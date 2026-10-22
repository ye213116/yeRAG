package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentEventListener;
import com.yizhaoqi.smartpai.agent.AgentRunResult;
import com.yizhaoqi.smartpai.agent.KnowledgeAgentService;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.yizhaoqi.smartpai.memory.*;
import com.yizhaoqi.smartpai.memory.MemoryTypes.Turn;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatHandler {
    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private final KnowledgeAgentService agentService;
    private final RagFallbackService fallbackService;
    private final AiProperties.Agent agentConfig;
    private final ObjectMapper objectMapper;
    private final Map<String, Disposable.Composite> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, String>> sessionReferenceMappings = new ConcurrentHashMap<>();
    private final Map<String, Turn> memoryTurns = new ConcurrentHashMap<>();
    private final MemoryStore memoryStore;
    private final MemoryContextService memoryContext;
    public void cancelClosedMemoryRuns(long userId) {
        memoryTurns.forEach((socket,turn) -> {
            if (turn.userId()!=userId) return;
            try { if (!memoryStore.running(turn)) cancelActiveRun(socket); }
            catch (Exception unavailable) { logger.warn("Cannot inspect memory turn={}",turn.id()); }
        });
    }

    public ChatHandler(KnowledgeAgentService agentService, RagFallbackService fallbackService,
                       AiProperties properties, ObjectMapper objectMapper, MemoryStore memoryStore,
                       MemoryContextService memoryContext) {
        this.agentService = agentService;
        this.fallbackService = fallbackService;
        this.agentConfig = properties.getAgent();
        this.objectMapper = objectMapper;
        this.memoryStore = memoryStore;
        this.memoryContext = memoryContext;
    }

    public void processMessage(String userId, String userMessage, String conversationId, String requestId, WebSocketSession session) {
        processMessage(userId, userMessage, conversationId, requestId, SourceMode.AUTO, session);
    }

    public void processMessage(String userId, String userMessage, String conversationId, String requestId,
                               SourceMode sourceMode, WebSocketSession session) {
        String socketId = session.getId();
        cancelClosedMemoryRuns(Long.parseLong(userId));
        Disposable.Composite group = Disposables.composite();
        if (activeRuns.putIfAbsent(socketId, group) != null) {
            sendJson(session, Map.of("type","request_rejected","requestId",requestId,"message","请先停止当前回答")); return;
        }
        AtomicBoolean emitted = new AtomicBoolean();
        var turnRef = new java.util.concurrent.atomic.AtomicReference<Turn>();
        Disposable run = Mono.fromCallable(() -> {
                return memoryStore.begin(Long.parseLong(userId), conversationId, requestId, userMessage);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(begin -> {
                Turn t = begin.turn(); turnRef.set(t);
                if (!begin.fresh()) {
                    if ("COMPLETED".equals(t.status())) {
                        sendMemory(session,t,Map.of("chunk", t.answer()==null ? "" : t.answer()));
                        sendMemory(session,t,Map.of("type","completion","status","finished"));
                    } else sendMemory(session,t,Map.of("type","request_rejected","message","该请求已处理，状态：" + t.status()));
                    return Mono.<AgentRunResult>empty();
                }
                memoryTurns.put(socketId, t);
                if (group.isDisposed()) { memoryStore.cancel(t,"CANCELLED"); return Mono.empty(); }
                memoryContext.cacheCurrent(t.userId(),t.sessionId());
                sendMemory(session,t,Map.of("type","conversation","switched",begin.switched()));
                MemoryTypes.Context context;
                try { context = memoryContext.build(t); }
                catch (Exception unavailable) {
                    logger.warn("Memory context unavailable for turn={}",t.id());
                    context = new MemoryTypes.Context(List.of(),true);
                }
                if (context.reduced()) sendMemory(session,t,Map.of("type","context_reduced","message","上下文已缩减，较早约束可能未完整保留"));
                final MemoryTypes.Context answerContext = context;
                AgentEventListener listener = new AgentEventListener() {
                    public void onStatus(String status, String message, Map<String,Object> details) {
                        if (!group.isDisposed()) sendMemory(session,t,Map.of("type","agent_status","status",status,"message",message,"details",details));
                    }
                    public void onChunk(String chunk) {
                        if (group.isDisposed()) return;
                        emitted.set(true); sendMemory(session,t,Map.of("chunk",chunk));
                    }
                };
                Mono<AgentRunResult> answer;
                if (agentConfig.isEnabled()) {
                    answer = sourceMode == SourceMode.AUTO
                            ? agentService.run(userId,userMessage,answerContext.messages(),listener)
                            : agentService.run(userId,userMessage,answerContext.messages(),sourceMode,listener);
                } else if (sourceMode == SourceMode.KNOWLEDGE) {
                    answer = memoryFallback(userId,userMessage,answerContext.messages(),listener);
                } else {
                    answer = Mono.error(new IllegalStateException("Agent disabled; choose knowledge-only mode for RAG"));
                }
                return answer.onErrorResume(error -> {
                    if (sourceMode == SourceMode.KNOWLEDGE && agentConfig.isEnabled() && agentConfig.isFallbackEnabled() && !emitted.get())
                        return memoryFallback(userId,userMessage,answerContext.messages(),listener);
                    return Mono.error(error);
                }).timeout(agentConfig.getTotalTimeout());
            })
            .publishOn(Schedulers.boundedElastic())
            .doFinally(signal -> {
                activeRuns.remove(socketId,group);
                Turn t = turnRef.get(); if (t != null) memoryTurns.remove(socketId,t);
            })
            .subscribe(result -> {
                Turn t=turnRef.get();
                try {
                    if (!group.isDisposed() && memoryStore.complete(t,result.answer())) {
                        sessionReferenceMappings.put(socketId,result.references());
                        sendMemory(session,t,Map.of("type","completion","status","finished","sources",result.sources()));
                    } else if (!group.isDisposed()) sendMemory(session,t,Map.of("type","error","error","本轮未能保存完整回答"));
                } catch (Exception failedSave) { sendMemory(session,t,Map.of("error","回答保存失败，请勿重复发送同一问题；本轮未确认持久化")); }
            }, error -> {
                Turn t=turnRef.get();
                if (t!=null) {
                    try { memoryStore.cancel(t,"FAILED"); } catch (Exception ignored) { logger.warn("Cannot mark failed memory turn={}",t.id()); }
                    sendMemory(session,t,Map.of("error",sourceMode == SourceMode.KNOWLEDGE
                            ? "本轮未完成，可能是上下文过长或服务暂不可用"
                            : "本轮未完成，Agent 服务暂不可用；如只需内部资料，可选择仅知识库重试", "type","error"));
                } else sendJson(session,Map.of("type","request_rejected","requestId",requestId,"message",
                        error instanceof org.springframework.web.server.ResponseStatusException r ? Objects.toString(r.getReason(),"请求被拒绝") : "无法开始本轮问答"));
            });
        group.add(run);
    }

    private Mono<AgentRunResult> memoryFallback(String user, String question, List<AgentMessage> history, AgentEventListener listener) {
        return Mono.<AgentRunResult>create(sink -> {
            StringBuilder answer = new StringBuilder();
            Map<Integer,String> references = new HashMap<>();
            List<Map<String,String>> legacy = history.stream().map(m -> Map.of("role",m.role(),"content",m.content())).toList();
            Disposable d = fallbackService.stream(user,question,legacy,
                    chunk -> { answer.append(chunk); listener.onChunk(chunk); },references::putAll,sink::error,
                    () -> sink.success(new AgentRunResult(answer.toString(),Map.copyOf(references))));
            sink.onCancel(d);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private void sendMemory(WebSocketSession session, Turn t, Map<String,?> payload) {
        Map<String,Object> tagged = new HashMap<>(payload);
        tagged.put("conversationId",t.sessionId()); tagged.put("turnId",Long.toString(t.id())); tagged.put("requestId",t.requestId());
        sendJson(session,tagged);
    }

    private void sendJson(WebSocketSession session, Map<String, ?> payload) {
        try { synchronized (session) { if (session.isOpen()) session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload))); } }
        catch (Exception e) { logger.warn("发送WebSocket消息失败: {}", e.getMessage()); }
    }

    public void stopResponse(String userId, WebSocketSession session) {
        cancelActiveRun(session.getId());
        sendJson(session, Map.of("type", "stop", "message", "响应已停止", "timestamp", System.currentTimeMillis(),
                "date", java.time.Instant.now().toString()));
    }
    public void cancelSession(String sessionId) { cancelActiveRun(sessionId); }
    private void cancelActiveRun(String sessionId) {
        Turn t=memoryTurns.remove(sessionId);
        Disposable.Composite run = activeRuns.remove(sessionId); if (run != null) run.dispose();
        if(t!=null) try { memoryStore.cancel(t,"CANCELLED"); } catch(Exception e) { logger.warn("Cannot cancel memory turn={}",t.id()); }
    }
    public String getReferenceMd5(String sessionId, int referenceNumber) {
        Map<Integer, String> mapping = sessionReferenceMappings.get(sessionId); return mapping == null ? null : mapping.get(referenceNumber);
    }
    public void clearSessionReferenceMapping(String sessionId) { sessionReferenceMappings.remove(sessionId); }
}
