package com.yizhaoqi.smartpai.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final ChatHandler chatHandler;
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtUtils jwtUtils;
    
    // 内部指令令牌 - 可以从配置文件读取
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    public ChatWebSocketHandler(ChatHandler chatHandler, JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId;
        try { userId = extractUserId(session); }
        catch (RuntimeException invalid) {
            try { session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
            return;
        }
        sessions.put(userId, session);
        session.getAttributes().put("authenticatedChatUser", userId);
        logger.info("WebSocket连接已建立，用户ID: {}，会话ID: {}，URI路径: {}",
                    userId, session.getId(), session.getUri().getPath());

        // 发送会话ID到前端
        try {
            Map<String, Object> connectionMessage = Map.of(
                "type", "connection",
                "sessionId", session.getId(),
                "message", "WebSocket连接已建立"
            );
            String jsonMessage = objectMapper.writeValueAsString(connectionMessage);
            session.sendMessage(new TextMessage(jsonMessage));
            logger.info("已发送会话ID到前端: sessionId={}", session.getId());
        } catch (Exception e) {
            logger.error("发送会话ID失败: {}", e.getMessage(), e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(session);
        try {
            String payload = message.getPayload();
            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}", 
                       userId, session.getId(), payload.length());
            
            Map<?,?> jsonMessage;
            try { jsonMessage = objectMapper.readValue(payload, Map.class); }
            catch (Exception invalid) { sendErrorMessage(session, "消息必须是 JSON 对象"); return; }
            String messageType = jsonMessage.get("type") instanceof String value ? value : null;
            if ("stop".equals(messageType) && INTERNAL_CMD_TOKEN.equals(jsonMessage.get("_internal_cmd_token"))) {
                chatHandler.stopResponse(userId, session);
                return;
            }
            if (!"chat".equals(messageType)
                    || !(jsonMessage.get("message") instanceof String question)
                    || !(jsonMessage.get("conversationId") instanceof String conversationId)
                    || !(jsonMessage.get("requestId") instanceof String requestId)
                    || conversationId.isBlank() || requestId.isBlank()) {
                sendErrorMessage(session, "聊天消息需要 type、message、conversationId 和 requestId");
                return;
            }
            chatHandler.processMessage(userId,question,conversationId,requestId,session);
            
        } catch (Exception e) {
            logger.error("处理消息出错，用户ID: {}，会话ID: {}，错误: {}", 
                        userId, session.getId(), e.getMessage(), e);
            sendErrorMessage(session, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String)session.getAttributes().get("authenticatedChatUser");
        if (userId != null) sessions.remove(userId, session);
        logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}",
                    userId, session.getId(), status);

        chatHandler.cancelSession(session.getId());
        // 清理会话的引用映射
        chatHandler.clearSessionReferenceMapping(session.getId());
    }

    private String extractUserId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        String jwtToken = segments[segments.length - 1];
        if (!jwtUtils.validateToken(jwtToken)) throw new IllegalArgumentException("Invalid token");
        String id=jwtUtils.extractUserIdFromToken(jwtToken);
        if (id == null || !id.matches("[0-9]+")) throw new IllegalArgumentException("Missing user ID; sign in again");
        return id;
    }

    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            Map<String, String> error = Map.of("error", errorMessage);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            logger.info("已发送错误消息到会话: {}, 错误: {}", session.getId(), errorMessage);
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取内部指令令牌 - 供前端调用
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
}
