package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentEventListener;
import com.yizhaoqi.smartpai.agent.AgentRunResult;
import com.yizhaoqi.smartpai.agent.KnowledgeAgentService;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.yizhaoqi.smartpai.memory.*;
import com.yizhaoqi.smartpai.memory.MemoryTypes.Turn;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatHandler {
    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private final RedisTemplate<String, String> redisTemplate;
    private final KnowledgeAgentService agentService;
    private final RagFallbackService fallbackService;
    private final AiProperties.Agent agentConfig;
    private final ObjectMapper objectMapper;
    private final Map<String, Disposable.Composite> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, String>> sessionReferenceMappings = new ConcurrentHashMap<>();
    private final Map<String, Turn> memoryTurns = new ConcurrentHashMap<>();
    private MemoryStore memoryStore;
    private MemoryContextService memoryContext;
    private MemoryProperties memoryConfig;

    @Autowired
    public void configureMemory(MemoryStore store, MemoryContextService context, MemoryProperties config) {
        memoryStore=store; memoryContext=context; memoryConfig=config;
    }
    public boolean memoryEnabled() { return memoryConfig != null && memoryConfig.isEnabled(); }
    public void cancelClosedMemoryRuns(long userId) {
        memoryTurns.forEach((socket,turn) -> {
            if (turn.userId()!=userId) return;
            try { if (!memoryStore.running(turn)) cancelActiveRun(socket); }
            catch (Exception unavailable) { logger.warn("Cannot inspect memory turn={}",turn.id()); }
        });
    }

    public ChatHandler(RedisTemplate<String, String> redisTemplate, KnowledgeAgentService agentService,
                       RagFallbackService fallbackService, AiProperties properties, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.agentService = agentService;
        this.fallbackService = fallbackService;
        this.agentConfig = properties.getAgent();
        this.objectMapper = objectMapper;
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        if (memoryEnabled()) { processMessage(userId, userMessage, null, UUID.randomUUID().toString(), session); return; }
        cancelActiveRun(session.getId());
        Disposable.Composite runGroup = Disposables.composite();
        activeRuns.put(session.getId(), runGroup);
        String conversationId = getOrCreateConversationId(userId);
        List<Map<String, String>> history = getConversationHistory(conversationId);
        if (!agentConfig.isEnabled()) {
            processFallback(userId, userMessage, session, conversationId, history);
            return;
        }
        AtomicBoolean emittedAnswer = new AtomicBoolean(false);
        AgentEventListener listener = new AgentEventListener() {
            @Override public void onStatus(String status, String message, Map<String, Object> details) {
                Map<String, Object> event = new HashMap<>(details);
                event.put("type", "agent_status"); event.put("status", status); event.put("message", message);
                sendJson(session, event);
            }
            @Override public void onChunk(String chunk) { emittedAnswer.set(true); sendResponseChunk(session, chunk); }
        };
        Disposable run = agentService.run(userId, userMessage, toAgentHistory(history), listener)
                .subscribe(result -> completeAgent(session, conversationId, userMessage, result),
                        error -> {
                            logger.warn("Agent执行失败，会话={}, fallback={}: {}", session.getId(),
                                    agentConfig.isFallbackEnabled(), error.getMessage());
                            if (agentConfig.isFallbackEnabled() && !emittedAnswer.get()) {
                                sendAgentStatus(session, "fallback", "工具调用不可用，正在切换知识库问答");
                                processFallback(userId, userMessage, session, conversationId, history, runGroup);
                            } else { activeRuns.remove(session.getId()); handleError(session, error); sendCompletionNotification(session); }
                        });
        runGroup.add(run);
    }

    public void processMessage(String userId, String userMessage, String conversationId, String requestId, WebSocketSession session) {
        if (!memoryEnabled()) { processMessage(userId, userMessage, session); return; }
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
                var context = memoryContext.build(t);
                if (context.reduced()) sendMemory(session,t,Map.of("type","context_reduced","message","上下文已缩减，较早约束可能未完整保留"));
                AgentEventListener listener = new AgentEventListener() {
                    public void onStatus(String status, String message, Map<String,Object> details) {
                        if (!group.isDisposed()) sendMemory(session,t,Map.of("type","agent_status","status",status,"message",message));
                    }
                    public void onChunk(String chunk) {
                        if (group.isDisposed()) return;
                        emitted.set(true); sendMemory(session,t,Map.of("chunk",chunk));
                    }
                };
                Mono<AgentRunResult> answer = agentConfig.isEnabled()
                        ? agentService.run(userId,userMessage,context.messages(),listener)
                        : memoryFallback(userId,userMessage,context.messages(),listener);
                return answer.onErrorResume(error -> {
                    if (agentConfig.isEnabled() && agentConfig.isFallbackEnabled() && !emitted.get())
                        return memoryFallback(userId,userMessage,context.messages(),listener);
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
                        sendMemory(session,t,Map.of("type","completion","status","finished"));
                    }
                } catch (Exception failedSave) { sendMemory(session,t,Map.of("error","回答保存失败，请勿重复发送同一问题；本轮未确认持久化")); }
            }, error -> {
                Turn t=turnRef.get();
                if (t!=null) {
                    try { memoryStore.cancel(t,"FAILED"); } catch (Exception ignored) { logger.warn("Cannot mark failed memory turn={}",t.id()); }
                    sendMemory(session,t,Map.of("error","本轮未完成，可能是上下文过长或服务暂不可用","type","error"));
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

    private void completeAgent(WebSocketSession session, String conversationId, String userMessage, AgentRunResult result) {
        activeRuns.remove(session.getId());
        sessionReferenceMappings.put(session.getId(), result.references());
        updateConversationHistory(conversationId, userMessage, result.answer());
        sendCompletionNotification(session);
    }

    private void processFallback(String userId, String userMessage, WebSocketSession session,
                                 String conversationId, List<Map<String, String>> history) {
        Disposable.Composite runGroup = activeRuns.computeIfAbsent(session.getId(), key -> Disposables.composite());
        processFallback(userId, userMessage, session, conversationId, history, runGroup);
    }

    private void processFallback(String userId, String userMessage, WebSocketSession session,
                                 String conversationId, List<Map<String, String>> history,
                                 Disposable.Composite runGroup) {
        StringBuilder answer = new StringBuilder();
        try {
            Disposable run = fallbackService.stream(userId, userMessage, history,
                    chunk -> { answer.append(chunk); sendResponseChunk(session, chunk); },
                    refs -> sessionReferenceMappings.put(session.getId(), refs),
                    error -> { activeRuns.remove(session.getId()); handleError(session, error); sendCompletionNotification(session); },
                    () -> { activeRuns.remove(session.getId()); updateConversationHistory(conversationId, userMessage, answer.toString()); sendCompletionNotification(session); });
            runGroup.add(run);
        } catch (Exception error) { handleError(session, error); sendCompletionNotification(session); }
    }

    private List<AgentMessage> toAgentHistory(List<Map<String, String>> history) {
        List<AgentMessage> messages = new ArrayList<>();
        for (Map<String, String> item : history) {
            if ("user".equals(item.get("role"))) messages.add(AgentMessage.user(item.get("content")));
            else if ("assistant".equals(item.get("role"))) messages.add(AgentMessage.assistant(item.get("content")));
        }
        return messages;
    }

    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String id = redisTemplate.opsForValue().get(key);
        if (id == null) { id = UUID.randomUUID().toString(); redisTemplate.opsForValue().set(key, id, Duration.ofDays(7)); }
        return id;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        String json = redisTemplate.opsForValue().get("conversation:" + conversationId);
        if (json == null) return new ArrayList<>();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { logger.warn("忽略损坏的会话历史: {}", conversationId); return new ArrayList<>(); }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response) {
        List<Map<String, String>> history = getConversationHistory(conversationId);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        history.add(message("user", userMessage, timestamp)); history.add(message("assistant", response, timestamp));
        if (history.size() > 20) history = new ArrayList<>(history.subList(history.size() - 20, history.size()));
        try { redisTemplate.opsForValue().set("conversation:" + conversationId, objectMapper.writeValueAsString(history), Duration.ofDays(7)); }
        catch (JsonProcessingException e) { logger.error("保存会话历史失败", e); }
    }

    private Map<String, String> message(String role, String content, String timestamp) {
        Map<String, String> value = new HashMap<>();
        value.put("role", role); value.put("content", content); value.put("timestamp", timestamp); return value;
    }

    private void sendResponseChunk(WebSocketSession session, String chunk) { sendJson(session, Map.of("chunk", chunk)); }
    private void sendAgentStatus(WebSocketSession session, String status, String message) {
        sendJson(session, Map.of("type", "agent_status", "status", status, "message", message));
    }
    private void sendCompletionNotification(WebSocketSession session) {
        sendJson(session, Map.of("type", "completion", "status", "finished", "message", "响应已完成",
                "timestamp", System.currentTimeMillis(), "date", LocalDateTime.now().toString()));
    }
    private void handleError(WebSocketSession session, Throwable error) {
        logger.error("AI服务错误", error); sendJson(session, Map.of("error", "AI服务暂时不可用，请稍后重试"));
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
