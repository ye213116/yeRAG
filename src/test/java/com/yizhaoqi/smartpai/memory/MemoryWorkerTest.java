package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryWorkerTest {
    static class Fixture implements AutoCloseable {
        MemoryStore store=mock(MemoryStore.class);AgentLlmClient llm=mock(AgentLlmClient.class);MemoryProperties config=new MemoryProperties();
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();Task task=new Task(1,2,"lease",1);
        Turn turn=new Turn(2,1,"s","r","我最近开始主要用Java","never use current assistant as source","COMPLETED",Instant.now());
        MemoryWorker worker=new MemoryWorker(config,store,mock(MemoryContextService.class),new MemoryPolicy(config),llm,json);
        Fixture() {
            when(store.turn(2)).thenReturn(turn);when(store.catalog(1)).thenReturn(List.of());when(store.extractionContext(turn)).thenReturn(List.of(turn));when(store.renewLease(task)).thenReturn(true);
            when(store.document(1,MemoryType.PREFERENCE)).thenReturn(new MemoryDocument(0,1,MemoryType.PREFERENCE,"# Preferences Memory\n",List.of(),0));
        }
        String candidates(UUID id) throws Exception { return json.writeValueAsString(Map.of("candidates",List.of(new MemoryCandidate(MemoryType.PREFERENCE,id,"示例优先使用Java",List.of(new MemorySource(2,turn.question())))))); }
        String integrated(UUID id,long version) throws Exception { return json.writeValueAsString(Map.of("reason","用户持续偏好", "patches",List.of(new MemoryPatch(MemoryType.PREFERENCE,id,"解释偏好",MemoryPatchOperation.UPSERT_SECTION,"Java示例优先。","技术解释的示例选择范围。",id==null ? MemoryDecision.CREATE:MemoryDecision.EXTEND,"补充用户偏好",version,List.of(new MemorySource(2,turn.question())))))); }
        @Override public void close() { worker.close(); }
    }
    @Test void emptyCandidateCompletesWithoutSecondCallOrReadingBodies() throws Exception {
        try(var f=new Fixture()) {
            when(f.llm.decide(anyList(),anyList())).thenAnswer(call -> {
                List<AgentMessage> input=call.getArgument(0);assertTrue(input.toString().contains("我最近开始主要用Java"));assertFalse(input.toString().contains("never use current assistant"));
                return Mono.just(new AgentDecision("{\"candidates\":[]}",List.of()));
            });
            f.worker.extract(f.task);verify(f.llm,times(1)).decide(anyList(),anyList());
            verify(f.store).applyPatches(f.task,List.of(),Map.of());verify(f.store,never()).document(anyLong(),any());verify(f.store,never()).taskFailed(any(),anyString(),anyString());
        }
    }
    @Test void twoStagesPassOnlyVerifiedCandidateSourcesAndExpectedVersions() throws Exception {
        try(var f=new Fixture()) {
            when(f.llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision(f.candidates(null),List.of())),Mono.just(new AgentDecision(f.integrated(null,0),List.of())));
            f.worker.extract(f.task);verify(f.llm,times(2)).decide(anyList(),anyList());
            verify(f.store).applyPatches(eq(f.task),argThat(p -> p.size()==1 && p.get(0).sources().get(0).turnId()==2),eq(Map.of(MemoryType.PREFERENCE,0L)));
            verify(f.store,never()).taskFailed(any(),anyString(),anyString());
        }
    }
    @Test void versionConflictRereadsBodyAndReintegratesRatherThanReplayingPatch() throws Exception {
        try(var f=new Fixture()) {
            UUID id=UUID.randomUUID();
            var old=new SectionMetadata(id,1,"解释偏好","解释范围。",1,1,false);
            when(f.store.catalog(1)).thenReturn(List.of(new CatalogDocument(1,MemoryType.PREFERENCE,1,List.of(old))));
            when(f.store.document(1,MemoryType.PREFERENCE)).thenReturn(new MemoryDocument(1,1,MemoryType.PREFERENCE,"# Preferences Memory\n\n## 解释偏好\n\n先讲业务。\n",List.of(old),1),
                    new MemoryDocument(1,1,MemoryType.PREFERENCE,"# Preferences Memory\n\n## 解释偏好\n\n先讲新业务。\n",List.of(new SectionMetadata(id,1,"解释偏好","解释范围。",2,1,false)),2));
            when(f.llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision(f.candidates(id),List.of())),Mono.just(new AgentDecision(f.integrated(id,1),List.of())),Mono.just(new AgentDecision(f.integrated(id,2),List.of())));
            doThrow(new MemoryVersionConflict()).doNothing().when(f.store).applyPatches(eq(f.task),anyList(),anyMap());
            f.worker.extract(f.task);verify(f.llm,times(3)).decide(anyList(),anyList());verify(f.store,times(2)).document(1,MemoryType.PREFERENCE);
            verify(f.store).applyPatches(eq(f.task),argThat(p -> p.get(0).expectedSectionVersion()==2),eq(Map.of(MemoryType.PREFERENCE,2L)));
        }
    }
    @Test void malformedFirstStageCannotCommitOrReadBody() throws Exception {
        for(String output:List.of("{\"candidates\":null}","{\"candidates\":[],\"userId\":9}","{\"candidates\":[],\"candidates\":[]}","{\"candidates\":[]} {}")) try(var f=new Fixture()) {
            when(f.llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision(output,List.of())));
            f.worker.extract(f.task);verify(f.store).taskFailed(eq(f.task),eq("candidate"),anyString());verify(f.store,never()).applyPatches(any(),anyList(),anyMap());verify(f.store,never()).document(anyLong(),any());
        }
    }
    @Test void forgedSourceAndInvalidSecondStageAreRejectedAtomically() throws Exception {
        try(var f=new Fixture()) {
            String forged=f.candidates(null).replace("\"turnId\":2","\"turnId\":999");
            when(f.llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision(forged,List.of())));
            f.worker.extract(f.task);verify(f.store).taskFailed(eq(f.task),eq("candidate"),anyString());verify(f.store,never()).applyPatches(any(),anyList(),anyMap());
        }
        try(var f=new Fixture()) {
            when(f.llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision(f.candidates(null),List.of())),Mono.just(new AgentDecision("{\"patches\":[],\"reason\":5}",List.of())));
            f.worker.extract(f.task);verify(f.store).taskFailed(eq(f.task),eq("integration"),anyString());verify(f.store,never()).applyPatches(any(),anyList(),anyMap());
        }
    }
    @Test void pollRunsAtMostConfiguredTasksConcurrently() throws Exception {
        try(var f=new Fixture()) {
            when(f.store.claim()).thenReturn(Optional.of(f.task),Optional.of(new Task(2,3,"b",1)),Optional.empty());
            when(f.store.turn(3)).thenReturn(new Turn(3,123,"s","r","我是工程师","answer","COMPLETED",Instant.now()));
            CountDownLatch started=new CountDownLatch(2);var response=reactor.core.publisher.Sinks.<AgentDecision>one();
            when(f.llm.decide(anyList(),anyList())).thenAnswer(call -> { started.countDown();return response.asMono(); });
            f.worker.poll();assertTrue(started.await(5,TimeUnit.SECONDS));f.worker.poll();verify(f.store,times(2)).claim();
            response.tryEmitValue(new AgentDecision("{\"candidates\":[]}",List.of()));
        }
    }
}
