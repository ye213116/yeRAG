package com.yizhaoqi.smartpai.memory;

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
    public record LegacyTurn(String question, String answer, Instant createdAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MemoryProperties config;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private MemoryIndexService indexPolicy;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureIndexPolicy(MemoryIndexService policy) { this.indexPolicy=policy; }

    public MemoryStore(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager,
                       MemoryProperties config) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.config = config;
        this.indexPolicy=new MemoryIndexService(config,new com.yizhaoqi.smartpai.rag.RagProperties(),json);
    }
    public void verifySchema() {
        db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            for (String[] column : List.of(new String[]{"chat_session","context_expires_at"},new String[]{"chat_turn","content_expires_at"})) {
                boolean found=false;
                for (boolean upper : List.of(false,true)) {
                    try (var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,
                            upper ? column[0].toUpperCase(Locale.ROOT) : column[0],upper ? column[1].toUpperCase(Locale.ROOT) : column[1])) {
                        if (columns.next()) {
                            found=true;
                            if (columns.getInt("NULLABLE")!=java.sql.DatabaseMetaData.columnNullable)
                                throw new IllegalStateException("Apply 20261102_persistent_conversation_history.sql before starting");
                            break;
                        }
                    }
                }
                if (!found) throw new IllegalStateException("Missing memory schema column "+column[0]+"."+column[1]);
            }
            return null;
        });
        db.queryForList("SELECT active_turn_id,context_expires_at,context_summary,summary_covered_turn_seq,summary_revision FROM chat_session WHERE 1=0");
        db.queryForList("SELECT user_turn_seq,request_hash,context_sync_status FROM chat_turn WHERE 1=0");
        db.queryForList("SELECT lease_token,lease_until,failure_stage FROM memory_extraction_task WHERE 1=0");
        db.queryForList("SELECT user_id,memory_type,content,version,created_at,updated_at FROM memory_document WHERE 1=0");
        db.queryForList("SELECT id,document_id,heading,description,section_version,last_applied_turn_seq,deleted,created_at,updated_at FROM memory_document_section WHERE 1=0");
        db.queryForList("SELECT document_id,version,metadata_json,patches_json,trigger_turn_id FROM memory_document_version WHERE 1=0");
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("invalid audit data",invalid); }
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
        var rows = db.queryForList("SELECT * FROM chat_session WHERE id=? AND user_id=? AND status<>'DELETED'", id, user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        return rows.get(0);
    }
    public List<Map<String, Object>> sessions(long user) {
        return db.queryForList("SELECT id,status,last_activity_at FROM chat_session WHERE user_id=? AND status<>'DELETED' ORDER BY last_activity_at DESC,id DESC LIMIT 100", user);
    }
    public String current(long user, boolean forceNew) {
        return tx.execute(s -> { lockUser(user); return currentLocked(user, forceNew, Instant.now()); });
    }
    private String currentLocked(long user, boolean forceNew, Instant now) {
        var rows = db.queryForList("SELECT * FROM chat_session WHERE user_id=? AND status='OPEN' ORDER BY created_at DESC", user);
        if (!rows.isEmpty()) {
            var row = rows.get(0);
            if (!forceNew) return (String) row.get("id");
            for (var old : rows) {
                db.update("UPDATE chat_turn SET status='CANCELLED' WHERE session_id=? AND status='RUNNING'", old.get("id"));
                db.update("UPDATE chat_session SET status='CLOSED',closed_at=?,active_turn_id=NULL,revision=revision+1 WHERE id=?", time(now), old.get("id"));
            }
        }
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO chat_session(id,user_id,status,last_activity_at,context_expires_at,created_at,revision) VALUES (?,?,'OPEN',?,?,?,0)",
                id, user, time(now), null, time(now));
        return id;
    }
    /** Explicit selection resumes an existing conversation, preserving its history and summary. */
    public String resume(long user, String id) {
        return tx.execute(s -> {
            lockUser(user);
            session(user,id);
            Instant now = Instant.now();
            db.update("UPDATE chat_turn SET status='CANCELLED' WHERE user_id=? AND session_id<>? AND status='RUNNING'",user,id);
            db.update("UPDATE chat_session SET status='CLOSED',closed_at=?,active_turn_id=NULL,revision=revision+1 WHERE user_id=? AND id<>? AND status='OPEN'",time(now),user,id);
            db.update("UPDATE chat_session SET status='OPEN',closed_at=NULL,last_activity_at=?,revision=revision+1 WHERE id=? AND user_id=?",time(now),id,user);
            return id;
        });
    }
    /** Keep only an identity tombstone so legacy imports and late callbacks cannot resurrect a deleted UUID. */
    public void deleteSession(long user, String id) {
        tx.executeWithoutResult(s -> {
            lockUser(user);
            session(user,id);
            db.update("DELETE FROM memory_extraction_task WHERE turn_id IN (SELECT id FROM chat_turn WHERE session_id=? AND user_id=?)",id,user);
            db.update("DELETE FROM chat_turn WHERE session_id=? AND user_id=?",id,user);
            db.update("UPDATE chat_session SET status='DELETED',active_turn_id=NULL,context_summary=NULL,summary_covered_turn_seq=0,summary_revision=summary_revision+1,revision=revision+1,closed_at=? WHERE id=? AND user_id=?",time(Instant.now()),id,user);
        });
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
                if ("COMPLETED".equals(row.get("status")) && row.get("answer")==null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"请求结果已过期");
                return new Begin(turn(row), false, false);
            }
            if (requestedSession != null) {
                var selected = session(user, requestedSession);
                if (!"OPEN".equals(selected.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换到当前会话");
            }
            Instant now = Instant.now();
            String id = requestedSession == null ? currentLocked(user, false, now) : requestedSession;
            var row = session(user, id);
            if (row.get("active_turn_id") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话有正在执行的问答，请先停止");
            long turn = insert("INSERT INTO chat_turn(session_id,user_id,request_id,request_hash,question,status,created_at,content_expires_at) VALUES (?,?,?,?,?,'RUNNING',?,?)",
                    id, user, requestId, hash(question), question, time(now), null);
            db.update("UPDATE chat_turn SET user_turn_seq=? WHERE id=?", turn, turn);
            db.update("UPDATE chat_session SET active_turn_id=?,last_activity_at=?,revision=revision+1 WHERE id=?",
                    turn, time(now), id);
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
            db.update("UPDATE chat_turn SET answer=?,status='COMPLETED',completed_at=?,content_expires_at=?,context_sync_status='PENDING',context_next_attempt_at=? WHERE id=?",
                    answer, time(now), null, time(now), t.id());
            db.update("UPDATE chat_session SET active_turn_id=NULL,context_expires_at=?,revision=revision+1 WHERE id=? AND active_turn_id=?",
                    null, t.sessionId(), t.id());
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
        return historyBefore(user,session,Long.MAX_VALUE);
    }
    /** Loads the preceding 200 eligible turns by ID and returns them chronologically. */
    public List<Turn> historyBefore(long user, String session, long before) {
        session(user, session);
        var rows = db.queryForList("SELECT * FROM chat_turn WHERE user_id=? AND session_id=? AND status='COMPLETED' AND question IS NOT NULL AND answer IS NOT NULL AND id<? ORDER BY id DESC LIMIT 200", user, session, before);
        List<Turn> turns = new ArrayList<>(); rows.forEach(r -> turns.add(turn(r))); Collections.reverse(turns); return turns;
    }
    /** Cross-session history for the administrator-only endpoint; raw history has no automatic expiration. */
    public List<Turn> completedHistory(long user, Instant start, Instant endExclusive) {
        var rows = db.queryForList("""
            SELECT * FROM chat_turn WHERE user_id=? AND status='COMPLETED'
              AND created_at>=? AND created_at<? AND question IS NOT NULL AND answer IS NOT NULL
            ORDER BY created_at DESC,id DESC LIMIT 200
            """, user, time(start), time(endExclusive));
        List<Turn> turns = new ArrayList<>();
        rows.forEach(row -> turns.add(turn(row)));
        Collections.reverse(turns);
        return turns;
    }
    public List<Turn> uncoveredHistory(long user, String session, long covered) {
        session(user,session);
        return db.queryForList("""
            SELECT * FROM chat_turn WHERE user_id=? AND session_id=? AND status='COMPLETED'
              AND question IS NOT NULL AND answer IS NOT NULL AND id>? ORDER BY id LIMIT 200
            """,user,session,covered).stream().map(this::turn).toList();
    }
    public List<Turn> searchHistory(long user, String session, String query, long before, int limit) {
        session(user,session);
        String pattern = "%" + query.replace("!","!!").replace("%","!%").replace("_","!_") + "%";
        return db.queryForList("""
            SELECT t.* FROM chat_turn t JOIN chat_session s ON s.id=t.session_id AND s.user_id=t.user_id
            WHERE t.user_id=? AND t.session_id=? AND s.status<>'DELETED' AND t.status='COMPLETED'
              AND t.question IS NOT NULL AND t.answer IS NOT NULL AND t.id<?
              AND (t.question LIKE ? ESCAPE '!' OR t.answer LIKE ? ESCAPE '!')
            ORDER BY t.id DESC LIMIT ?
            """,user,session,before,pattern,pattern,Math.min(20,Math.max(1,limit))).stream().map(this::turn).toList();
    }
    public List<Turn> readTurns(long user, String session, long turnId, int neighbors) {
        session(user,session);
        var anchor = db.queryForList("SELECT id FROM chat_turn WHERE id=? AND user_id=? AND session_id=? AND status='COMPLETED'",turnId,user,session);
        if (anchor.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"回合不存在");
        int count = Math.min(2,Math.max(0,neighbors));
        List<Turn> result = new ArrayList<>();
        result.addAll(db.queryForList("SELECT * FROM chat_turn WHERE user_id=? AND session_id=? AND status='COMPLETED' AND question IS NOT NULL AND answer IS NOT NULL AND id<? ORDER BY id DESC LIMIT ?",user,session,turnId,count).stream().map(this::turn).toList());
        result.addAll(db.queryForList("SELECT * FROM chat_turn WHERE user_id=? AND session_id=? AND status='COMPLETED' AND question IS NOT NULL AND answer IS NOT NULL AND id>=? ORDER BY id LIMIT ?",user,session,turnId,count+1).stream().map(this::turn).toList());
        result.sort(Comparator.comparingLong(Turn::id));
        session(user,session);
        return List.copyOf(result);
    }
    public boolean hasContextGap(long user, String session, long covered, long before) {
        return db.queryForObject("""
            SELECT COUNT(*) FROM chat_turn WHERE user_id=? AND session_id=? AND status='COMPLETED'
              AND question IS NOT NULL AND answer IS NOT NULL AND id>? AND id<?
            """,Long.class,user,session,covered,before)>0;
    }
    public boolean saveSummary(long user, String session, long expectedRevision, String summary, long covered) {
        if (summary == null || summary.isBlank() || MemoryContextService.bytes(summary) > config.getSummaryBytes() || covered <= 0)
            throw new IllegalArgumentException("invalid summary");
        return db.update("""
            UPDATE chat_session SET context_summary=?,summary_covered_turn_seq=?,summary_revision=summary_revision+1
            WHERE id=? AND user_id=? AND summary_revision=? AND summary_covered_turn_seq<? AND status<>'DELETED'
            """, summary, covered, session, user, expectedRevision, covered) == 1;
    }
    public boolean importSummary(long user, String session, String summary, long covered) {
        if (summary == null || summary.isBlank() || MemoryContextService.bytes(summary) > config.getSummaryBytes() || covered <= 0)
            return false;
        return db.update("""
            UPDATE chat_session SET context_summary=?,summary_covered_turn_seq=?,summary_revision=summary_revision+1
            WHERE id=? AND user_id=? AND summary_revision=0 AND summary_covered_turn_seq=0
              AND status<>'DELETED' AND EXISTS (
                SELECT 1 FROM chat_turn WHERE id=? AND session_id=? AND user_id=? AND status='COMPLETED')
            """, summary, covered, session, user, covered, session, user) == 1;
    }
    public boolean importLegacySession(long user, String id, List<LegacyTurn> turns, Instant expiresAt) {
        if (id == null || expiresAt == null || !expiresAt.isAfter(Instant.now()) || turns == null || turns.size() > 10)
            return false;
        try { UUID.fromString(id); } catch (IllegalArgumentException invalid) { return false; }
        return Boolean.TRUE.equals(tx.execute(s -> {
            lockUser(user);
            if (!db.queryForList("SELECT id FROM chat_session WHERE id=?",id).isEmpty()) return false;
            Instant now = Instant.now();
            boolean open = db.queryForObject("SELECT COUNT(*) FROM chat_session WHERE user_id=? AND status='OPEN'", Long.class,user) == 0;
            Instant created = turns.isEmpty() ? now : turns.get(0).createdAt();
            Instant activity = turns.isEmpty() ? now : turns.get(turns.size()-1).createdAt();
            if (activity.isAfter(now)) activity = now;
            db.update("""
                INSERT INTO chat_session(id,user_id,status,last_activity_at,context_expires_at,created_at,closed_at,revision)
                VALUES (?,?,?,?,?,?,?,0)
                """,id,user,open ? "OPEN" : "CLOSED",time(activity),null,time(created),open ? null : time(now));
            for (int i=0; i<turns.size(); i++) {
                LegacyTurn turn = turns.get(i);
                String requestId = "legacy_" + hash(id + ":" + i).substring(0,32);
                long turnId = insert("""
                    INSERT INTO chat_turn(session_id,user_id,request_id,request_hash,question,answer,status,
                      context_sync_status,created_at,completed_at,content_expires_at)
                    VALUES (?,?,?,?,?,?,'COMPLETED','SYNCED',?,?,?)
                    """,id,user,requestId,hash(turn.question()),turn.question(),turn.answer(),
                        time(turn.createdAt()),time(turn.createdAt()),null);
                db.update("UPDATE chat_turn SET user_turn_seq=? WHERE id=?",turnId,turnId);
            }
            return true;
        }));
    }
    /** Metadata-only discovery; no body is fetched or parsed to build MEMORY.md. */
    public List<CatalogDocument> catalog(long user) {
        var rows=db.queryForList("""
            SELECT d.id AS doc_id,d.memory_type,d.version,
                   s.id AS section_id,s.heading,s.description,s.section_version,s.last_applied_turn_seq,s.deleted
            FROM memory_document d LEFT JOIN memory_document_section s ON s.document_id=d.id
            WHERE d.user_id=? ORDER BY d.memory_type,s.id
            """,user);
        Map<Long,CatalogDocument> result=new LinkedHashMap<>();
        for(var row:rows) {
            long id=((Number)row.get("doc_id")).longValue();
            var doc=result.computeIfAbsent(id,key -> new CatalogDocument(id,MemoryType.valueOf((String)row.get("memory_type")),
                    ((Number)row.get("version")).longValue(),new ArrayList<>()));
            if(row.get("section_id")!=null) doc.sections().add(metadata(row,"section_id",id));
        }
        return result.values().stream().map(d -> new CatalogDocument(d.id(),d.type(),d.version(),List.copyOf(d.sections()))).toList();
    }
    public List<MemoryDocument> documents(long user) {
        return catalog(user).stream().map(d -> document(user,d.type())).toList();
    }
    /** One joined read keeps a body and its metadata/version on the same SQL snapshot. */
    public MemoryDocument document(long user,MemoryType type) {
        var rows=db.queryForList("""
            SELECT d.id AS doc_id,d.user_id,d.memory_type,d.content,d.version,
                   s.id AS section_id,s.heading,s.description,s.section_version,s.last_applied_turn_seq,s.deleted
            FROM memory_document d LEFT JOIN memory_document_section s ON s.document_id=d.id
            WHERE d.user_id=? AND d.memory_type=? ORDER BY s.id
            """,user,type.name());
        if(rows.isEmpty()) return new MemoryDocument(0,user,type,"# "+type.title()+"\n",List.of(),0);
        var first=rows.get(0); long id=((Number)first.get("doc_id")).longValue();
        return new MemoryDocument(id,user,type,(String)first.get("content"),rows.stream().filter(r -> r.get("section_id")!=null)
                .map(r -> metadata(r,"section_id",id)).toList(),((Number)first.get("version")).longValue());
    }
    private SectionMetadata metadata(Map<String,Object> row,String idColumn,long documentId) {
        Object deleted=row.get("deleted");
        return new SectionMetadata(UUID.fromString((String)row.get(idColumn)),documentId,(String)row.get("heading"),
                (String)row.get("description"),((Number)row.get("section_version")).longValue(),
                ((Number)row.get("last_applied_turn_seq")).longValue(),Boolean.TRUE.equals(deleted) || deleted instanceof Number n && n.intValue()!=0);
    }
    public List<Turn> extractionContext(Turn current) {
        var rows=db.queryForList("""
            SELECT * FROM chat_turn WHERE user_id=? AND session_id=? AND id<=? AND status='COMPLETED'
              AND question IS NOT NULL AND answer IS NOT NULL ORDER BY id DESC LIMIT ?
            """,current.userId(),current.sessionId(),current.id(),config.getExtractionRecentTurns()+1);
        List<Turn> result=new ArrayList<>(rows.stream().map(this::turn).toList()); Collections.reverse(result);
        return List.copyOf(result);
    }
    public List<Map<String,Object>> versions(long user,MemoryType type,long before) {
        return db.queryForList("""
            SELECT v.version,v.change_summary,v.trigger_turn_id,v.created_at FROM memory_document_version v
            JOIN memory_document d ON d.id=v.document_id WHERE d.user_id=? AND d.memory_type=? AND v.version<?
            ORDER BY v.version DESC LIMIT 20
            """,user,type.name(),before);
    }
    public DocumentVersion version(long user,MemoryType type,long version) {
        var rows=db.queryForList("""
            SELECT v.* FROM memory_document_version v JOIN memory_document d ON d.id=v.document_id
            WHERE d.user_id=? AND d.memory_type=? AND v.version=?
            """,user,type.name(),version);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆版本不存在");
        var row=rows.get(0);
        return new DocumentVersion(((Number)row.get("version")).longValue(),(String)row.get("content"),(String)row.get("metadata_json"),
                (String)row.get("change_summary"),(String)row.get("patches_json"),row.get("trigger_turn_id")==null ? null : ((Number)row.get("trigger_turn_id")).longValue(),instant(row.get("created_at")));
    }
    public Map<String,Object> provenance(long user,MemoryType type,UUID section) {
        var doc=document(user,type);
        if(doc.sections().stream().noneMatch(s -> s.id().equals(section))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆条目不存在");
        var rows=db.queryForList("SELECT version,patches_json,trigger_turn_id FROM memory_document_version WHERE document_id=? ORDER BY version DESC LIMIT 100",doc.id());
        for(var row:rows) try {
            var patches=json.readTree((String)row.get("patches_json"));
            if(patches.isObject() && "ROLLBACK".equals(patches.path("decision").asText()))
                return Map.of("documentVersion",row.get("version"),"decision","ROLLBACK","reason",patches.path("reason").asText(),
                        "restoredFromVersion",patches.path("targetVersion").asLong(),"sources",List.of());
            if(patches.isArray()) for(var patch:patches) if(section.toString().equals(patch.path("sectionId").asText()))
                return Map.of("documentVersion",row.get("version"),"decision",patch.path("decision").asText(),"reason",patch.path("reason").asText(),
                        "sources",patch.path("sources"));
        } catch(Exception invalid) { throw new IllegalStateException("invalid stored memory audit",invalid); }
        return Map.of("status","PROVENANCE_NOT_IN_RECENT_VERSIONS","documentVersion",doc.version());
    }
    private MemoryDocument lockDocument(long user,MemoryType type) {
        Instant now=Instant.now();
        db.update("INSERT INTO memory_document(user_id,memory_type,content,created_at,updated_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                user,type.name(),"# "+type.title()+"\n",time(now),time(now));
        var row=db.queryForMap("SELECT * FROM memory_document WHERE user_id=? AND memory_type=? FOR UPDATE",user,type.name());
        long id=((Number)row.get("id")).longValue();
        var sections=db.queryForList("SELECT * FROM memory_document_section WHERE document_id=? ORDER BY id FOR UPDATE",id).stream().map(r -> metadata(r,"id",id)).toList();
        return new MemoryDocument(id,user,type,(String)row.get("content"),sections,((Number)row.get("version")).longValue());
    }
    private void snapshot(MemoryDocument document,long nextVersion,String content,List<SectionMetadata> sections,
                          String summary,Object patches,Long turnId) {
        db.update("INSERT INTO memory_document_version(document_id,version,content,metadata_json,change_summary,patches_json,trigger_turn_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
                document.id(),nextVersion,content,encode(sections),summary,encode(patches),turnId,time(Instant.now()));
    }
    /** Compatibility for internal callers: production Worker supplies the versions it actually read. */
    public void applyPatches(Task task,List<MemoryPatch> patches) {
        var sources=db.queryForList("SELECT * FROM chat_turn WHERE id=?",task.turnId());
        if(sources.isEmpty()) return;
        long user=((Number)sources.get(0).get("user_id")).longValue();
        Map<MemoryType,Long> expected=new EnumMap<>(MemoryType.class);
        catalog(user).forEach(d -> expected.put(d.type(),d.version()));
        applyPatches(task,patches,expected);
    }
    /** Model calls happen outside this transaction; lease, evidence, CAS, snapshots and DONE commit together. */
    public void applyPatches(Task task,List<MemoryPatch> patches,Map<MemoryType,Long> expectedVersions) {
        Objects.requireNonNull(patches,"patches");
        if(patches.size()>config.getMaxPatches()) throw new IllegalArgumentException("too many patches");
        tx.executeWithoutResult(status -> {
            var sources=db.queryForList("SELECT * FROM chat_turn WHERE id=?",task.turnId());
            if(sources.isEmpty()) return;
            long user=((Number)sources.get(0).get("user_id")).longValue();
            lockUser(user);
            var leases=db.queryForList("SELECT * FROM memory_extraction_task WHERE id=? FOR UPDATE",task.id());
            if(leases.isEmpty()) return;
            var lease=leases.get(0);
            if(!"RUNNING".equals(lease.get("status")) || !task.token().equals(lease.get("lease_token"))
                    || !instant(lease.get("lease_until")).isAfter(Instant.now())) return;
            if(((Number)lease.get("turn_id")).longValue()!=task.turnId()) throw new IllegalArgumentException("task source mismatch");
            Turn source=turn(db.queryForMap("SELECT * FROM chat_turn WHERE id=? FOR UPDATE",task.turnId()));
            session(user,source.sessionId());
            if(!"COMPLETED".equals(source.status()) || source.question()==null) throw new IllegalArgumentException("source not available");
            long seq=((Number)db.queryForMap("SELECT user_turn_seq FROM chat_turn WHERE id=?",source.id()).get("user_turn_seq")).longValue();
            Map<MemoryType,MemoryDocument> locked=new EnumMap<>(MemoryType.class);
            for(var type:patches.stream().map(MemoryPatch::target).filter(Objects::nonNull).distinct().sorted().toList()) locked.put(type,lockDocument(user,type));
            List<MemoryPatch> applicable=new ArrayList<>();
            for(var patch:patches) {
                if(patch==null || patch.target()==null) throw new IllegalArgumentException("invalid patch");
                var doc=locked.get(patch.target());
                var old=doc.sections().stream().filter(s -> s.id().equals(patch.sectionId())).findFirst().orElse(null);
                if(old!=null && seq<=old.lastAppliedTurnSeq()) continue;
                if(patch.sectionId()==null && doc.sections().stream().anyMatch(s -> s.deleted() && s.heading().equals(patch.heading()) && seq<=s.lastAppliedTurnSeq())) continue;
                applicable.add(patch);
            }
            for(var type:applicable.stream().map(MemoryPatch::target).distinct().toList())
                if(locked.get(type).version()!=expectedVersions.getOrDefault(type,0L)) throw new MemoryVersionConflict();
            new MemoryPolicy(config).validatePatches(applicable,extractionContext(source),source,List.copyOf(locked.values()));
            var editor=new MemoryDocumentEditor();
            for(var doc:locked.values()) {
                Map<UUID,SectionMetadata> catalog=new LinkedHashMap<>(); doc.sections().forEach(s -> catalog.put(s.id(),s));
                List<ResolvedMemoryPatch> edits=new ArrayList<>(); List<MemoryPatch> audit=new ArrayList<>();
                for(var patch:applicable) {
                    if(patch.target()!=doc.type()) continue;
                    UUID id=patch.sectionId()==null ? UUID.randomUUID() : patch.sectionId();
                    var old=catalog.get(id);
                    edits.add(new ResolvedMemoryPatch(id,patch.operation()==MemoryPatchOperation.DELETE_SECTION ? old.heading() : patch.heading().strip(),patch.operation(),patch.content()));
                    audit.add(new MemoryPatch(patch.target(),id,patch.heading(),patch.operation(),patch.content(),patch.description(),patch.decision(),patch.reason(),patch.expectedSectionVersion(),patch.sources()));
                }
                if(edits.isEmpty()) continue;
                String content=editor.apply(doc.content(),edits,catalog).content();
                if(MemoryContextService.bytes(content)>config.getMaxDocumentBytes()) throw new IllegalArgumentException("document too large");
                List<SectionMetadata> next=new ArrayList<>(doc.sections());
                for(var patch:audit) {
                    var old=catalog.get(patch.sectionId());
                    boolean deleted=patch.operation()==MemoryPatchOperation.DELETE_SECTION;
                    String heading=deleted ? old.heading():patch.heading().strip();
                    String description=deleted ? old.description():patch.description().strip();
                    long version=old==null ? 1 : old.sectionVersion()+1;
                    next.removeIf(s -> s.id().equals(patch.sectionId()));
                    next.add(new SectionMetadata(patch.sectionId(),doc.id(),heading,description,version,seq,deleted));
                    if(old==null) db.update("INSERT INTO memory_document_section(id,document_id,heading,description,section_version,last_applied_turn_seq,deleted,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                            patch.sectionId().toString(),doc.id(),heading,description,version,seq,deleted,time(Instant.now()),time(Instant.now()));
                    else db.update("UPDATE memory_document_section SET heading=?,description=?,section_version=?,last_applied_turn_seq=?,deleted=?,updated_at=? WHERE id=? AND document_id=?",
                            heading,description,version,seq,deleted,time(Instant.now()),patch.sectionId().toString(),doc.id());
                }
                var all=new ArrayList<>(catalog(user)); all.removeIf(d -> d.id()==doc.id());
                all.add(new CatalogDocument(doc.id(),doc.type(),doc.version()+1,next)); indexPolicy.validateCatalog(all);
                db.update("UPDATE memory_document SET content=?,version=version+1,updated_at=? WHERE id=?",content,time(Instant.now()),doc.id());
                snapshot(doc,doc.version()+1,content,next,audit.stream().map(p -> p.decision()+": "+p.reason()).collect(java.util.stream.Collectors.joining("; ")),audit,source.id());
            }
            db.update("UPDATE memory_extraction_task SET status='DONE',failure_stage=NULL,last_error_code=NULL,updated_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",time(Instant.now()),task.id());
        });
    }
    public long rollback(long user,MemoryType type,long targetVersion,long expectedVersion,String reason) {
        if(reason==null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("rollback reason is required");
        return tx.execute(status -> {
            lockUser(user);
            var doc=lockDocument(user,type);
            if(doc.version()!=expectedVersion) throw new MemoryVersionConflict();
            var target=version(user,type,targetVersion);
            List<SectionMetadata> historical;
            try { historical=json.readValue(target.metadata(),new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
            catch(Exception invalid) { throw new IllegalStateException("invalid stored version",invalid); }
            long barrier=db.queryForObject("SELECT COALESCE(MAX(user_turn_seq),0) FROM chat_turn WHERE user_id=?",Long.class,user);
            Map<UUID,SectionMetadata> desired=new LinkedHashMap<>(); historical.forEach(s -> desired.put(s.id(),s));
            Map<UUID,SectionMetadata> current=new LinkedHashMap<>(); doc.sections().forEach(s -> current.put(s.id(),s));
            Set<UUID> identities=new TreeSet<>(); identities.addAll(current.keySet()); identities.addAll(desired.keySet());
            List<SectionMetadata> next=new ArrayList<>();
            for(UUID id:identities) {
                var old=current.get(id); var restore=desired.get(id); var basis=restore==null ? old:restore;
                long version=Math.max(old==null ? 0:old.sectionVersion(),basis.sectionVersion())+1;
                long seq=Math.max(barrier,old==null ? 0:old.lastAppliedTurnSeq());
                var item=new SectionMetadata(id,doc.id(),basis.heading(),basis.description(),version,seq,restore==null || restore.deleted()); next.add(item);
                if(old==null) db.update("INSERT INTO memory_document_section(id,document_id,heading,description,section_version,last_applied_turn_seq,deleted,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        id.toString(),doc.id(),item.heading(),item.description(),version,seq,item.deleted(),time(Instant.now()),time(Instant.now()));
                else db.update("UPDATE memory_document_section SET heading=?,description=?,section_version=?,last_applied_turn_seq=?,deleted=?,updated_at=? WHERE id=? AND document_id=?",
                        item.heading(),item.description(),version,seq,item.deleted(),time(Instant.now()),id.toString(),doc.id());
            }
            if(MemoryContextService.bytes(target.content())>config.getMaxDocumentBytes()) throw new IllegalArgumentException("restored document exceeds budget");
            var all=new ArrayList<>(catalog(user)); all.removeIf(d -> d.id()==doc.id()); all.add(new CatalogDocument(doc.id(),doc.type(),doc.version()+1,next)); indexPolicy.validateCatalog(all);
            db.update("UPDATE memory_document SET content=?,version=version+1,updated_at=? WHERE id=?",target.content(),time(Instant.now()),doc.id());
            snapshot(doc,doc.version()+1,target.content(),next,"ROLLBACK: "+reason,Map.of("decision","ROLLBACK","reason",reason,"fromVersion",doc.version(),"targetVersion",targetVersion),null);
            return doc.version()+1;
        });
    }

    public List<Turn> pendingSync() {
        return db.queryForList("SELECT * FROM chat_turn WHERE context_sync_status='PENDING' AND context_next_attempt_at<=? ORDER BY id LIMIT 20", time(Instant.now())).stream().map(this::turn).toList();
    }
    public void synced(String session, long through) {
        db.update("UPDATE chat_turn SET context_sync_status='SYNCED' WHERE session_id=? AND id<=? AND status='COMPLETED' AND context_sync_status='PENDING'", session, through);
    }
    public void syncedTurns(String session, List<Long> ids) {
        if (ids.isEmpty()) return;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        List<Object> args=new ArrayList<>(); args.add(session); args.addAll(ids);
        db.update("UPDATE chat_turn SET context_sync_status='SYNCED' WHERE session_id=? AND status='COMPLETED' AND context_sync_status='PENDING' AND id IN ("+placeholders+")",args.toArray());
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
            String claimSql = """
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
                """;
            var rows = db.queryForList(claimSql, config.getMaxAttempts(), time(now), time(now));
            if (rows.isEmpty()) return Optional.empty();
            var r = rows.get(0); long id = ((Number) r.get("id")).longValue(); String token = UUID.randomUUID().toString();
            db.update("UPDATE memory_extraction_task SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=? WHERE id=?",
                    token, time(now.plus(config.getTaskLease())), time(now), id);
            return Optional.of(new Task(id, ((Number) r.get("turn_id")).longValue(), token, ((Number) r.get("attempts")).intValue() + 1));
        });
    }
    public boolean renewLease(Task task) {
        Instant now=Instant.now();
        return db.update("UPDATE memory_extraction_task SET lease_until=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                time(now.plus(config.getTaskLease())),time(now),task.id(),task.token(),time(now))==1;
    }
    public void taskFailed(Task t,String code) { taskFailed(t,"unknown",code); }
    public void taskFailed(Task t,String stage,String code) {
        db.update("UPDATE memory_extraction_task SET status=?,next_attempt_at=?,last_error_code=?,failure_stage=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                t.attempts() >= config.getMaxAttempts() ? "FAILED" : "PENDING",
                time(Instant.now().plusSeconds(Math.min(3600, 5L << Math.min(t.attempts(), 9)))), code, stage, time(Instant.now()), t.id(), t.token(), time(Instant.now()));
    }
    public void maintain() {
        for (var r : db.queryForList("SELECT * FROM chat_turn WHERE status='RUNNING' AND created_at<? LIMIT 50", time(Instant.now().minus(config.getRunTimeout())))) cancel(turn(r), "FAILED");
    }
}
