package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentLlmClient;
import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.agent.model.ToolCall;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryRewriteServiceTest {
    AgentLlmClient llm = mock(AgentLlmClient.class);
    AiProperties properties = new AiProperties();
    QueryRewriteService service;

    @BeforeEach
    void setUp() {
        service = new QueryRewriteService(llm, new ObjectMapper(), properties);
    }

    @Test
    void rewritesWithOnlyQueryAndNoTools() {
        when(llm.decide(anyList(), eq(List.of())))
                .thenReturn(Mono.just(new AgentDecision("{\"query\":\"  leave policy  \"}", List.of())));
        assertEquals("leave policy", service.rewrite("how do I take leave?"));
        verify(llm).decide(argThat(messages -> messages.size() == 2
                && messages.get(0).role().equals("system")
                && messages.get(1).equals(AgentMessage.user("how do I take leave?"))), eq(List.of()));
    }

    @Test
    void fallsBackForInvalidOutput() {
        for (String content : List.of("not-json", "null", "[]", "{}", "{\"query\":123}",
                "{\"query\":\" \"}", "{\"query\":\"ok\",\"userId\":\"mallory\"}",
                "{\"query\":\"" + "x".repeat(501) + "\"}")) {
            when(llm.decide(anyList(), anyList())).thenReturn(Mono.just(new AgentDecision(content, List.of())));
            assertEquals("original", service.rewrite("original"));
        }
        when(llm.decide(anyList(), anyList())).thenReturn(Mono.just(new AgentDecision(null, List.of())));
        assertEquals("original", service.rewrite("original"));
        when(llm.decide(anyList(), anyList())).thenReturn(Mono.just(new AgentDecision("{\"query\":\"new\"}",
                List.of(new ToolCall("1", "search", "{}")))));
        assertEquals("original", service.rewrite("original"));
    }

    @Test
    void fallsBackForErrorEmptyResponseAndTimeout() {
        when(llm.decide(anyList(), anyList())).thenReturn(Mono.error(new IllegalStateException("offline")));
        assertEquals("original", service.rewrite("original"));
        when(llm.decide(anyList(), anyList())).thenReturn(Mono.empty());
        assertEquals("original", service.rewrite("original"));
        properties.getAgent().setToolTimeout(Duration.ofMillis(20));
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(llm.decide(anyList(), anyList())).thenReturn(Mono.<AgentDecision>never()
                .doOnCancel(() -> cancelled.set(true)));
        assertEquals("original", service.rewrite("original"));
        assertTrue(cancelled.get());
    }

    @Test
    void disabledSkipsModel() {
        properties.getAgent().setQueryRewriteEnabled(false);
        assertEquals("original", service.rewrite("original"));
        verifyNoInteractions(llm);
    }
}
