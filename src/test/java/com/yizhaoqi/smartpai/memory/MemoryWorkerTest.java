package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentLlmClient;
import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryWorkerTest {
    @Test void extractionOnlyReceivesOriginalUserInputNotAnswerOrTools() {
        MemoryStore store=mock(MemoryStore.class); AgentLlmClient llm=mock(AgentLlmClient.class);
        MemoryProperties config=new MemoryProperties();
        Task task=new Task(1,2,"lease",1);
        when(store.turn(2)).thenReturn(new Turn(2,3,"s","r","我是工程师","助手推测：用户是管理员，工具检索：报销制度","COMPLETED",Instant.now()));
        when(llm.decide(anyList(),eq(List.of()))).thenAnswer(call->{
            List<AgentMessage> messages=call.getArgument(0);
            assertEquals(2,messages.size()); assertEquals("我是工程师",messages.get(1).content());
            assertFalse(messages.toString().contains("用户是管理员"));
            return Mono.just(new AgentDecision("[]",List.of()));
        });
        new MemoryWorker(config,store,mock(MemoryContextService.class),new MemoryPolicy(config),llm,new ObjectMapper()).extract(task);
        verify(store).apply(task,List.of()); verify(store,never()).taskFailed(any(),anyString());
    }
    @Test void malformedModelSchemaRetriesWithoutApplyingAnyFacts() {
        MemoryStore store=mock(MemoryStore.class); AgentLlmClient llm=mock(AgentLlmClient.class);
        MemoryProperties config=new MemoryProperties(); Task task=new Task(1,2,"lease",1);
        when(store.turn(2)).thenReturn(new Turn(2,3,"s","r","我是工程师","a","COMPLETED",Instant.now()));
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision("[{\"userId\":99}]",List.of())));
        new MemoryWorker(config,store,mock(MemoryContextService.class),new MemoryPolicy(config),llm,new ObjectMapper()).extract(task);
        verify(store).taskFailed(task,"extraction_failed"); verify(store,never()).apply(any(),anyList());
    }
    @Test void documentExtractionUsesStrictObjectAndOriginalQuestion() {
        MemoryStore store=mock(MemoryStore.class); AgentLlmClient llm=mock(AgentLlmClient.class);
        MemoryProperties config=new MemoryProperties(); config.setStorageMode("document");
        Task task=new Task(1,2,"lease",1);
        when(store.turn(2)).thenReturn(new Turn(2,3,"s","r","我是工程师","secret assistant answer","COMPLETED",Instant.now()));
        when(store.documents(3)).thenReturn(List.of());
        when(llm.decide(anyList(),anyList())).thenAnswer(call->{
            List<AgentMessage> messages=call.getArgument(0);
            assertTrue(messages.get(1).content().contains("我是工程师"));
            assertFalse(messages.toString().contains("secret assistant answer"));
            return Mono.just(new AgentDecision("{\"patches\":[]}",List.of()));
        });
        new MemoryWorker(config,store,mock(MemoryContextService.class),new MemoryPolicy(config),llm,new ObjectMapper()).extract(task);
        verify(store).applyPatches(task,List.of()); verify(store,never()).taskFailed(any(),anyString());
        verify(llm,times(1)).decide(anyList(),anyList());
    }

    @Test void documentProtocolRejectsMalformedNullUnknownAndDuplicateFields() {
        for (String output:List.of("{\"patches\":null}","{\"patches\":[],\"userId\":9}","{\"patches\":[],\"patches\":[]}","{\"patches\":[]} {}")) {
            MemoryStore store=mock(MemoryStore.class); AgentLlmClient llm=mock(AgentLlmClient.class);
            MemoryProperties config=new MemoryProperties(); config.setStorageMode("document");
            Task task=new Task(1,2,"lease",1);
            when(store.turn(2)).thenReturn(new Turn(2,3,"s","r","我是工程师","answer","COMPLETED",Instant.now()));
            when(store.documents(3)).thenReturn(List.of());
            when(llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision(output,List.of())));
            var worker=new MemoryWorker(config,store,mock(MemoryContextService.class),new MemoryPolicy(config),llm,new ObjectMapper());
            try {
                worker.extract(task);
                verify(store).taskFailed(task,"extraction_failed"); verify(store,never()).applyPatches(any(),anyList());
            } finally { worker.close(); }
        }
    }
    @Test void documentProtocolRejectsScalarCoercionAndUsesConfiguredPatchLimit() {
        MemoryStore store=mock(MemoryStore.class); AgentLlmClient llm=mock(AgentLlmClient.class);
        MemoryProperties config=new MemoryProperties(); config.setStorageMode("document"); config.setMaxPatches(3);
        Task task=new Task(1,2,"lease",1);
        when(store.turn(2)).thenReturn(new Turn(2,3,"s","r","我是工程师","answer","COMPLETED",Instant.now()));
        when(llm.decide(anyList(),anyList())).thenAnswer(call -> {
            List<AgentMessage> messages=call.getArgument(0);
            assertTrue(messages.get(0).content().contains("最多 3 项"));
            return Mono.just(new AgentDecision("""
                {"patches":[{"target":"PROFILE","sectionId":null,"heading":123,
                "operation":"UPSERT_SECTION","content":"工程师","evidence":"我是工程师"}]}
                """,List.of()));
        });
        var worker=new MemoryWorker(config,store,mock(MemoryContextService.class),new MemoryPolicy(config),llm,new ObjectMapper());
        try {
            worker.extract(task);
            verify(store).taskFailed(task,"extraction_failed"); verify(store,never()).applyPatches(any(),anyList());
        } finally { worker.close(); }
    }
    @Test void pollRunsAtMostConfiguredNumberOfTasksConcurrently() throws Exception {
        MemoryStore store=mock(MemoryStore.class); AgentLlmClient llm=mock(AgentLlmClient.class);
        MemoryProperties config=new MemoryProperties(); config.setEnabled(true); config.setStorageMode("document"); config.setWorkerConcurrency(2);
        when(store.claim()).thenReturn(java.util.Optional.of(new Task(1,1,"a",1)),java.util.Optional.of(new Task(2,2,"b",1)),java.util.Optional.empty());
        when(store.turn(anyLong())).thenAnswer(call -> new Turn(call.getArgument(0),call.getArgument(0),"s","r","我是工程师","answer","COMPLETED",Instant.now()));
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(2);
        var response=reactor.core.publisher.Sinks.<AgentDecision>one();
        when(llm.decide(anyList(),anyList())).thenAnswer(call -> { started.countDown(); return response.asMono(); });
        var worker=new MemoryWorker(config,store,mock(MemoryContextService.class),new MemoryPolicy(config),llm,new ObjectMapper());
        try {
            worker.poll(); assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            worker.poll(); verify(store,times(2)).claim();
        } finally {
            response.tryEmitValue(new AgentDecision("{\"patches\":[]}",List.of())); worker.close();
        }
        verify(store,times(2)).applyPatches(any(),eq(List.of()));
    }
}
