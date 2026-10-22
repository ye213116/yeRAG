package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.RagFallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemoryChatIntegrationTest {
    @Test void completedAgentPersistsExactlyOneFullAnswer() {
        var fixture=new Fixture();
        when(fixture.agent.run(anyString(),anyString(),anyList(),any())).thenReturn(Mono.just(new AgentRunResult("完整答案",Map.of())));
        fixture.handler.processMessage("1","问题","sid","request",fixture.socket);
        verify(fixture.store,timeout(3000)).complete(fixture.turn,"完整答案");
        verify(fixture.fallback,never()).stream(anyString(),anyString(),anyList(),any(),any(),any(),any());
    }
    @Test void cancellationMarksTurnAndDoesNotSavePartialAnswer() {
        var fixture=new Fixture();
        when(fixture.agent.run(anyString(),anyString(),anyList(),any())).thenReturn(Mono.never());
        fixture.handler.processMessage("1","问题","sid","request",fixture.socket);
        verify(fixture.agent,timeout(3000)).run(anyString(),anyString(),anyList(),any());
        fixture.handler.cancelSession("socket");
        verify(fixture.store,timeout(3000)).cancel(fixture.turn,"CANCELLED");
        verify(fixture.store,never()).complete(any(),anyString());
    }
    @Test void disabledAgentFallbackStillUsesMemoryCompletionPath() {
        var fixture=new Fixture(); fixture.ai.getAgent().setEnabled(false);
        when(fixture.fallback.stream(anyString(),anyString(),anyList(),any(),any(),any(),any())).thenAnswer(call -> {
            java.util.function.Consumer<String> chunk=call.getArgument(3); chunk.accept("降级答案");
            Runnable complete=call.getArgument(6); complete.run(); return reactor.core.Disposables.disposed();
        });
        fixture.handler.processMessage("1","问题","sid","request",SourceMode.KNOWLEDGE,fixture.socket);
        verify(fixture.store,timeout(3000)).complete(fixture.turn,"降级答案");
        verifyNoInteractions(fixture.agent);
    }
    @Test void contextFailureAnswersWithoutHistoryAndStillPersists() {
        var fixture=new Fixture();
        when(fixture.context.build(fixture.turn)).thenThrow(new IllegalStateException("memory unavailable"));
        when(fixture.agent.run(anyString(),anyString(),anyList(),any())).thenReturn(Mono.just(new AgentRunResult("answer",Map.of())));
        fixture.handler.processMessage("1","问题","sid","request",fixture.socket);
        verify(fixture.agent,timeout(3000)).run(eq("1"),eq("问题"),eq(List.of()),any());
        verify(fixture.store,timeout(3000)).complete(fixture.turn,"answer");
    }
    @Test void failedDatabaseCompletionDoesNotSendSuccess() throws Exception {
        var fixture=new Fixture();
        when(fixture.agent.run(anyString(),anyString(),anyList(),any())).thenReturn(Mono.just(new AgentRunResult("answer",Map.of())));
        when(fixture.store.complete(fixture.turn,"answer")).thenThrow(new IllegalStateException("database down"));
        fixture.handler.processMessage("1","问题","sid","request",fixture.socket);
        verify(fixture.store,timeout(3000)).complete(fixture.turn,"answer");
        verify(fixture.socket,timeout(3000)).sendMessage(argThat(message ->
                message instanceof TextMessage text && text.getPayload().contains("回答保存失败")));
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage> messages=ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(fixture.socket,atLeastOnce()).sendMessage(messages.capture());
        assertFalse(messages.getAllValues().stream().anyMatch(message -> message.getPayload().toString().contains("\"type\":\"completion\"")));
    }
    @SuppressWarnings("unchecked")
    static class Fixture {
        MemoryStore store=mock(MemoryStore.class);
        MemoryContextService context=mock(MemoryContextService.class);
        KnowledgeAgentService agent=mock(KnowledgeAgentService.class);
        RagFallbackService fallback=mock(RagFallbackService.class);
        WebSocketSession socket=mock(WebSocketSession.class);
        AiProperties ai=new AiProperties();
        Turn turn=new Turn(1,1,"sid","request","问题",null,"RUNNING",Instant.now());
        ChatHandler handler=new ChatHandler(agent,fallback,ai,new ObjectMapper(),store,context);
        Fixture() {
            when(socket.getId()).thenReturn("socket"); when(socket.isOpen()).thenReturn(true);
            when(store.begin(1,"sid","request","问题")).thenReturn(new Begin(turn,true,false));
            when(context.build(turn)).thenReturn(new Context(List.of(),false));
            when(store.complete(any(),anyString())).thenReturn(true);
        }
    }
}
