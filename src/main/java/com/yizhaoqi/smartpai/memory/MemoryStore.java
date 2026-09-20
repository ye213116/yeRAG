package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

/** Explicit SQL repository: session writes serialize on users.id; document writes lock task/document/section.
 * No transaction/lock spans a model or Redis call. */
@Repository
public class MemoryStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MemoryProperties config;
    private final ObjectMapper json;

    public MemoryStore(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager,
                       MemoryProperties config, ObjectMapper json) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.config = config; this.json = json;
    }
    public void verifySchema() {
        db.queryForList("SELECT active_turn_id,context_expires_at FROM chat_session WHERE 1=0");
        db.queryForList("SELECT user_turn_seq,request_hash,context_sync_status FROM chat_turn WHERE 1=0");
        db.queryForList("SELECT lease_token,lease_until FROM memory_extraction_task WHERE 1=0");
        if ("document".equals(config.getStorageMode())) {
            db.queryForList("SELECT user_id,memory_type,content,created_at,updated_at FROM memory_document WHERE 1=0");
            db.queryForList("SELECT id,document_id,heading,last_applied_turn_seq,deleted,created_at,updated_at FROM memory_document_section WHERE 1=0");
        } else {
            db.queryForList("SELECT current_version_id,last_applied_turn_seq FROM memory_slot WHERE 1=0");
            db.queryForList("SELECT value_json,operation,evidence FROM memory_version WHERE 1=0");
        }
    }
    private Timestamp time(Instant t) { return t == null ? null : Timestamp.from(t); }
    /**
     * MySQL Connector/J maps DATETIME to LocalDateTime in queryForMap, while H2
     * commonly returns Timestamp. Keep temporal reads independent of the driver.
     */
    static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime localDateTime) return Timestamp.valueOf(localDateTime).toInstant();
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof Date date) return date.toInstant();
        throw new IllegalStateException("unsupported database time value: " +
                (value == null ? "null" : value.getClass().getName()));
    }
    private void lockUser(long user) {
        if (db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", user).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
    }
    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(c -> {
            var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public Map<String, Object> session(long user, String id) {
        var rows = db.queryForList("SELECT * FROM chat_session WHERE id=? AND user_id=?", id, user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        return rows.get(0);
    }
    public List<Map<String, Object>> sessions(long user) {
        return db.queryForList("SELECT id,status,last_activity_at FROM chat_session WHERE user_id=? ORDER BY created_at DESC LIMIT 100", user);
    }
    public void importLegacy(long user, String legacyId, List<Map<String,String>> messages) {
        importLegacy(user,legacyId,messages,null);
    }
    public void importLegacy(long user, String legacyId, List<Map<String,String>> messages, java.time.Duration remaining) {
        tx.executeWithoutResult(s -> {
            lockUser(user);
            if (!sessions(user).isEmpty()) return;
            Instant now=Instant.now(); String sid=currentLocked(user,false,now);
            java.time.Duration retention=remaining==null || remaining.compareTo(config.getContextTtl())>0 ? config.getContextTtl() : remaining;
            db.update("UPDATE chat_session SET context_expires_at=? WHERE id=?",time(now.plus(retention)),sid);
            for(int i=0;i+1<Math.min(messages.size(),20);i++) {
                var q=messages.get(i); var a=messages.get(i+1);
                if (!"user".equals(q.get("role")) || !"assistant".equals(a.get("role")) || q.get("content")==null || a.get("content")==null) continue;
                String request="legacy-"+hash(legacyId+":"+i);
                Instant created=now;
                try {
                    if(q.get("timestamp")!=null) created=java.time.LocalDateTime.parse(q.get("timestamp")).atZone(java.time.ZoneId.systemDefault()).toInstant();
                } catch(java.time.format.DateTimeParseException ignored) { }
                long id=insert("INSERT INTO chat_turn(session_id,user_id,request_id,request_hash,question,answer,status,context_sync_status,context_next_attempt_at,created_at,completed_at,content_expires_at) VALUES (?,?,?,?,?,?,'COMPLETED','PENDING',?,?,?,?)",
                        sid,user,request,hash(q.get("content")),q.get("content"),a.get("content"),time(now),time(created),time(created),time(now.plus(retention.compareTo(config.getContentRetention())<0 ? retention : config.getContentRetention())));
                db.update("UPDATE chat_turn SET user_turn_seq=? WHERE id=?",id,id); i++;
            }
        });
    }
    public String current(long user, boolean forceNew) {
        return tx.execute(s -> { lockUser(user); return currentLocked(user, forceNew, Instant.now()); });
    }
    private String currentLocked(long user, boolean forceNew, Instant now) {
        var rows = db.queryForList("SELECT * FROM chat_session WHERE user_id=? AND status='OPEN' ORDER BY created_at DESC", user);
        if (!rows.isEmpty()) {
            var row = rows.get(0);
            boolean running = row.get("active_turn_id") != null;
            boolean idle = instant(row.get("last_activity_at")).plus(config.getIdleTimeout()).isBefore(now);
            if (!forceNew && (running || !idle)) return (String) row.get("id");
            for (var old : rows) {
                db.update("UPDATE chat_turn SET status='CANCELLED' WHERE session_id=? AND status='RUNNING'", old.get("id"));
                db.update("UPDATE chat_session SET status='CLOSED',closed_at=?,active_turn_id=NULL,revision=revision+1 WHERE id=?", time(now), old.get("id"));
            }
        }
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO chat_session(id,user_id,status,last_activity_at,context_expires_at,created_at,revision) VALUES (?,?,'OPEN',?,?,?,0)",
                id, user, time(now), time(now.plus(config.getContextTtl())), time(now));
        return id;
    }
    public Begin begin(long user, String requestedSession, String requestId, String question) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,80}") || question == null || question.isBlank()
                || question.getBytes(StandardCharsets.UTF_8).length > config.getMaxQuestionBytes())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息为空、过长或请求标识无效");
        return tx.execute(s -> {
            lockUser(user);
            var previous = db.queryForList("SELECT * FROM chat_turn WHERE user_id=? AND request_id=?", user, requestId);
            if (!previous.isEmpty()) {
                var row = previous.get(0);
                if (!hash(question).equals(row.get("request_hash")) || (requestedSession != null && !requestedSession.equals(row.get("session_id"))))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "请求标识已用于其他内容或会话");
                return new Begin(turn(row), false, false);
            }
            if (requestedSession != null) {
                var selected = session(user, requestedSession);
                if (!"OPEN".equals(selected.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换到当前会话");
            }
            Instant now = Instant.now();
            String id = currentLocked(user, false, now);
            var row = session(user, id);
            if (row.get("active_turn_id") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话有正在执行的问答，请先停止");
            long turn = insert("INSERT INTO chat_turn(session_id,user_id,request_id,request_hash,question,status,created_at,content_expires_at) VALUES (?,?,?,?,?,'RUNNING',?,?)",
                    id, user, requestId, hash(question), question, time(now), time(now.plus(config.getContentRetention())));
            db.update("UPDATE chat_turn SET user_turn_seq=? WHERE id=?", turn, turn);
            db.update("UPDATE chat_session SET active_turn_id=?,last_activity_at=?,context_expires_at=?,revision=revision+1 WHERE id=?",
                    turn, time(now), time(now.plus(config.getContextTtl())), id);
            return new Begin(new Turn(turn, user, id, requestId, question, null, "RUNNING", now), true,
                    requestedSession != null && !id.equals(requestedSession));
        });
    }
    private Turn turn(Map<String, Object> r) {
        return new Turn(((Number) r.get("id")).longValue(), ((Number) r.get("user_id")).longValue(),
                (String) r.get("session_id"), (String) r.get("request_id"), (String) r.get("question"),
                (String) r.get("answer"), (String) r.get("status"), instant(r.get("created_at")));
    }
    public Turn turn(long id) { return turn(db.queryForMap("SELECT * FROM chat_turn WHERE id=?", id)); }
    public boolean running(Turn t) {
        return db.queryForObject("SELECT COUNT(*) FROM chat_turn t JOIN chat_session s ON s.id=t.session_id WHERE t.id=? AND t.status='RUNNING' AND s.active_turn_id=t.id AND s.status='OPEN'", Long.class, t.id()) == 1;
    }
    public boolean complete(Turn t, String answer) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            lockUser(t.userId());
            if (!running(t)) return false;
            Instant now = Instant.now();
            db.update("UPDATE chat_turn SET answer=?,status='COMPLETED',completed_at=?,context_sync_status='PENDING',context_next_attempt_at=? WHERE id=?",
                    answer, time(now), time(now), t.id());
            db.update("UPDATE chat_session SET active_turn_id=NULL,context_expires_at=?,revision=revision+1 WHERE id=? AND active_turn_id=?",
                    time(now.plus(config.getContextTtl())), t.sessionId(), t.id());
            if (config.isExtractionEnabled()) db.update("INSERT INTO memory_extraction_task(turn_id,status,next_attempt_at,created_at,updated_at) VALUES (?,'PENDING',?,?,?)",
                    t.id(), time(now), time(now), time(now));
            return true;
        }));
    }
    public void cancel(Turn t, String status) {
        tx.executeWithoutResult(s -> {
            lockUser(t.userId());
            db.update("UPDATE chat_turn SET status=? WHERE id=? AND status='RUNNING'", status, t.id());
            db.update("UPDATE chat_session SET active_turn_id=NULL,revision=revision+1 WHERE id=? AND active_turn_id=?", t.sessionId(), t.id());
        });
    }
    public List<Turn> history(long user, String session) {
        session(user, session);
        // Bounded DB reads; older completed turns can be requested separately for history display.
        var rows = db.queryForList("SELECT * FROM chat_turn WHERE user_id=? AND session_id=? AND status='COMPLETED' AND content_expires_at>? ORDER BY id DESC LIMIT 200", user, session, time(Instant.now()));
        List<Turn> turns = new ArrayList<>(); rows.forEach(r -> turns.add(turn(r))); Collections.reverse(turns); return turns;
    }
    public List<Map<String, Object>> memories(long user) {
        return db.queryForList("SELECT s.memory_key,v.* FROM memory_slot s JOIN memory_version v ON v.id=s.current_version_id AND v.slot_id=s.id WHERE s.user_id=? AND v.status='ACTIVE' AND (v.expires_at IS NULL OR v.expires_at>?) ORDER BY CASE WHEN v.category='preference' THEN 0 ELSE 1 END,v.created_at DESC,s.id LIMIT ?",
                user, time(Instant.now()), Math.max(1, config.getMaxMemories()));
    }
    /** One joined statement gives recall a consistent snapshot of current documents and metadata. */
    public List<MemoryDocument> documents(long user) {
        var rows = db.queryForList("""
            SELECT d.id AS doc_id,d.user_id,d.memory_type,d.content,
                   s.id AS section_id,s.heading,s.last_applied_turn_seq,s.deleted
            FROM memory_document d LEFT JOIN memory_document_section s ON s.document_id=d.id
            WHERE d.user_id=? ORDER BY d.memory_type,s.heading,s.id
            """, user);
        Map<Long,MemoryDocument> result = new LinkedHashMap<>();
        for (var r : rows) {
            long id = ((Number) r.get("doc_id")).longValue();
            var doc = result.computeIfAbsent(id, ignored -> new MemoryDocument(id,user,
                    MemoryType.valueOf((String)r.get("memory_type")),(String)r.get("content"),new ArrayList<>()));
            if (r.get("section_id") != null) doc.sections().add(metadata(r,"section_id",id));
        }
        return result.values().stream().sorted(Comparator.comparing(MemoryDocument::type))
                .map(d -> new MemoryDocument(d.id(),d.userId(),d.type(),d.content(),List.copyOf(d.sections()))).toList();
    }
    private SectionMetadata metadata(Map<String,Object> row, String idColumn, long documentId) {
        Object deleted = row.get("deleted");
        return new SectionMetadata(UUID.fromString((String)row.get(idColumn)),documentId,(String)row.get("heading"),
                ((Number)row.get("last_applied_turn_seq")).longValue(),
                Boolean.TRUE.equals(deleted) || deleted instanceof Number n && n.intValue()!=0);
    }
    /** Model calls happen before this transaction. A task commits all documents and DONE together. */
    public void applyPatches(Task task, List<MemoryPatch> patches) {
        Objects.requireNonNull(patches,"patches");
        if (patches.size()>config.getMaxPatches()) throw new IllegalArgumentException("too many patches");
        Set<UUID> seen = new HashSet<>();
        for (var p : patches) {
            if (p==null || p.target()==null || p.operation()==null || (p.sectionId()!=null && !seen.add(p.sectionId())))
                throw new IllegalArgumentException("invalid or duplicate patch");
        }
        tx.executeWithoutResult(status -> {
            var lease = db.queryForMap("SELECT * FROM memory_extraction_task WHERE id=? FOR UPDATE",task.id());
            if (!"RUNNING".equals(lease.get("status")) || !task.token().equals(lease.get("lease_token"))
                    || !instant(lease.get("lease_until")).isAfter(Instant.now())) return;
            if (((Number)lease.get("turn_id")).longValue()!=task.turnId()) throw new IllegalArgumentException("task source mismatch");
            var source = db.queryForMap("SELECT * FROM chat_turn WHERE id=?",task.turnId());
            if (source.get("question")==null || !instant(source.get("content_expires_at")).isAfter(Instant.now())) {
                db.update("UPDATE memory_extraction_task SET status='EXPIRED',last_error_code='source_expired',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",time(Instant.now()),task.id());
                return;
            }
            if (!"COMPLETED".equals(source.get("status"))) throw new IllegalArgumentException("source not completed");
            long user = ((Number)source.get("user_id")).longValue();
            long seq = ((Number)source.get("user_turn_seq")).longValue();
            Instant now = Instant.now();
            var editor = new MemoryDocumentEditor();
            Map<MemoryType,MemoryDocument> locked = new EnumMap<>(MemoryType.class);
            for (var type : patches.stream().map(MemoryPatch::target).distinct().sorted().toList()) {
                String title = type==MemoryType.PROFILE ? "# Profile Memory\n" : "# Preferences Memory\n";
                db.update("INSERT INTO memory_document(user_id,memory_type,content,created_at,updated_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                        user,type.name(),title,time(now),time(now));
                var row = db.queryForMap("SELECT * FROM memory_document WHERE user_id=? AND memory_type=? FOR UPDATE",user,type.name());
                long id = ((Number)row.get("id")).longValue();
                var sections = db.queryForList("SELECT * FROM memory_document_section WHERE document_id=? ORDER BY id FOR UPDATE",id)
                        .stream().map(r -> metadata(r,"id",id)).toList();
                locked.put(type,new MemoryDocument(id,user,type,(String)row.get("content"),sections));
            }
            // Recheck source and ownership against the locked state, never trust the extraction snapshot.
            new MemoryPolicy(config).validatePatches((String)source.get("question"),patches,List.copyOf(locked.values()));
            for (var doc : locked.values()) {
                Map<UUID,SectionMetadata> catalog = new LinkedHashMap<>();
                doc.sections().forEach(section -> catalog.put(section.id(),section));
                List<ResolvedMemoryPatch> resolved = new ArrayList<>();
                for (var p : patches) {
                    if (p.target()!=doc.type()) continue;
                    SectionMetadata old = p.sectionId()==null ? null : catalog.get(p.sectionId());
                    if (p.sectionId()!=null && old==null) throw new IllegalArgumentException("section does not belong to document");
                    if (old!=null && seq<=old.lastAppliedTurnSeq()) continue;
                    UUID id = p.sectionId()==null ? UUID.randomUUID() : p.sectionId();
                    resolved.add(new ResolvedMemoryPatch(id,p.operation()==MemoryPatchOperation.DELETE_SECTION ? old.heading() : p.heading().strip(),p.operation(),p.content()));
                }
                if (resolved.isEmpty()) continue;
                String content = editor.apply(doc.content(),resolved,catalog).content();
                if (MemoryContextService.bytes(content)>config.getMaxDocumentBytes()) throw new IllegalArgumentException("document too large");
                if (!content.equals(doc.content())) db.update("UPDATE memory_document SET content=?,updated_at=? WHERE id=?",content,time(now),doc.id());
                for (var p : resolved) {
                    boolean deleted = p.operation()==MemoryPatchOperation.DELETE_SECTION;
                    if (catalog.containsKey(p.sectionId())) {
                        db.update("UPDATE memory_document_section SET heading=?,deleted=?,last_applied_turn_seq=?,updated_at=? WHERE id=? AND document_id=?",
                                p.heading(),deleted,seq,time(now),p.sectionId().toString(),doc.id());
                    } else {
                        db.update("INSERT INTO memory_document_section(id,document_id,heading,last_applied_turn_seq,deleted,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                                p.sectionId().toString(),doc.id(),p.heading(),seq,deleted,time(now),time(now));
                    }
                }
            }
            db.update("UPDATE memory_extraction_task SET status='DONE',updated_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",time(now),task.id());
        });
    }

    /** Called only by the existing admin-authorized history controller; never returns memory facts. */
    public List<Map<String,Object>> adminHistory(Long target, String start, String end) {
        String sql="SELECT t.*,u.username FROM chat_turn t JOIN users u ON u.id=t.user_id WHERE t.status='COMPLETED' AND t.content_expires_at>?";
        List<Object> args=new ArrayList<>(); args.add(time(Instant.now()));
        if(target!=null) { sql+=" AND t.user_id=?"; args.add(target); }
        if(start!=null && !start.isBlank()) { sql+=" AND t.created_at>=?"; args.add(time(java.time.LocalDate.parse(start.substring(0,10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())); }
        if(end!=null && !end.isBlank()) { sql+=" AND t.created_at<?"; args.add(time(java.time.LocalDate.parse(end.substring(0,10)).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())); }
        List<Map<String,Object>> result=new ArrayList<>();
        for(var r:db.queryForList(sql+" ORDER BY t.id DESC LIMIT 200",args.toArray())) {
            for(String role:List.of("user","assistant")) result.add(Map.of("role",role,"content",r.get(role.equals("user")?"question":"answer"),"username",r.get("username"),"timestamp",r.get("created_at").toString()));
        }
        return result;
    }
    public List<Turn> pendingSync() {
        return db.queryForList("SELECT * FROM chat_turn WHERE context_sync_status='PENDING' AND context_next_attempt_at<=? ORDER BY id LIMIT 20", time(Instant.now())).stream().map(this::turn).toList();
    }
    public void synced(String session, long through) {
        db.update("UPDATE chat_turn SET context_sync_status='SYNCED' WHERE session_id=? AND id<=? AND status='COMPLETED'", session, through);
    }
    public void syncedTurns(String session, List<Long> ids) {
        if (ids.isEmpty()) return;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        List<Object> args=new ArrayList<>(); args.add(session); args.addAll(ids);
        db.update("UPDATE chat_turn SET context_sync_status='SYNCED' WHERE session_id=? AND status='COMPLETED' AND id IN ("+placeholders+")",args.toArray());
    }
    public void contextExpired(long turnId) {
        db.update("UPDATE chat_turn SET context_sync_status='EXPIRED' WHERE id=? AND context_sync_status='PENDING'", turnId);
    }
    public void expireOmittedContext(String session, long before) {
        db.update("UPDATE chat_turn SET context_sync_status='EXPIRED' WHERE session_id=? AND id<? AND context_sync_status='PENDING'",session,before);
    }
    public void syncFailed(long id) {
        db.update("UPDATE chat_turn SET context_sync_attempts=context_sync_attempts+1,context_next_attempt_at=? WHERE id=? AND context_sync_status='PENDING'", time(Instant.now().plusSeconds(30)), id);
    }
    public Optional<Task> claim() {
        return tx.execute(s -> {
            Instant now = Instant.now();
            db.update("UPDATE memory_extraction_task SET status='FAILED',last_error_code='attempts_exhausted',lease_token=NULL,lease_until=NULL WHERE attempts>=? AND (status='PENDING' OR (status='RUNNING' AND lease_until<?))", config.getMaxAttempts(), time(now));
            String claimSql = "document".equals(config.getStorageMode()) ? """
                SELECT task.* FROM memory_extraction_task task
                JOIN chat_turn turn_row ON turn_row.id=task.turn_id
                WHERE task.attempts<? AND ((task.status='PENDING' AND task.next_attempt_at<=?)
                    OR (task.status='RUNNING' AND task.lease_until<?))
                AND NOT EXISTS (
                    SELECT 1 FROM memory_extraction_task earlier_task
                    JOIN chat_turn earlier_turn ON earlier_turn.id=earlier_task.turn_id
                    WHERE earlier_turn.user_id=turn_row.user_id
                    AND earlier_turn.user_turn_seq<turn_row.user_turn_seq
                    AND earlier_task.status IN ('PENDING','RUNNING'))
                ORDER BY task.next_attempt_at,turn_row.user_turn_seq LIMIT 1 FOR UPDATE SKIP LOCKED
                """ : "SELECT * FROM memory_extraction_task WHERE attempts<? AND ((status='PENDING' AND next_attempt_at<=?) OR (status='RUNNING' AND lease_until<?)) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED";
            var rows = db.queryForList(claimSql, config.getMaxAttempts(), time(now), time(now));
            if (rows.isEmpty()) return Optional.empty();
            var r = rows.get(0); long id = ((Number) r.get("id")).longValue(); String token = UUID.randomUUID().toString();
            db.update("UPDATE memory_extraction_task SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=? WHERE id=?",
                    token, time(now.plus(config.getTaskLease())), time(now), id);
            return Optional.of(new Task(id, ((Number) r.get("turn_id")).longValue(), token, ((Number) r.get("attempts")).intValue() + 1));
        });
    }
    public void taskFailed(Task t, String code) {
        db.update("UPDATE memory_extraction_task SET status=?,next_attempt_at=?,last_error_code=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                t.attempts() >= config.getMaxAttempts() ? "FAILED" : "PENDING",
                time(Instant.now().plusSeconds(Math.min(3600, 5L << Math.min(t.attempts(), 9)))), code, time(Instant.now()), t.id(), t.token(), time(Instant.now()));
    }
    public void apply(Task task, List<Change> changes) {
        Turn source = turn(task.turnId());
        tx.executeWithoutResult(s -> {
            lockUser(source.userId());
            var lease = db.queryForMap("SELECT * FROM memory_extraction_task WHERE id=? FOR UPDATE", task.id());
            if (!"RUNNING".equals(lease.get("status")) || !task.token().equals(lease.get("lease_token"))
                    || !instant(lease.get("lease_until")).isAfter(Instant.now())) return;
            var row = db.queryForMap("SELECT * FROM chat_turn WHERE id=?", source.id());
            if (row.get("question") == null || !instant(row.get("content_expires_at")).isAfter(Instant.now())) {
                db.update("UPDATE memory_extraction_task SET status='EXPIRED',last_error_code='source_expired' WHERE id=?", task.id()); return;
            }
            for (Change change : changes.stream().sorted(Comparator.comparing(Change::key)).toList()) write(source, change);
            db.update("UPDATE memory_extraction_task SET status='DONE',updated_at=?,lease_token=NULL,lease_until=NULL WHERE id=?", time(Instant.now()), task.id());
        });
    }
    private void write(Turn source, Change c) {
        var slots = db.queryForList("SELECT * FROM memory_slot WHERE user_id=? AND memory_key=?", source.userId(), c.key());
        long slot;
        if (slots.isEmpty()) {
            slot = insert("INSERT INTO memory_slot(user_id,memory_key,revision,last_applied_turn_seq) VALUES (?,?,0,0)", source.userId(), c.key());
        } else slot = ((Number) slots.get(0).get("id")).longValue();
        var state = db.queryForMap("SELECT * FROM memory_slot WHERE id=? FOR UPDATE", slot);
        if (((Number) state.get("last_applied_turn_seq")).longValue() >= source.id()) return;
        Instant now = Instant.now();
        Long oldId = state.get("current_version_id") == null ? null : ((Number) state.get("current_version_id")).longValue();
        String value;
        try { value = json.writeValueAsString(Map.of("value", c.value())); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
        if (oldId != null && !"DELETE".equals(c.operation())) {
            var old = db.queryForMap("SELECT * FROM memory_version WHERE id=? AND slot_id=?", oldId, slot);
            try {
                boolean same = json.readTree((String) old.get("value_json")).path("value").asText().equals(c.value());
                Instant expiry = old.get("expires_at") == null ? null : instant(old.get("expires_at"));
                if (same && Objects.equals(expiry, c.expiresAt()) && "ACTIVE".equals(old.get("status"))) {
                    db.update("UPDATE memory_slot SET last_applied_turn_seq=? WHERE id=?", source.id(), slot); return;
                }
            } catch (JsonProcessingException e) { throw new IllegalStateException("invalid stored memory", e); }
        }
        if (oldId != null) db.update("UPDATE memory_version SET status='SUPERSEDED',invalidated_at=? WHERE id=? AND status='ACTIVE'", time(now), oldId);
        long revision = ((Number) state.get("revision")).longValue() + 1;
        boolean deleted="DELETE".equals(c.operation());
        long audit = insert("INSERT INTO memory_version(slot_id,version,category,attribute,operation,value_json,status,expires_at,source_turn_id,evidence,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                slot, revision, c.category(), c.attribute(), c.operation(), value, deleted ? "DELETED" : "ACTIVE", time(c.expiresAt()), source.id(), c.evidence(), time(now));
        Long next = deleted ? null : audit;
        db.update("UPDATE memory_slot SET current_version_id=?,revision=?,last_applied_turn_seq=? WHERE id=?", next, revision, source.id(), slot);
    }
    public void maintain() {
        for (var r : db.queryForList("SELECT * FROM chat_turn WHERE status='RUNNING' AND created_at<? LIMIT 50", time(Instant.now().minus(config.getRunTimeout())))) cancel(turn(r), "FAILED");
        db.update("UPDATE chat_turn SET question=NULL,answer=NULL,context_sync_status='EXPIRED' WHERE content_expires_at<? AND question IS NOT NULL AND status<>'RUNNING'", time(Instant.now()));
        if (!"document".equals(config.getStorageMode())) {
        db.update("UPDATE memory_version SET status='EXPIRED',invalidated_at=? WHERE status='ACTIVE' AND expires_at<?", time(Instant.now()), time(Instant.now()));
        db.update("UPDATE memory_version SET evidence=NULL WHERE evidence IS NOT NULL AND created_at<?",time(Instant.now().minus(config.getAuditEvidenceRetention())));
        }
    }
}
