package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentLlmClient;
import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryContextServiceTest {
    MemoryStore store; StringRedisTemplate redis; AgentLlmClient llm; MemoryProperties config;
    MemoryContextService service;
    Turn current=new Turn(4,1,"sid","r4","当前问题",null,"RUNNING",Instant.now());
    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        store=mock(MemoryStore.class); redis=mock(StringRedisTemplate.class); llm=mock(AgentLlmClient.class);
        HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes); when(hashes.entries(anyString())).thenReturn(Map.of());
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
        config=new MemoryProperties(); config.setMaxRecentTurns(1);
        when(store.session(1,"sid")).thenReturn(Map.of("context_expires_at",Timestamp.from(Instant.now().plusSeconds(3600))));
        when(store.memories(1)).thenReturn(List.of(Map.of("memory_key","preference.response.verbosity","value_json","{\"value\":\"concise\"}")));
        when(store.history(1,"sid")).thenReturn(List.of(
                new Turn(1,1,"sid","r1","我只用Java17","了解","COMPLETED",Instant.now()),
                new Turn(2,1,"sid","r2","解释线程池","第二个回答","COMPLETED",Instant.now())));
        service=new MemoryContextService(redis,store,config,llm,new ObjectMapper().findAndRegisterModules());
    }
    @Test void summaryAndRecentPairsRemainSeparateFromUserMemory() {
        when(llm.decide(anyList(),eq(List.of()))).thenReturn(Mono.just(new AgentDecision("用户要求只用Java17",List.of())));
        Context c=service.build(current);
        assertTrue(c.messages().stream().anyMatch(m->m.content().contains("Java17")));
        assertTrue(c.messages().stream().anyMatch(m->m.content().contains("concise")));
        assertEquals("解释线程池",c.messages().get(c.messages().size()-2).content());
        assertEquals("第二个回答",c.messages().get(c.messages().size()-1).content());
        verify(store).syncedTurns("sid",List.of(1L,2L));
    }
    @Test void summaryFailureUsesRecentCompletePairAndSignalsReduction() {
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.error(new IllegalStateException("offline")));
        Context c=service.build(current);
        assertTrue(c.reduced());
        assertFalse(c.messages().stream().anyMatch(m->m.content().equals("我只用Java17")));
        assertEquals("第二个回答",c.messages().get(c.messages().size()-1).content());
        verify(store).syncedTurns("sid",List.of(2L));
    }
    @Test void expiredContextIsNotRecreated() {
        when(store.session(1,"sid")).thenReturn(Map.of("context_expires_at",LocalDateTime.now().minusSeconds(1)));
        service.sync(current);
        verify(store).contextExpired(4); verifyNoInteractions(redis); verifyNoInteractions(llm);
    }
    private MemoryDocument document(MemoryType type, String heading, String body) {
        return new MemoryDocument(20,1,type,"# Memory\n\n## "+heading+"\n\n"+body+"\n",
                List.of(new SectionMetadata(UUID.randomUUID(),20,heading,1,false)));
    }
    private String memory(Context c) { return c.messages().get(1).content(); }
    @Test void documentRecallBuildsIndexAndPrioritizesMatchingWholeSections() {
        config.setStorageMode("document"); config.setLegacySlotRecall(false); config.setMemoryBytes(400);
        when(store.history(1,"sid")).thenReturn(List.of());
        when(store.documents(1)).thenReturn(List.of(document(MemoryType.PROFILE,"背景","Java developer"),
                document(MemoryType.PREFERENCE,"算法","先提示，再提供完整实现")));
        Context c=service.build(new Turn(4,1,"sid","r4","算法",null,"RUNNING",Instant.now()));
        String data=memory(c);
        assertTrue(data.contains("MEMORY.md")); assertTrue(data.contains("# Memory Index"));
        assertTrue(data.indexOf("MEMORY.md")<data.indexOf("preferences.md"));
        assertTrue(data.contains("## 算法"));
        assertTrue(c.messages().get(0).content().contains("不可信"));
        verifyNoInteractions(llm); verify(store,never()).memories(anyLong());
    }
    @Test void indexSurvivesWhenBodyDoesNotFitAndSignalsReduction() {
        config.setStorageMode("document"); config.setLegacySlotRecall(false); config.setMemoryBytes(200);
        when(store.history(1,"sid")).thenReturn(List.of());
        when(store.documents(1)).thenReturn(List.of(document(MemoryType.PROFILE,"背景","Short paragraph.\n\n"+"X".repeat(500))));
        Context c=service.build(current);
        assertTrue(memory(c).contains("Short paragraph.")); assertFalse(memory(c).contains("XXX")); assertTrue(c.reduced());
    }
    @Test void existingDocumentSuppressesOnlyItsOwnLegacyType() {
        config.setStorageMode("document"); when(store.history(1,"sid")).thenReturn(List.of());
        when(store.documents(1)).thenReturn(List.of(new MemoryDocument(20,1,MemoryType.PROFILE,"# Profile Memory\n",List.of())));
        when(store.memories(1)).thenReturn(List.of(Map.of("memory_key","profile.occupation","value_json","{\"value\":\"old-job\"}"),
                Map.of("memory_key","preference.response.verbosity","value_json","{\"value\":\"concise\"}"),
                Map.of("memory_key","goal.interview","value_json","{\"value\":\"old-goal\"}")));
        String data=memory(service.build(current));
        assertTrue(data.contains("concise")); assertFalse(data.contains("old-job")); assertFalse(data.contains("old-goal"));
    }
}
