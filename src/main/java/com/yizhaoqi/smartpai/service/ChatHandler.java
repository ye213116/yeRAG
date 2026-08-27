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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public ChatHandler(RedisTemplate<String, String> redisTemplate, KnowledgeAgentService agentService,
                       RagFallbackService fallbackService, AiProperties properties, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.agentService = agentService;
        this.fallbackService = fallbackService;
        this.agentConfig = properties.getAgent();
        this.objectMapper = objectMapper;
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
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
        try { if (session.isOpen()) session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload))); }
        catch (Exception e) { logger.warn("发送WebSocket消息失败: {}", e.getMessage()); }
    }

    public void stopResponse(String userId, WebSocketSession session) {
        cancelActiveRun(session.getId());
        sendJson(session, Map.of("type", "stop", "message", "响应已停止", "timestamp", System.currentTimeMillis(),
                "date", java.time.Instant.now().toString()));
    }
    public void cancelSession(String sessionId) { cancelActiveRun(sessionId); }
    private void cancelActiveRun(String sessionId) { Disposable.Composite run = activeRuns.remove(sessionId); if (run != null) run.dispose(); }
    public String getReferenceMd5(String sessionId, int referenceNumber) {
        Map<Integer, String> mapping = sessionReferenceMappings.get(sessionId); return mapping == null ? null : mapping.get(referenceNumber);
    }
    public void clearSessionReferenceMapping(String sessionId) { sessionReferenceMappings.remove(sessionId); }
}
