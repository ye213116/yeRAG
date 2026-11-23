package com.yizhaoqi.smartpai.coding.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static com.yizhaoqi.smartpai.coding.CodingTypes.uuid;

@Repository
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class CodingTaskStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public CodingTaskStore(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager) {
        this.db=db; this.json=json; this.tx=new TransactionTemplate(manager);
    }
    public <T> T transaction(java.util.function.Supplier<T> work) { return tx.execute(s -> work.get()); }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception invalid) { throw new IllegalArgumentException("Invalid task data",invalid); }
    }
    private <T> T decode(String value,TypeReference<T> type) {
        try { return json.readValue(value,type); } catch(Exception invalid) { throw new IllegalStateException("Invalid stored task data",invalid); }
    }
    public Workspace workspace(long user,String id) {
        uuid(id);
        var rows=db.queryForList("SELECT * FROM coding_workspace WHERE id=? AND owner_id=?",id,user);
        if(rows.isEmpty()) throw missing();
        return workspace(rows.get(0));
    }
    private Workspace workspace(Map<String,Object> r) {
        return new Workspace((String)r.get("id"),((Number)r.get("owner_id")).longValue(),(String)r.get("repository_id"),
                (String)r.get("branch_name"),(String)r.get("base_sha"),decode((String)r.get("permissions_json"),new TypeReference<>(){}),
                (String)r.get("status"),(String)r.get("active_task_id"));
    }
    public List<Workspace> workspaces(long user) {
        return db.queryForList("SELECT * FROM coding_workspace WHERE owner_id=? ORDER BY created_at DESC LIMIT 100",user).stream().map(this::workspace).toList();
    }
    public Workspace createWorkspace(long user,String repository,String branch,String sha,Set<WorkspacePermission> permissions) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO coding_workspace(id,owner_id,repository_id,branch_name,base_sha,permissions_json,status,created_at) VALUES(?,?,?,?,?,?,'NEW',CURRENT_TIMESTAMP)",
                id,user,repository,branch,sha,encode(permissions));
        return workspace(user,id);
    }
    public void permissions(long user,String id,Set<WorkspacePermission> permissions) {
        workspace(user,id);
        db.update("UPDATE coding_workspace SET permissions_json=? WHERE id=? AND owner_id=?",encode(permissions),id,user);
    }
    public void workspaceState(Workspace w,String state) {
        db.update("UPDATE coding_workspace SET status=? WHERE id=? AND owner_id=?",state,w.id(),w.ownerId());
    }
    public Task replay(long user,String requestId,String fingerprint) {
        var rows=db.queryForList("SELECT * FROM coding_task WHERE owner_id=? AND request_id=?",user,requestId);
        if(rows.isEmpty()) return null;
        Task task=map(rows.get(0));
        if(!task.requestHash().equals(fingerprint)) throw conflict("请求标识已用于其他任务参数");
        return task;
    }
    public void enqueue(Task task) {
        int locked=db.update("UPDATE coding_workspace SET active_task_id=? WHERE id=? AND owner_id=? AND active_task_id IS NULL AND status IN ('NEW','READY')",
                task.id(),task.workspaceId(),task.ownerId());
        if(locked!=1) throw conflict("Workspace 正在执行任务、等待审核或不可用，请新建 Workspace");
        db.update("INSERT INTO coding_task(id,owner_id,workspace_id,conversation_id,request_id,request_hash,message,source_mode,status,permissions_json,history_json,publish_state,created_at,heartbeat_at) VALUES(?,?,?,?,?,?,?,?,'QUEUED',?,?,'NONE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                task.id(),task.ownerId(),task.workspaceId(),task.conversationId(),task.requestId(),task.requestHash(),task.message(),task.sourceMode().name(),encode(task.permissions()),encode(task.history()));
    }
    public Task task(long user,String id) {
        uuid(id);
        var rows=db.queryForList("SELECT * FROM coding_task WHERE id=? AND owner_id=?",id,user);
        if(rows.isEmpty()) throw missing();
        return map(rows.get(0));
    }
    public Task internal(String id) {
        var rows=db.queryForList("SELECT * FROM coding_task WHERE id=?",id);
        if(rows.isEmpty()) throw missing();
        return map(rows.get(0));
    }
    private Task map(Map<String,Object> r) {
        return new Task((String)r.get("id"),((Number)r.get("owner_id")).longValue(),(String)r.get("workspace_id"),
                (String)r.get("conversation_id"),(String)r.get("request_id"),(String)r.get("request_hash"),(String)r.get("message"),
                SourceMode.valueOf((String)r.get("source_mode")),Status.valueOf((String)r.get("status")),
                decode((String)r.get("permissions_json"),new TypeReference<>(){}),decode((String)r.get("history_json"),new TypeReference<>(){}),
                (String)r.get("summary"),r.get("result_json")==null?null:decode((String)r.get("result_json"),new TypeReference<>(){}),
                (String)r.get("run_id"),(String)r.get("publish_state"),(String)r.get("pr_url"));
    }
    public List<Map<String,Object>> list(long user) {
        return db.queryForList("SELECT id,workspace_id,conversation_id,status,summary,publish_state,pr_url,created_at FROM coding_task WHERE owner_id=? ORDER BY created_at DESC LIMIT 100",user);
    }
    public Optional<Task> claim() {
        return transaction(() -> {
            var rows=db.queryForList("SELECT * FROM coding_task WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1 FOR UPDATE");
            if(rows.isEmpty()) return Optional.empty();
            String id=(String)rows.get(0).get("id");
            db.update("UPDATE coding_task SET status='PREPARING',heartbeat_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'",id);
            return Optional.of(internal(id));
        });
    }
    public boolean running(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM coding_task WHERE id=? AND status IN ('PREPARING','RUNNING')",Long.class,id)==1;
    }
    public void started(String id,String runId) {
        db.update("UPDATE coding_task SET status='RUNNING',run_id=?,heartbeat_at=CURRENT_TIMESTAMP WHERE id=? AND status='PREPARING'",runId,id);
    }
    public void heartbeat(String id) {
        db.update("UPDATE coding_task SET heartbeat_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PREPARING','RUNNING')",id);
    }
    public void event(String id,String kind,Object data) {
        db.update("INSERT INTO coding_task_event(task_id,kind,data_json,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",id,kind,encode(data));
    }
    public List<Map<String,Object>> events(String id) {
        return db.queryForList("SELECT id,kind,LEFT(data_json,1000) AS preview,created_at FROM coding_task_event WHERE task_id=? ORDER BY id DESC LIMIT 100",id);
    }
    public Map<String,Object> eventDetails(String taskId,long eventId) {
        var rows=db.queryForList("SELECT id,kind,data_json,created_at FROM coding_task_event WHERE task_id=? AND id=?",taskId,eventId);
        if(rows.isEmpty()) throw missing();return rows.get(0);
    }
    public void recoverPublishing() {
        db.update("UPDATE coding_task SET publish_state='FAILED' WHERE publish_state='PUBLISHING' AND heartbeat_at<?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(300)));
    }
    public List<Task> cleanupPending() {
        return db.queryForList("SELECT * FROM coding_task WHERE result_json LIKE ?","%\"cleanupPending\":true%").stream().map(this::map).toList();
    }
    public void cleanupCompleted(String id) {
        Task task=internal(id);
        if(task.result() instanceof com.fasterxml.jackson.databind.node.ObjectNode result) {
            result.put("cleanupPending",false);db.update("UPDATE coding_task SET result_json=? WHERE id=?",encode(result),id);
        }
    }
    public void finish(String id,Status status,String summary,JsonNode result) {
        transaction(() -> {
            db.queryForList("SELECT id FROM coding_task WHERE id=? FOR UPDATE",id);
            Task task=internal(id);
            if(task.status()!=Status.PREPARING && task.status()!=Status.RUNNING) return null;
            db.update("UPDATE coding_task SET status=?,summary=?,result_json=?,heartbeat_at=CURRENT_TIMESTAMP WHERE id=?",status.name(),summary,result==null?null:encode(result),id);
            db.update("UPDATE coding_workspace SET active_task_id=NULL,status='FROZEN' WHERE id=? AND active_task_id=?",task.workspaceId(),id);
            return null;
        });
    }
    public void cancel(long user,String id) {
        transaction(() -> {
            db.queryForList("SELECT id FROM coding_task WHERE id=? AND owner_id=? FOR UPDATE",id,user);
            Task task=task(user,id);
            if(task.status()==Status.QUEUED) {
                db.update("UPDATE coding_task SET status='CANCELLED' WHERE id=? AND status='QUEUED'",id);
                db.update("UPDATE coding_workspace SET active_task_id=NULL WHERE id=? AND active_task_id=?",task.workspaceId(),id);
            } else db.update("UPDATE coding_task SET cancel_requested=TRUE WHERE id=? AND status IN ('PREPARING','RUNNING')",id);
            return null;
        });
    }
    public boolean cancelled(String id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT cancel_requested FROM coding_task WHERE id=?",Boolean.class,id));
    }
    public List<String> interruptStale() {
        var rows=db.queryForList("SELECT id FROM coding_task WHERE status IN ('PREPARING','RUNNING') AND heartbeat_at<?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));
        return rows.stream().map(r -> (String)r.get("id")).toList();
    }
    public void saveCleanupResult(String id,JsonNode result) {
        db.update("UPDATE coding_task SET result_json=? WHERE id=? AND result_json IS NULL",encode(result),id);
    }
    public boolean beginPublish(long owner,String id) {
        return db.update("UPDATE coding_task SET publish_state='PUBLISHING',heartbeat_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=? AND status='REVIEW_READY' AND publish_state IN ('NONE','FAILED')",id,owner)==1;
    }
    public void publication(String id,String state,String url) {
        db.update("UPDATE coding_task SET publish_state=?,pr_url=? WHERE id=?",state,url,id);
    }
    public static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"任务或工作区不存在或未授权"); }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
