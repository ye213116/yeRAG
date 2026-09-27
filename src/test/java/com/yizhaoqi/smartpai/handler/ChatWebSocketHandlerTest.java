package com.yizhaoqi.smartpai.handler;

import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {
    @Test void rejectsPlainTextAndAcceptsTheUnifiedJsonProtocol() throws Exception {
        ChatHandler chat=mock(ChatHandler.class);
        JwtUtils jwt=mock(JwtUtils.class);
        WebSocketSession socket=mock(WebSocketSession.class);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/chat/token"));
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        when(socket.getId()).thenReturn("socket");
        when(socket.isOpen()).thenReturn(true);
        when(jwt.validateToken("token")).thenReturn(true);
        when(jwt.extractUserIdFromToken("token")).thenReturn("1");
        ChatWebSocketHandler handler=new ChatWebSocketHandler(chat,jwt);
        handler.afterConnectionEstablished(socket);

        handler.handleTextMessage(socket,new TextMessage("hello"));
        verify(chat,never()).processMessage(anyString(),anyString(),anyString(),anyString(),any());
        verify(socket).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("消息必须是 JSON 对象")));

        handler.handleTextMessage(socket,new TextMessage("""
            {"type":"chat","message":"hello","conversationId":"sid","requestId":"r1"}
            """));
        verify(chat).processMessage("1","hello","sid","r1",socket);
    }
}
