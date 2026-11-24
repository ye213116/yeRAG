package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ChatRuntimeProperties;
import com.yizhaoqi.smartpai.memory.*;
import com.yizhaoqi.smartpai.memory.MemoryTypes.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.*;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runs belong to durable user/conversation/request identities; sockets are subscribers only. */
@Service
public class ChatHandler {
    private static final Logger logger=LoggerFactory.getLogger(ChatHandler.class);
    private final KnowledgeAgentService agentService;
    private final RagFallbackService fallbackService;
    private final AiProperties.Agent agentConfig;
    private final ObjectMapper objectMapper;
    private final MemoryStore memoryStore;
    private final MemoryContextService memoryContext;
    private final Map<String,Run> activeRuns=new ConcurrentHashMap<>();
    private final Map<String,Map<String,WebSocketSession>> subscribers=new ConcurrentHashMap<>();
    // Compatibility lookups are bounded; persisted answers already carry durable citation identities.
    private final Map<String,Map<Integer,String>> references=Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Map<Integer,String>> eldest) { return size()>256; }
    });
    private ChatRuntimeProperties limits=new ChatRuntimeProperties();
    private boolean closing;
    private com.yizhaoqi.smartpai.rag.trace.QuestionTelemetry telemetry;
    @org.springframework.beans.factory.annotation.Autowired public void configureRuntime(ChatRuntimeProperties value) { limits=value; }
    @org.springframework.beans.factory.annotation.Autowired public void configureTelemetry(com.yizhaoqi.smartpai.rag.trace.QuestionTelemetry value) { telemetry=value; }
    public ChatHandler(KnowledgeAgentService agent,RagFallbackService fallback,AiProperties properties,ObjectMapper json,MemoryStore store,MemoryContextService context) {
        agentService=agent;fallbackService=fallback;agentConfig=properties.getAgent();objectMapper=json;memoryStore=store;memoryContext=context;
    }
    private static final class Run {
        final String user,conversation,request,origin;
        final Disposable.Composite subscriptions=Disposables.composite();
        final StringBuilder text=new StringBuilder();
        Turn turn;
        long seq;
        int bytes;
        String state="RUNNING",message="正在思考",question,runId="";
        Object planTasks=List.of();
        Run(String user,String conversation,String request,String origin,String question) {
            this.user=user;this.conversation=conversation;this.request=request;this.origin=origin;this.question=question;
        }
        synchronized Map<String,Object> snapshot() {
            return Map.of("conversationId",conversation,"requestId",request,"turnId",turn==null?"":Long.toString(turn.id()),
                    "question",question,"text",text.toString(),"seq",seq,"state",state,"agentStatus",message,"planTasks",planTasks,"runId",runId);
        }
    }
    private String key(String user,String request) { return user+":"+request; }
    public void attachSession(String user,WebSocketSession session) {
        subscribers.compute(user,(id,sockets) -> {
            if(sockets==null) sockets=new ConcurrentHashMap<>();sockets.put(session.getId(),session);return sockets;
        });
    }
    public void detachSession(String user,String socket) {
        subscribers.computeIfPresent(user,(id,sockets) -> { sockets.remove(socket);return sockets.isEmpty()?null:sockets; });
    }
    private synchronized boolean admit(Run run) {
        if(closing || activeRuns.size()>=limits.getMaxConcurrentRuns()
                || activeRuns.values().stream().filter(r -> r.user.equals(run.user)).count()>=limits.getMaxConcurrentRunsPerUser()) return false;
        return activeRuns.putIfAbsent(key(run.user,run.request),run)==null;
    }
    private Mono<AgentRunResult> observe(Run run,java.util.function.Supplier<Mono<AgentRunResult>> work) {
        var override=agentConfig.getProfiles().getWork().getTotalTimeout();
        var timeout=override==null?agentConfig.getTotalTimeout():override;
        return telemetry==null?Mono.defer(work).timeout(timeout):telemetry.observe("id:"+run.user,run.question,run.conversation,run.request,timeout,
                Map.of("agentMode","work"),work,id -> run.runId=id);
    }
    private void persisted(AgentRunResult result,String user,boolean saved) { if(telemetry!=null) telemetry.persisted(result.runId(),"id:"+user,saved); }
    private boolean mayFallback(Throwable error) {
        if(error instanceof SecurityException || error instanceof java.util.concurrent.TimeoutException) return false;
        return !(error instanceof com.yizhaoqi.smartpai.rag.RagFailure failure)
                || failure.status()==com.yizhaoqi.smartpai.rag.model.RagTypes.Status.FAILED;
    }
    public void processMessage(String user,String question,String conversation,String request,WebSocketSession session) {
        processMessage(user,question,conversation,request,SourceMode.AUTO,session);
    }
    public void processMessage(String user,String question,String conversation,String request,SourceMode source,WebSocketSession session) {
        attachSession(user,session);
        Run existing=activeRuns.get(key(user,request));
        if(existing!=null) {
            if(!existing.conversation.equals(conversation) || !existing.question.equals(question)) reject(session,conversation,request,"请求标识已用于其他内容");
            else {var snapshot=new HashMap<>(existing.snapshot());snapshot.put("type","run_snapshot");sendJson(session,snapshot);}return;
        }
        Run run=new Run(user,conversation,request,session.getId(),question);
        if(!admit(run)) {reject(session,conversation,request,"后台回答已达并发上限，请等待完成或停止其他回答");return;}
        var subscription=Mono.fromCallable(() -> {
            memoryStore.requireWorkSession(Long.parseLong(user),conversation);
            return memoryStore.begin(Long.parseLong(user),conversation,request,question);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(begin -> {
            synchronized(run) {
                run.turn=begin.turn();
                if(!begin.fresh()) {
                    run.state=run.turn.status();
                    if("COMPLETED".equals(run.state)) {
                        emit(run,Map.of("chunk",Objects.toString(run.turn.answer(),"")));
                        emit(run,Map.of("type","completion","status","finished"));
                    } else emit(run,Map.of("type","request_rejected","message","该请求已处理，状态："+run.state));
                    return Mono.<AgentRunResult>empty();
                }
                if(run.subscriptions.isDisposed()) { memoryStore.cancel(run.turn,"CANCELLED");return Mono.empty(); }
                emit(run,Map.of("type","run_started","question",question));
                emit(run,Map.of("type","conversation","switched",begin.switched()));
            }
            MemoryTypes.Context context;
            try {context=memoryContext.build(run.turn);} catch(Exception unavailable) { context=new MemoryTypes.Context(List.of(),true); }
            if(!memoryStore.running(run.turn) || run.subscriptions.isDisposed()) return Mono.<AgentRunResult>empty();
            if(context.reduced()) emit(run,Map.of("type","context_reduced","message","上下文已缩减，较早约束可能未完整保留"));
            var history=context.messages();
            AgentEventListener listener=new AgentEventListener() {
                public void onStatus(String status,String message,Map<String,Object> details) {
                    synchronized(run) {
                        if(!"RUNNING".equals(run.state)) return;
                        run.message=message;
                        if("plan_updated".equals(status) && details.containsKey("tasks")) run.planTasks=details.get("tasks");
                        emit(run,Map.of("type","agent_status","status",status,"message",message,"details",details));
                    }
                }
                public void onChunk(String chunk) {
                    synchronized(run) {
                        if(!"RUNNING".equals(run.state)) return;
                        run.bytes+=chunk.getBytes(StandardCharsets.UTF_8).length;
                        if(run.bytes>limits.getMaxBufferBytes()) throw new IllegalStateException("Answer exceeds snapshot budget");
                        run.text.append(chunk);emit(run,Map.of("chunk",chunk));
                    }
                }
            };
            return observe(run,() -> {
                Mono<AgentRunResult> answer;
                if(agentConfig.isEnabled()) answer=Mono.defer(() -> agentService.run(user,question,history,source,conversation,listener));
                else if(source==SourceMode.KNOWLEDGE) answer=Mono.defer(() -> fallbackService.answer(user,question,history,listener));
                else answer=Mono.error(new IllegalStateException("Agent unavailable"));
                return answer.onErrorResume(error -> {
                    if(source==SourceMode.KNOWLEDGE && agentConfig.isEnabled() && agentConfig.isFallbackEnabled() && run.text.isEmpty() && mayFallback(error))
                        return fallbackService.answer(user,question,history,listener);
                    return Mono.error(error);
                });
            });
        }).publishOn(Schedulers.boundedElastic()).doFinally(signal -> activeRuns.remove(key(user,request),run))
            .subscribe(result -> {
                synchronized(run) {
                    if(!"RUNNING".equals(run.state) || run.subscriptions.isDisposed()) return;
                    try {
                        if(!memoryStore.complete(run.turn,result.answer())) {persisted(result,user,false);fail(run,"本轮未能保存完整回答","SAVE_FAILED");return;}
                        persisted(result,user,true);run.state="COMPLETED";run.text.setLength(0);run.text.append(result.answer());
                        references.put(conversation,result.references());
                        emit(run,Map.of("type","completion","status","finished","sources",result.sources(),"runId",Objects.toString(result.runId(),"")));
                    } catch(Exception error) {persisted(result,user,false);fail(run,"回答保存失败，请勿重复发送同一问题；本轮未确认持久化","SAVE_FAILED");}
                }
            },error -> {
                synchronized(run) {
                    if(!"RUNNING".equals(run.state)) return;
                    if(run.turn==null) reject(session,conversation,request,error instanceof ResponseStatusException r?Objects.toString(r.getReason(),"请求被拒绝"):"无法开始本轮问答");
                    else fail(run,"本轮未完成，服务暂不可用，可重试或选择仅知识库模式","PROVIDER_FAILED");
                }
            });
        run.subscriptions.add(subscription);
    }
    private void fail(Run run,String message,String code) {
        run.state="FAILED";run.message=message;
        if(run.turn!=null) {try {memoryStore.cancel(run.turn,"FAILED");memoryStore.failureCode(run.turn,code);} catch(Exception error) {logger.warn("Cannot mark failed turn={}",run.turn.id());}}
        emit(run,Map.of("type","error","error",message));
    }
    private void emit(Run run,Map<String,?> payload) {
        synchronized(run) {
            var event=new HashMap<String,Object>(payload);event.put("conversationId",run.conversation);event.put("requestId",run.request);
            if(run.turn!=null) event.put("turnId",Long.toString(run.turn.id()));event.put("seq",++run.seq);
            var sockets=subscribers.get(run.user);if(sockets!=null) sockets.values().forEach(socket -> sendJson(socket,event));
        }
    }
    private void reject(WebSocketSession socket,String conversation,String request,String message) {
        sendJson(socket,Map.of("type","request_rejected","conversationId",conversation,"requestId",request,"message",message));
    }
    private void sendJson(WebSocketSession socket,Map<String,?> payload) {
        try {synchronized(socket) {if(socket.isOpen()) socket.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));}}
        catch(Exception unavailable) {logger.debug("Work subscriber unavailable: {}",socket.getId());}
    }
    public Map<String,Object> snapshot(long user,String conversation) {
        memoryStore.session(user,conversation);
        for(Run run:activeRuns.values()) if(run.user.equals(Long.toString(user)) && run.conversation.equals(conversation)) return run.snapshot();
        return memoryStore.latestRun(user,conversation);
    }
    public void stop(long user,String conversation,String request) {
        memoryStore.session(user,conversation);
        Run run=activeRuns.get(key(Long.toString(user),request));
        if(run!=null && run.conversation.equals(conversation)) cancel(run,false);
        else {
            var latest=memoryStore.latestRun(user,conversation);
            if(!request.equals(latest.get("requestId"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"运行不存在");
        }
    }
    private void cancel(Run run,boolean restarting) {
        synchronized(run) {
            if(!"RUNNING".equals(run.state)) return;
            run.state=restarting?"FAILED":"CANCELLED";
            if(run.turn!=null) {
                memoryStore.cancel(run.turn,run.state);
                if(restarting) memoryStore.failureCode(run.turn,"SERVER_RESTARTED");
            }
            run.subscriptions.dispose();activeRuns.remove(key(run.user,run.request),run);
            emit(run,Map.of("type","completion","status",restarting?"interrupted":"cancelled"));
        }
    }
    /** Compatibility: only an unambiguous request from the originating socket can be stopped. */
    public void stopResponse(String user,WebSocketSession socket) {
        var matches=activeRuns.values().stream().filter(r -> r.user.equals(user) && r.origin.equals(socket.getId())).toList();
        if(matches.size()==1) cancel(matches.get(0),false);
        else sendJson(socket,Map.of("type","request_rejected","message","请指定要停止的会话和请求"));
    }
    public void cancelSession(String socket) {activeRuns.values().stream().filter(r -> r.origin.equals(socket)).toList().forEach(r -> cancel(r,false));}
    public void cancelClosedMemoryRuns(long user) {
        for(Run run:activeRuns.values()) if(run.turn!=null && run.turn.userId()==user && !memoryStore.running(run.turn)) cancel(run,false);
    }
    public String getReferenceMd5(String conversation,int number) {return references.getOrDefault(conversation,Map.of()).get(number);}
    public void clearSessionReferenceMapping(String socket) { /* Citations belong to conversations, not transport connections. */ }
    @jakarta.annotation.PreDestroy void shutdown() {
        closing=true;activeRuns.values().stream().toList().forEach(run -> cancel(run,true));subscribers.clear();references.clear();
    }
}
