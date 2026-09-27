package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentEventListener;
import com.yizhaoqi.smartpai.agent.KnowledgeAgentService;
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
        Disposable fallbackRun=mock(Disposable.class);
        WebSocketSession socket=mock(WebSocketSession.class);
        MemoryTypes.Turn turn=new MemoryTypes.Turn(1,1,"sid","request","question",null,"RUNNING", Instant.now());
        when(socket.getId()).thenReturn("ws-1");
        when(socket.isOpen()).thenReturn(true);
        when(store.begin(1,"sid","request","question")).thenReturn(new MemoryTypes.Begin(turn,true,false));
        when(context.build(turn)).thenReturn(new MemoryTypes.Context(List.of(),false));
        when(agent.run(anyString(),anyString(),anyList(),any(AgentEventListener.class)))
                .thenReturn(Mono.error(new IllegalStateException("unsupported tools")));
        when(fallback.stream(anyString(),anyString(),anyList(),any(),any(),any(),any()))
                .thenReturn(fallbackRun);
        ChatHandler handler=new ChatHandler(agent,fallback,new AiProperties(),new ObjectMapper(),store,context);

        handler.processMessage("1","question","sid","request",socket);
        verify(fallback,timeout(3000)).stream(anyString(),anyString(),anyList(),any(),any(),any(),any());
        handler.cancelSession("ws-1");

        verify(fallbackRun,timeout(3000)).dispose();
        verify(store,never()).complete(any(),anyString());
    }
}
