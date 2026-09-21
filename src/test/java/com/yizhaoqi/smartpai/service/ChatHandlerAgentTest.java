package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentEventListener;
import com.yizhaoqi.smartpai.agent.KnowledgeAgentService;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHandlerAgentTest {
    @SuppressWarnings("unchecked")
    @Test
    void synchronousAgentFailureKeepsFallbackCancellable() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("conversation-id", null);
        KnowledgeAgentService agent = mock(KnowledgeAgentService.class);
        when(agent.run(anyString(), anyString(), anyList(), any(AgentEventListener.class)))
                .thenReturn(Mono.error(new IllegalStateException("unsupported tools")));
        RagFallbackService fallback = mock(RagFallbackService.class);
        Disposable fallbackRun = mock(Disposable.class);
        when(fallback.stream(anyString(), anyString(), anyList(), any(), any(), any(), any()))
                .thenReturn(fallbackRun);
        AiProperties properties = new AiProperties();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);
        ChatHandler handler = new ChatHandler(redis, agent, fallback, properties, new ObjectMapper());

        handler.processMessage("alice", "question", session);
        handler.cancelSession("ws-1");

        verify(fallbackRun).dispose();
    }
}
