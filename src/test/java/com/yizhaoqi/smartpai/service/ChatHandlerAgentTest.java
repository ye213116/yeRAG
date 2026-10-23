package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentEventListener;
import com.yizhaoqi.smartpai.agent.KnowledgeAgentService;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.memory.MemoryContextService;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.memory.MemoryTypes;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHandlerAgentTest {
    @Test
    void agentFailureKeepsRagFallbackCancellable() {
        MemoryStore store=mock(MemoryStore.class);
        MemoryContextService context=mock(MemoryContextService.class);
        KnowledgeAgentService agent=mock(KnowledgeAgentService.class);
        RagFallbackService fallback=mock(RagFallbackService.class);
        Runnable fallbackRun=mock(Runnable.class);
        WebSocketSession socket=mock(WebSocketSession.class);
        MemoryTypes.Turn turn=new MemoryTypes.Turn(1,1,"sid","request","question",null,"RUNNING", Instant.now());
        when(socket.getId()).thenReturn("ws-1");
        when(socket.isOpen()).thenReturn(true);
        when(store.begin(1,"sid","request","question")).thenReturn(new MemoryTypes.Begin(turn,true,false));
        when(context.build(turn)).thenReturn(new MemoryTypes.Context(List.of(),false));
        when(agent.run(anyString(),anyString(),anyList(),eq(SourceMode.KNOWLEDGE),any(AgentEventListener.class)))
                .thenReturn(Mono.error(new IllegalStateException("unsupported tools")));
        when(fallback.answer(anyString(),anyString(),anyList(),any()))
                .thenReturn(Mono.<com.yizhaoqi.smartpai.agent.AgentRunResult>never().doOnCancel(fallbackRun));
        ChatHandler handler=new ChatHandler(agent,fallback,new AiProperties(),new ObjectMapper(),store,context);

        handler.processMessage("1","question","sid","request",SourceMode.KNOWLEDGE,socket);
        verify(fallback,timeout(3000)).answer(anyString(),anyString(),anyList(),any());
        handler.cancelSession("ws-1");

        verify(fallbackRun,timeout(3000)).run();
        verify(store,never()).complete(any(),anyString());
    }

    @Test
    void autoAndWebFailuresNeverFallBackToKnowledgeEvidence() {
        for (SourceMode mode : List.of(SourceMode.AUTO, SourceMode.WEB)) {
            MemoryStore store = mock(MemoryStore.class);
            MemoryContextService context = mock(MemoryContextService.class);
            KnowledgeAgentService agent = mock(KnowledgeAgentService.class);
            RagFallbackService fallback = mock(RagFallbackService.class);
            WebSocketSession socket = mock(WebSocketSession.class);
            MemoryTypes.Turn turn = new MemoryTypes.Turn(1, 1, "sid", "request", "最新官方信息", null, "RUNNING", Instant.now());
            when(socket.getId()).thenReturn("ws"); when(socket.isOpen()).thenReturn(true);
            when(store.begin(1, "sid", "request", "最新官方信息")).thenReturn(new MemoryTypes.Begin(turn, true, false));
            when(context.build(turn)).thenReturn(new MemoryTypes.Context(List.of(), false));
            if (mode == SourceMode.AUTO) when(agent.run(anyString(),anyString(),anyList(),any()))
                    .thenReturn(Mono.error(new IllegalStateException("unavailable")));
            else when(agent.run(anyString(),anyString(),anyList(),eq(mode),any()))
                    .thenReturn(Mono.error(new IllegalStateException("unavailable")));
            new ChatHandler(agent, fallback, new AiProperties(), new ObjectMapper(), store, context)
                    .processMessage("1", "最新官方信息", "sid", "request", mode, socket);
            verify(store, timeout(3000)).cancel(turn, "FAILED");
            verifyNoInteractions(fallback);
            verify(store, never()).complete(any(), anyString());
        }
    }
}
