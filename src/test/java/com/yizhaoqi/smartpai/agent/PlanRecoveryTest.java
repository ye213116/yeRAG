package com.yizhaoqi.smartpai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.model.*;
import com.yizhaoqi.smartpai.agent.plan.*;
import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.memory.*;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.*;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlanRecoveryTest {
    static final String QUESTION = "综合两份文档，详细比较成本和风险";
    static final AgentDecision PLAN = answer("{\"steps\":[\"读取甲方案\",\"读取乙方案\"]}");
    JdbcTemplate db;
    MemoryStore memory;
    PlanStore plans;
    MemoryTypes.Turn turn;
    AiProperties ai;
    Evidence tool;

    javax.sql.DataSource dataSource() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:plans_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return ds;
    }
    @BeforeEach void setup() throws Exception {
        var ds = dataSource();
        db = new JdbcTemplate(ds);
        try (var c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new FileSystemResource("docs/databases/ddl.sql")); }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        var tx = new DataSourceTransactionManager(ds);
        memory = new MemoryStore(db, tx, new MemoryProperties());
        ai = new AiProperties();
        plans = new PlanStore(db, tx, new ObjectMapper(), memory, ai);
        turn = memory.begin(1, null, "request", QUESTION).turn();
        tool = new Evidence();
    }
    static AgentDecision answer(String text) { return new AgentDecision(text, List.of()); }
    static AgentDecision call(String id) { return new AgentDecision(null, List.of(new ToolCall(id, "search_knowledge_base", "{}"))); }
    KnowledgeAgentService service(Client client) {
        var service = new KnowledgeAgentService(client, new AgentToolRegistry(List.of(tool)), ai);
        var guard = mock(PlanRecoveryGuard.class);
        when(guard.validate(any(), any(), any())).thenReturn(Mono.empty());
        service.configureRecovery(guard);
        return service;
    }
    Mono<AgentRunResult> execute(Client client, PlanJournal journal, Events events) {
        return service(client).run("1", QUESTION, List.of(), SourceMode.KNOWLEDGE, turn.sessionId(), events)
                .contextWrite(ctx -> ctx.put(PlanJournal.CONTEXT_KEY, journal));
    }
    PlanStore.Execution interruptedAfterFirstStep() {
        var journal = new PlanJournal(plans, turn, SourceMode.KNOWLEDGE);
        assertThrows(IllegalStateException.class, () -> execute(new Client(PLAN, call("a"), answer("甲证据")), journal, new Events()).block());
        journal.stop(false, "模拟中断", true);
        return plans.find(1, turn.sessionId(), "request").orElseThrow();
    }
    @Test void newExecutionResumesOriginalTurnWithoutRepeatingCompletedStepAndKeepsCitationsAndBudget() {
        var interrupted = interruptedAfterFirstStep();
        assertEquals(1, tool.calls);
        assertEquals(PlanStep.Status.COMPLETED, interrupted.checkpoint().steps().get(0).status);
        var resumed = plans.resume(1, turn.sessionId(), "request", interrupted.version());
        var journal = new PlanJournal(plans, memory.turn(turn.id()), resumed);
        var result = execute(new Client(call("b"), answer("乙证据")), journal, new Events()).block();
        assertEquals(2, tool.calls);
        assertEquals(List.of(0, 1), tool.offsets);
        assertEquals(Set.of(1, 2), result.sources().keySet());
        assertEquals(2, journal.execution().calls());
        assertEquals(2, journal.execution().checkpoint().steps().get(1).attempts);
        assertEquals("INTERRUPTED", db.queryForObject("SELECT status FROM agent_plan_step WHERE run_id=? AND step_id=2 AND attempt=1", String.class, resumed.id()));
        assertTrue(journal.complete(result, memory));
        assertEquals(1, memory.history(1, turn.sessionId()).size());
        assertEquals("COMPLETED", plans.find(1, turn.sessionId(), "request").orElseThrow().phase());
    }
    @Test void staleWritesAndLateCompletionCannotAffectResumedAttempt() {
        var old = interruptedAfterFirstStep();
        var current = plans.resume(1, turn.sessionId(), "request", old.version());
        assertThrows(PlanJournal.LeaseLost.class, () -> plans.checkpoint(old, old.checkpoint(), "RUNNING"));
        assertThrows(PlanJournal.LeaseLost.class, () -> plans.complete(old, turn, "late answer"));
        plans.stop(old, true, "late cancellation", false);
        assertTrue(memory.running(turn));
        assertTrue(plans.current(current));
        assertTrue(memory.history(1, turn.sessionId()).isEmpty());
    }
    @Test void onlyOneConcurrentResumeWinsAndCrossUserCannotReadOrResume() throws Exception {
        var paused = interruptedAfterFirstStep();
        assertThrows(ResponseStatusException.class, () -> plans.find(2, turn.sessionId(), "request"));
        assertThrows(ResponseStatusException.class, () -> plans.resume(2, turn.sessionId(), "request", paused.version()));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<Boolean> resume = () -> { start.await(); try { plans.resume(1, turn.sessionId(), "request", paused.version()); return true; }
                catch (ResponseStatusException conflict) { return false; } };
            var a = pool.submit(resume); var b = pool.submit(resume); start.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void userCancellationAndUnsafeEffectsAreNotResumable() {
        var paused = interruptedAfterFirstStep();
        plans.stop(paused, true, "用户取消", false);
        var cancelled = plans.find(1, turn.sessionId(), "request").orElseThrow();
        assertFalse(plans.resumable(cancelled));
        assertThrows(ResponseStatusException.class, () -> plans.resume(1, turn.sessionId(), "request", cancelled.version()));
        assertEquals("CANCELLED", memory.turn(turn.id()).status());
    }
    @Test void unsafeCallReservationSurvivesCrashAndBlocksBlindRetry() {
        var paused = interruptedAfterFirstStep();
        var resumed = plans.resume(1, turn.sessionId(), "request", paused.version());
        var afterReservation = plans.budget(resumed, resumed.calls(), 0, 1, resumed.decisions(), true);
        plans.stop(afterReservation, false, "保存结果未知", true);
        var blocked = plans.find(1, turn.sessionId(), "request").orElseThrow();
        assertTrue(blocked.unsafe()); assertFalse(plans.resumable(blocked));
        assertThrows(ResponseStatusException.class, () -> plans.resume(1, turn.sessionId(), "request", blocked.version()));
    }
    @Test void inFlightReservationsRemainChargedAndOldInterruptedAttemptRemainsImmutable() {
        var paused = interruptedAfterFirstStep();
        var current = plans.resume(1, turn.sessionId(), "request", paused.version());
        var reserved = plans.budget(current, 2, 0, 0, current.decisions() + 1, false);
        assertEquals(1, reserved.checkpoint().calls()); // The current step never finished.
        plans.stop(reserved, false, "read interrupted", true);
        var secondPause = plans.find(1, turn.sessionId(), "request").orElseThrow();
        var resumed = plans.resume(1, turn.sessionId(), "request", secondPause.version());
        assertEquals(2, resumed.calls());
        var step = resumed.checkpoint().steps().get(1);
        step.status = PlanStep.Status.SKIPPED; step.message = "预算不足";
        plans.checkpoint(resumed, resumed.checkpoint(), "RUNNING");
        assertEquals("INTERRUPTED", db.queryForObject("SELECT status FROM agent_plan_step WHERE run_id=? AND step_id=2 AND attempt=1", String.class, resumed.id()));
    }
    @Test void attemptsAndRecoveryWindowBoundResumption() {
        var paused = interruptedAfterFirstStep();
        ai.getAgent().getPlanning().setMaxAttempts(1);
        assertFalse(plans.resumable(paused));
        ai.getAgent().getPlanning().setMaxAttempts(3);
        db.update("UPDATE agent_plan_run SET created_at=TIMESTAMPADD(HOUR,-25,CURRENT_TIMESTAMP) WHERE id=?", paused.id());
        assertFalse(plans.resumable(paused));
        assertThrows(ResponseStatusException.class, () -> plans.resume(1, turn.sessionId(), "request", paused.version()));
    }
    @Test void expiredLeasePausesWorkAndStartupDoesNotInterruptOtherLivePlan() {
        var paused = interruptedAfterFirstStep();
        var active = plans.resume(1, turn.sessionId(), "request", paused.version());
        memory.recoverWorkRuns();
        assertTrue(memory.running(turn));
        assertTrue(plans.heartbeat(active));
        plans.recoverExpired(); assertTrue(plans.current(active));
        db.update("UPDATE agent_plan_run SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", active.id());
        assertFalse(plans.heartbeat(active));
        plans.recoverExpired();
        var recovered = plans.find(1, turn.sessionId(), "request").orElseThrow();
        assertTrue(plans.resumable(recovered));
        assertFalse(memory.running(turn));
        assertThrows(PlanJournal.LeaseLost.class, () -> plans.complete(active, turn, "late"));
    }
    @Test void changedContextCannotReachModelAndNewerTurnsPreventOldResume() {
        var paused = interruptedAfterFirstStep();
        var active = plans.resume(1, turn.sessionId(), "request", paused.version());
        var client = new Client();
        assertThrows(SecurityException.class, () -> service(client).run("1", QUESTION, List.of(AgentMessage.user("new context")),
                        SourceMode.KNOWLEDGE, turn.sessionId(), new Events())
                .contextWrite(ctx -> ctx.put(PlanJournal.CONTEXT_KEY, new PlanJournal(plans, turn, active))).block());
        assertEquals(0, client.decisions);
        plans.stop(active, false, "context changed", true);
        var newer = memory.begin(1, turn.sessionId(), "newer", "new question").turn(); memory.complete(newer, "answer");
        var old = plans.find(1, turn.sessionId(), "request").orElseThrow();
        assertThrows(ResponseStatusException.class, () -> plans.resume(1, turn.sessionId(), "request", old.version()));
    }
    @Test void revokedKnowledgePermissionRejectsCachedEvidenceBeforeUsingIt() {
        var paused = interruptedAfterFirstStep();
        var search = mock(HybridSearchService.class);
        when(search.readDocumentWithPermission(anyString(), anyInt(), anyInt(), eq("1"))).thenReturn(List.of());
        var guard = new PlanRecoveryGuard(search, ai);
        var request = new AgentRunRequest("1", QUESTION, List.of(), turn.sessionId(), SourceMode.KNOWLEDGE, AgentMode.WORK, null);
        assertThrows(SecurityException.class, () -> guard.validate(paused, request, new AgentToolRegistry(List.of(tool)).snapshot()).block());
    }
    @Test void decisionExhaustionOnlyProducesOneFinalAnswerAndSuccessfulRetryClearsFailure() {
        ai.getAgent().getProfiles().getWork().setMaxDecisions(1);
        var events = new Events();
        service(new Client(PLAN, call("a"))).run("1", QUESTION, List.of(), SourceMode.KNOWLEDGE, turn.sessionId(), events).block();
        assertEquals(1, Collections.frequency(events.chunks, "final answer"));
        assertEquals("failed", events.tasks.get(0).get("status"));
        ai.getAgent().getProfiles().getWork().setMaxDecisions(null);
        tool.failFirst = true; tool.calls = 0;
        events = new Events();
        service(new Client(PLAN, call("retry"), answer("取得依据"), answer("未找到其他依据")))
                .run("1", QUESTION, List.of(), SourceMode.KNOWLEDGE, turn.sessionId(), events).block();
        assertEquals(2, tool.calls);
        assertEquals("completed", events.tasks.get(0).get("status"));
        assertEquals("NO_EVIDENCE", events.tasks.get(1).get("outcome"));
    }
    @Test void deletingSessionDeletesPrivatePlanCheckpointsAndAttemptHistory() {
        interruptedAfterFirstStep();
        memory.deleteSession(1, turn.sessionId());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_plan_run", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_plan_step", Integer.class));
    }
    @Test void jsonKeyOrderingAcrossProcessesDoesNotInvalidateIdenticalMemoryContext() {
        var first = List.of(AgentMessage.user("{\"memory_data\":{\"summary\":\"same\",\"memory_index\":\"index\"}}"));
        var reordered = List.of(AgentMessage.user("{\"memory_data\":{\"memory_index\":\"index\",\"summary\":\"same\"}}"));
        assertEquals(PlanFingerprint.context(first), PlanFingerprint.context(reordered));
        assertNotEquals(PlanFingerprint.context(first), PlanFingerprint.context(List.of(AgentMessage.user("changed"))));
        assertEquals(PlanFingerprint.content("{\"a\":1,\"b\":2}"), PlanFingerprint.content("{\"b\":2,\"a\":1}"));
    }

    static final class Client implements AgentLlmClient {
        final Deque<AgentDecision> replies = new ArrayDeque<>(); int decisions;
        Client(AgentDecision... values) { replies.addAll(Arrays.asList(values)); }
        public Mono<AgentDecision> decide(List<AgentMessage> history, List<Map<String, Object>> definitions) {
            decisions++;
            return replies.isEmpty() ? Mono.error(new IllegalStateException("simulated provider interruption")) : Mono.just(replies.removeFirst());
        }
        public Flux<String> streamFinal(List<AgentMessage> history) { return Flux.just("final answer"); }
    }
    static final class Evidence implements AgentTool {
        int calls; boolean failFirst; List<Integer> offsets = new ArrayList<>();
        public String name() { return "search_knowledge_base"; }
        public Source source() { return Source.KNOWLEDGE; }
        public boolean replaySafe() { return true; }
        public Map<String, Object> definition() { return Map.of("name", name()); }
        public ToolExecutionResult execute(String arguments, ToolExecutionContext context) {
            calls++;
            if (failFirst && calls == 1) return ToolExecutionResult.error("tool_failed", "temporary");
            offsets.add(context.referenceOffset()); int n = context.referenceOffset() + 1;
            return new ToolExecutionResult(true, "source#" + n, 1, Map.of(n, "a".repeat(32)),
                    Map.of(n, SourceReference.knowledge(n, "document", "a".repeat(32), n)));
        }
    }
    static final class Events implements AgentEventListener {
        List<String> chunks = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> tasks = List.of();
        @SuppressWarnings("unchecked") public void onStatus(String stage, String message, Map<String, Object> details) {
            if (stage.equals("plan_updated")) tasks = (List<Map<String, Object>>) details.get("tasks");
        }
        public void onChunk(String chunk) { chunks.add(chunk); }
    }
}
