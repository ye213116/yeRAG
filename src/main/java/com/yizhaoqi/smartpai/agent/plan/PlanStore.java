package com.yizhaoqi.smartpai.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.memory.MemoryTypes.Turn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Durable Work plans. Transactions never span model, search or tool calls. */
@Repository
public class PlanStore {
    public record Execution(String id, long owner, String conversation, String request, long turnId,
                            SourceMode mode, int attempt, long version, String phase, String reason,
                            boolean unsafe, PlanCheckpoint checkpoint, int calls, int memoryCalls,
                            int artifactCalls, int decisions, Instant createdAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MemoryStore memory;
    private final AiProperties.Planning config;

    public PlanStore(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json,
                     MemoryStore memory, AiProperties ai) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.json = json;
        this.memory = memory; this.config = ai.getAgent().getPlanning();
        if (config.getMaxAttempts() < 1 || config.getMaxAttempts() > 10 || config.getLeaseDuration() == null
                || config.getLeaseDuration().compareTo(Duration.ofSeconds(30)) < 0 || config.getLeaseDuration().compareTo(Duration.ofMinutes(10)) > 0
                || config.getRecoveryTtl() == null || config.getRecoveryTtl().isZero() || config.getRecoveryTtl().isNegative())
            throw new IllegalArgumentException("Invalid plan recovery limits");
    }
    private <T> T transaction(Supplier<T> work) { return tx.execute(s -> work.get()); }
    private void lockUser(long user) {
        if (db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", user).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private String encode(Object value) {
        try {
            String text = json.writeValueAsString(value);
            if (text.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
                throw new IllegalArgumentException("Plan checkpoint exceeds 8 MiB");
            return text;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private Execution map(Map<String, Object> row) {
        try {
            Object created = row.get("created_at");
            Instant at = created instanceof Timestamp stamp ? stamp.toInstant() : Timestamp.valueOf((LocalDateTime) created).toInstant();
            return new Execution((String) row.get("id"), ((Number) row.get("owner_id")).longValue(),
                    (String) row.get("conversation_id"), (String) row.get("request_id"), ((Number) row.get("turn_id")).longValue(),
                    SourceMode.valueOf((String) row.get("source_mode")), ((Number) row.get("attempt")).intValue(),
                    ((Number) row.get("version")).longValue(), (String) row.get("phase"), (String) row.get("reason"),
                    Boolean.TRUE.equals(row.get("unsafe_effect")) || row.get("unsafe_effect") instanceof Number n && n.intValue() != 0,
                    json.readValue((String) row.get("checkpoint_json"), PlanCheckpoint.class),
                    ((Number) row.get("calls")).intValue(), ((Number) row.get("memory_calls")).intValue(),
                    ((Number) row.get("artifact_calls")).intValue(), ((Number) row.get("decisions")).intValue(), at);
        } catch (Exception invalid) { throw new IllegalStateException("Invalid stored plan", invalid); }
    }
    private Execution internal(String id) { return map(db.queryForMap("SELECT * FROM agent_plan_run WHERE id=?", id)); }
    public Optional<Execution> find(long user, String conversation, String request) {
        memory.requireWorkSession(user, conversation);
        return db.queryForList("SELECT * FROM agent_plan_run WHERE owner_id=? AND conversation_id=? AND request_id=?",
                user, conversation, request).stream().findFirst().map(this::map);
    }
    private int leaseSeconds() { return (int) Math.max(30, config.getLeaseDuration().toSeconds()); }
    private boolean active(String phase) { return Set.of("RUNNING", "SYNTHESIZING").contains(phase); }
    public boolean current(Execution run) {
        return db.queryForObject("""
            SELECT COUNT(*) FROM agent_plan_run p JOIN chat_turn t ON t.id=p.turn_id
            JOIN chat_session s ON s.id=p.conversation_id
            WHERE p.id=? AND p.attempt=? AND p.version=? AND p.phase IN ('RUNNING','SYNTHESIZING')
              AND p.lease_until>CURRENT_TIMESTAMP AND t.status='RUNNING' AND s.active_turn_id=t.id AND s.status<>'DELETED'
            """, Long.class, run.id(), run.attempt(), run.version()) == 1;
    }
    public Execution create(Turn turn, SourceMode mode, PlanCheckpoint checkpoint) {
        return transaction(() -> {
            lockUser(turn.userId());
            if (!memory.running(turn)) throw new PlanJournal.LeaseLost();
            String id = UUID.randomUUID().toString();
            db.update("""
                INSERT INTO agent_plan_run(id,owner_id,conversation_id,request_id,turn_id,source_mode,phase,attempt,version,
                  checkpoint_json,calls,memory_calls,artifact_calls,decisions,unsafe_effect,lease_until,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'RUNNING',1,1,?,?,?,?,?,FALSE,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, turn.userId(), turn.sessionId(), turn.requestId(), turn.id(), mode.name(), encode(checkpoint),
                    checkpoint.calls(), checkpoint.memoryCalls(), checkpoint.artifactCalls(), checkpoint.decisions(), leaseSeconds());
            writeSteps(id, checkpoint);
            return internal(id);
        });
    }
    public Execution checkpoint(Execution run, PlanCheckpoint checkpoint, String phase) {
        if (!active(phase)) throw new IllegalArgumentException("Invalid active plan phase");
        return transaction(() -> {
            int changed = db.update("""
                UPDATE agent_plan_run SET checkpoint_json=?,phase=?,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND attempt=? AND version=? AND phase IN ('RUNNING','SYNTHESIZING') AND lease_until>CURRENT_TIMESTAMP
                """, encode(checkpoint), phase, run.id(), run.attempt(), run.version());
            if (changed != 1) throw new PlanJournal.LeaseLost();
            writeSteps(run.id(), checkpoint);
            return internal(run.id());
        });
    }
    private void writeSteps(String id, PlanCheckpoint checkpoint) {
        for (PlanStep step : checkpoint.steps()) {
            if (step.attempts == 0) continue;
            String result = step.result == null ? null : encode(step.result);
            if (db.queryForList("SELECT step_id FROM agent_plan_step WHERE run_id=? AND step_id=? AND attempt=?",
                    id, step.id, step.attempts).isEmpty())
                db.update("INSERT INTO agent_plan_step(run_id,step_id,attempt,title,status,result_json,updated_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                        id, step.id, step.attempts, step.title, step.status.name(), result);
            else db.update("UPDATE agent_plan_step SET status=?,result_json=?,updated_at=CURRENT_TIMESTAMP WHERE run_id=? AND step_id=? AND attempt=? AND status='RUNNING'",
                    step.status.name(), result, id, step.id, step.attempts);
        }
    }
    /** Budget reservation is durable even when the following read never returns. */
    public Execution budget(Execution run, int calls, int memories, int artifacts, int decisions, boolean unsafe) {
        return transaction(() -> {
            int changed = db.update("""
                UPDATE agent_plan_run SET calls=?,memory_calls=?,artifact_calls=?,decisions=?,
                  unsafe_effect=CASE WHEN ? THEN TRUE ELSE unsafe_effect END,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND attempt=? AND version=? AND phase IN ('RUNNING','SYNTHESIZING') AND lease_until>CURRENT_TIMESTAMP
                  AND calls<=? AND memory_calls<=? AND artifact_calls<=? AND decisions<=?
                """, calls, memories, artifacts, decisions, unsafe, run.id(), run.attempt(), run.version(), calls, memories, artifacts, decisions);
            if (changed != 1) throw new PlanJournal.LeaseLost();
            return internal(run.id());
        });
    }
    public boolean heartbeat(Execution run) {
        return db.update("""
            UPDATE agent_plan_run SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP)
            WHERE id=? AND attempt=? AND version=? AND phase IN ('RUNNING','SYNTHESIZING') AND lease_until>CURRENT_TIMESTAMP
            """, leaseSeconds(), run.id(), run.attempt(), run.version()) == 1;
    }
    public boolean complete(Execution run, Turn turn, String answer) {
        return transaction(() -> {
            lockUser(run.owner());
            db.queryForList("SELECT id FROM agent_plan_run WHERE id=? FOR UPDATE", run.id());
            if (!current(run)) throw new PlanJournal.LeaseLost();
            if (!memory.complete(turn, answer)) return false;
            db.update("UPDATE agent_plan_run SET phase='COMPLETED',version=version+1,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", run.id());
            return true;
        });
    }
    public void stop(Execution run, boolean cancelled, String reason, boolean resumable) {
        transaction(() -> {
            lockUser(run.owner());
            int changed = db.update("""
                UPDATE agent_plan_run SET phase=?,reason=?,version=version+1,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND attempt=? AND version=? AND phase IN ('RUNNING','SYNTHESIZING','PAUSED')
                """, cancelled ? "CANCELLED" : resumable ? "PAUSED" : "FAILED", reason, run.id(), run.attempt(), run.version());
            if (changed == 1) {
                db.update("UPDATE agent_plan_step SET status=?,updated_at=CURRENT_TIMESTAMP WHERE run_id=? AND status='RUNNING'",
                        cancelled ? "CANCELLED" : "INTERRUPTED", run.id());
                Turn turn = memory.turn(run.turnId());
                memory.cancel(turn, cancelled ? "CANCELLED" : "FAILED");
                if (cancelled) db.update("UPDATE chat_turn SET status='CANCELLED',failure_code=NULL WHERE id=? AND status='FAILED' AND failure_code='PLAN_INTERRUPTED'", run.turnId());
                if (!cancelled) memory.failureCode(turn, "PLAN_INTERRUPTED");
            }
            return null;
        });
    }
    public boolean resumable(Execution run) {
        return "PAUSED".equals(run.phase()) && !run.unsafe() && run.attempt() < config.getMaxAttempts()
                && withinRecoveryWindow(run);
    }
    private boolean withinRecoveryWindow(Execution run) {
        return db.queryForObject("SELECT COUNT(*) FROM agent_plan_run WHERE id=? AND created_at>TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP)",
                Long.class, run.id(), -Math.max(1, config.getRecoveryTtl().toSeconds())) == 1;
    }
    public Execution resume(long user, String conversation, String request, long version) {
        return transaction(() -> {
            lockUser(user);
            memory.requireWorkSession(user, conversation);
            var rows = db.queryForList("SELECT * FROM agent_plan_run WHERE owner_id=? AND conversation_id=? AND request_id=? FOR UPDATE", user, conversation, request);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有可恢复的执行计划");
            Execution run = map(rows.get(0));
            if (run.version() != version || !resumable(run)) throw new ResponseStatusException(HttpStatus.CONFLICT, "计划状态已变化或不可恢复，请刷新运行状态");
            memory.resumeWorkTurn(user, conversation, request);
            db.update("""
                UPDATE agent_plan_run SET phase=?,reason=NULL,attempt=attempt+1,version=version+1,
                  lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?
                """, "SYNTHESIZING".equals(checkpointPhase(run)) ? "SYNTHESIZING" : "RUNNING", leaseSeconds(), run.id(), version);
            return internal(run.id());
        });
    }
    private String checkpointPhase(Execution run) {
        var steps = run.checkpoint().steps();
        return steps.get(steps.size() - 1).attempts > 0 ? "SYNTHESIZING" : "RUNNING";
    }
    public Map<String, Object> enrich(long user, String conversation, Map<String, Object> snapshot) {
        Object request = snapshot.get("requestId");
        if (request == null) return snapshot;
        var found = find(user, conversation, request.toString());
        if (found.isEmpty()) return snapshot;
        Execution run = found.get();
        var value = new HashMap<>(snapshot);
        value.put("planId", run.id()); value.put("planAttempt", run.attempt()); value.put("planVersion", run.version());
        value.put("planPhase", run.phase()); value.put("resumable", resumable(run));
        if ("CANCELLED".equals(run.phase())) value.put("state", "CANCELLED");
        if (Set.of("FAILED", "PAUSED").contains(run.phase())) value.put("state", "FAILED");
        value.put("planOutcome", run.checkpoint().steps().stream().limit(run.checkpoint().steps().size() - 1L)
                .allMatch(step -> step.result != null && step.result.outcome() == PlanStep.Outcome.EVIDENCE_FOUND) ? "EVIDENCE_FOUND" : "PARTIAL");
        value.put("recoveryReason", run.unsafe() ? "执行过可能产生副作用的工具，请核对成果后重新发起任务"
                : run.attempt() >= config.getMaxAttempts() ? "恢复次数已达上限，请重新发起任务"
                : !withinRecoveryWindow(run) ? "检查点已超过恢复窗口，请重新发起任务" : Objects.toString(run.reason(), ""));
        value.put("planTasks", run.checkpoint().steps().stream().map(step -> {
            var item = new LinkedHashMap<>(step.display());
            if (!active(run.phase()) && step.status == PlanStep.Status.RUNNING) {
                item.put("status", "CANCELLED".equals(run.phase()) ? "cancelled" : "interrupted");
                item.put("message", "执行已中断");
            }
            return item;
        }).toList());
        return value;
    }
    @Scheduled(fixedDelay = 10000)
    public void recoverExpired() {
        for (var row : db.queryForList("SELECT * FROM agent_plan_run WHERE phase IN ('RUNNING','SYNTHESIZING') AND lease_until<=CURRENT_TIMESTAMP")) {
            Execution run = map(row);
            // stop's version fence loses to a concurrent heartbeat/checkpoint or a new attempt.
            transaction(() -> {
                lockUser(run.owner());
                var expired = db.queryForList("SELECT id FROM agent_plan_run WHERE id=? AND version=? AND lease_until<=CURRENT_TIMESTAMP FOR UPDATE", run.id(), run.version());
                if (!expired.isEmpty()) stop(run, false, "执行进程中断，可从检查点继续", true);
                return null;
            });
        }
    }
}
