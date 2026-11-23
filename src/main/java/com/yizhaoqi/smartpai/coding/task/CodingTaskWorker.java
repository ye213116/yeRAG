package com.yizhaoqi.smartpai.coding.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.coding.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.sandbox.SandboxExecutor;
import com.yizhaoqi.smartpai.coding.tool.CodingTools.CodingToolExecutor;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.trace.QuestionTelemetry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class CodingTaskWorker {
    private final CodingTaskStore store;
    private final CodingTaskService tasks;
    private final CodingProperties properties;
    private final AiProperties ai;
    private final KnowledgeAgentService agent;
    private final SandboxExecutor sandbox;
    private final CodingToolExecutor tools;
    private final QuestionTelemetry telemetry;
    private final ObjectMapper json;
    private final com.yizhaoqi.smartpai.coding.context.CodingContextService projectContext;
    private final ExecutorService pool;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    public CodingTaskWorker(CodingTaskStore store,CodingTaskService tasks,CodingProperties properties,AiProperties ai,
                             KnowledgeAgentService agent,SandboxExecutor sandbox,CodingToolExecutor tools,QuestionTelemetry telemetry,ObjectMapper json,
                             com.yizhaoqi.smartpai.coding.context.CodingContextService projectContext) {
        this.store=store;this.tasks=tasks;this.properties=properties;this.ai=ai;this.agent=agent;this.sandbox=sandbox;this.tools=tools;this.telemetry=telemetry;this.json=json;
        this.projectContext=projectContext;
        if(properties.getConcurrency()<1 || properties.getConcurrency()>4) throw new IllegalArgumentException("Coding concurrency must be 1..4");
        pool=Executors.newFixedThreadPool(properties.getConcurrency(),r -> { var t=new Thread(r,"yerag-coding-task");t.setDaemon(true);return t; });
    }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        if(closing) return;
        try {
            for(String id:active) store.heartbeat(id);
            store.recoverPublishing();
            for(Task pending:store.cleanupPending()) { sandbox.destroy(pending.workspaceId());store.cleanupCompleted(pending.id()); }
            for(String id:store.interruptStale()) {
                Task task=store.internal(id);Workspace workspace=store.workspace(task.ownerId(),task.workspaceId());
                sandbox.stop(workspace.id());JsonNode recovered=null;
                try { recovered=sandbox.execute(workspace,Operation.SNAPSHOT,Map.of(),Duration.ofSeconds(30),() -> {}); } catch(Exception unavailable) { /* A preparation crash may have no snapshot. */ }
                if(recovered instanceof ObjectNode object) object.put("cleanupPending",true);
                else recovered=json.createObjectNode().put("cleanupPending",true);
                store.finish(id,Status.INTERRUPTED,"执行进程中断，未自动续跑",recovered);
                sandbox.destroy(workspace.id());store.cleanupCompleted(id);
            }
            while(active.size()<properties.getConcurrency()) {
                var task=store.claim();if(task.isEmpty()) break;
                active.add(task.get().id());
                pool.execute(() -> {try { run(task.get()); } finally { active.remove(task.get().id()); }});
            }
        } catch(Exception unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Coding worker unavailable: {}",unavailable.getClass().getSimpleName());
        }
    }
    public void run(Task task) {
        Workspace workspace=store.workspace(task.ownerId(),task.workspaceId());
        var execution=tasks.execution(task);
        var request=new AgentRunRequest(Long.toString(task.ownerId()),task.message(),task.history(),task.conversationId(),task.sourceMode(),AgentMode.CODING,execution);
        Duration timeout=ai.getAgent().getProfiles().getCoding().getTotalTimeout();
        java.util.concurrent.atomic.AtomicBoolean prepared=new java.util.concurrent.atomic.AtomicBoolean();
        Status status=Status.FAILED;String summary="任务未完成";ObjectNode result=json.createObjectNode();
        try {
            timeout=new AgentProfileResolver(ai.getAgent()).resolve(request).totalTimeout();
            AgentEventListener listener=new AgentEventListener() {
                public void onStatus(String stage,String message,Map<String,Object> details) { store.event(task.id(),stage,Map.of("message",message,"details",details)); }
                public void onChunk(String chunk) { /* Full final answer is saved by the task, never into a switched chat. */ }
            };
            AgentRunResult answer=telemetry.observe("id:"+task.ownerId(),task.message(),task.conversationId(),task.requestId(),timeout,
                    Map.of("agentMode","coding","taskId",task.id(),"workspaceId",workspace.id()),
                    () -> Mono.fromRunnable(() -> {
                        sandbox.prepare(workspace,properties.repository(workspace.repositoryId(),task.ownerId()),() -> execution.require(WorkspacePermission.READ));
                        prepared.set(true);store.workspaceState(workspace,"READY");
                        if(task.permissions().contains(WorkspacePermission.EXECUTE))
                            store.event(task.id(),"baseline",sandbox.execute(workspace,Operation.TEST,Map.of(),Duration.ofSeconds(120),() -> execution.require(WorkspacePermission.EXECUTE)));
                    }).subscribeOn(Schedulers.boundedElastic()).then(Mono.defer(() -> agent.run(
                            new AgentRunRequest(request.userId(),request.message(),projectContext.build(task,workspace,execution),
                                    request.conversationId(),request.sourceMode(),request.mode(),execution),listener))),id -> store.started(task.id(),id))
                    .block(timeout.plusSeconds(5));
            execution.require(WorkspacePermission.READ);
            if(answer==null) throw new IllegalStateException("Coding returned no result");
            summary=answer.answer();status=Status.REVIEW_READY;
        } catch(Exception failure) {
            status=store.cancelled(task.id()) || failure instanceof CodingTaskService.TaskCancelled?Status.CANCELLED:
                    (hasTimeout(failure)?Status.TIMED_OUT:Status.FAILED);
            summary=status==Status.CANCELLED?"任务已取消，已产生的改动供审核":status==Status.TIMED_OUT?"任务已超时，验证可能未完成":"任务执行失败，未确认完成";
            store.event(task.id(),"execution_ended",Map.of("status",status.name(),"errorType",failure.getClass().getSimpleName()));
        } finally {
            boolean stopped=false;
            try {
                sandbox.stop(workspace.id());
                stopped=true;
                JsonNode snapshot=prepared.get()?sandbox.execute(workspace,Operation.SNAPSHOT,Map.of(),Duration.ofSeconds(30),() -> {}):json.createObjectNode().put("ok",false);
                if(snapshot.isObject()) result=(ObjectNode)snapshot.deepCopy();
                if(status==Status.REVIEW_READY && !result.path("ok").asBoolean()) { status=Status.FAILED;summary+="；无法生成完整审核快照"; }
                JsonNode verification=tools.verification(task.id());
                if(verification!=null && verification.path("changeSetHash").asText().equals(result.path("changeSetHash").asText())) result.set("verification",verification);
                else result.set("verification",json.valueToTree(Map.of("status","NOT_VERIFIED")));
            } catch(Exception collectionFailed) { result.put("collectionFailed",true);if(!stopped) status=Status.INTERRUPTED;else if(status==Status.REVIEW_READY) status=Status.FAILED;summary+="；成果采集失败"; }
            result.put("baseSha",workspace.baseSha());
            result.put("cleanupPending",true);
            // Durable results precede volume deletion. On SQL failure the keeper preserves the files for stale-task recovery.
            try {
                store.finish(task.id(),status,summary,result);
                try { sandbox.destroy(workspace.id());store.cleanupCompleted(task.id()); }
                catch(Exception cleanupFailed) { store.event(task.id(),"cleanup_pending",Map.of("retry",true)); }
            } finally { tools.forget(task.id()); }
        }
    }
    private static boolean hasTimeout(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause())
            if(cause instanceof TimeoutException || cause instanceof java.util.concurrent.CancellationException) return true;
        return false;
    }
    @PreDestroy public void close() {
        closing=true;
        for(String id:active) try { sandbox.stop(store.internal(id).workspaceId()); } catch(Exception ignored) {}
        pool.shutdownNow();
    }
}
