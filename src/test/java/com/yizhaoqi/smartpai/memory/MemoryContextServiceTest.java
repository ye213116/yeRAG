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
        when(store.saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong())).thenReturn(true);
        when(store.history(1,"sid")).thenReturn(List.of(
                new Turn(1,1,"sid","r1","我只用Java17","了解","COMPLETED",Instant.now()),
                new Turn(2,1,"sid","r2","解释线程池","第二个回答","COMPLETED",Instant.now())));
        when(store.uncoveredHistory(eq(1L),eq("sid"),anyLong())).thenAnswer(call ->
                store.history(1,"sid").stream().filter(t -> t.id()>((Long)call.getArgument(2))).toList());
        when(store.documents(1)).thenReturn(List.of(
                document(MemoryType.PREFERENCE,"回答偏好","concise")));
        service=new MemoryContextService(redis,store,config,llm,new ObjectMapper().findAndRegisterModules());
    }
    @Test void summaryAndRecentPairsRemainSeparateFromUserMemory() {
        when(llm.decide(anyList(),eq(List.of()))).thenReturn(Mono.just(new AgentDecision("用户要求只用Java17",List.of())));
        Context c=service.build(current);
        assertTrue(c.messages().stream().anyMatch(m->m.content().contains("Java17")));
        assertTrue(c.messages().stream().anyMatch(m->m.content().contains("concise")));
        assertEquals("解释线程池",c.messages().get(c.messages().size()-2).content());
        assertEquals("第二个回答",c.messages().get(c.messages().size()-1).content());
        verify(store).synced("sid",1L);
        verify(store).syncedTurns("sid",List.of(2L));
    }
    @Test void summaryFailureUsesOnlyCurrentQuestionAndSignalsReduction() {
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.error(new IllegalStateException("offline")));
        Context c=service.build(current);
        assertTrue(c.reduced());
        assertFalse(c.messages().stream().anyMatch(m->m.content().equals("我只用Java17")));
        assertFalse(c.messages().stream().anyMatch(m->m.content().equals("第二个回答")));
        verify(store).syncedTurns("sid",List.of(2L));
        verify(store,never()).documents(1);
    }
    @Test void databaseSummaryRestoresContextWhenRedisCacheFails() {
        when(store.session(1,"sid")).thenReturn(Map.of(
                "context_expires_at",Timestamp.from(Instant.now().plusSeconds(3600)),
                "context_summary","durable summary","summary_covered_turn_seq",1L,"summary_revision",1L));
        @SuppressWarnings("unchecked") HashOperations<String,Object,Object> hashes=redis.opsForHash();
        doThrow(new IllegalStateException("redis unavailable")).when(hashes).putAll(anyString(),anyMap());
        Context c=service.build(current);
        assertTrue(c.messages().stream().anyMatch(m->m.content().contains("durable summary")));
        assertEquals("第二个回答",c.messages().get(c.messages().size()-1).content());
        assertFalse(c.reduced());
        verifyNoInteractions(llm);
    }
    @Test void matchingRedisCacheAvoidsHistoryReadButUsesDatabaseVersion() throws Exception {
        when(store.session(1,"sid")).thenReturn(Map.of("context_expires_at",Timestamp.from(Instant.now().plusSeconds(3600)),
                "context_summary","stored summary","revision",4L,"summary_revision",2L));
        @SuppressWarnings("unchecked") HashOperations<String,Object,Object> hashes=redis.opsForHash();
        String recent=new ObjectMapper().findAndRegisterModules().writeValueAsString(List.of(
                new Turn(2,1,"sid","r2","cached question","cached answer","COMPLETED",Instant.now())));
        when(hashes.entries("memory:session:sid:context")).thenReturn(Map.of(
                "schema_version","1","owner_user_id","1","session_revision","4","summary_revision","2",
                "recent_limit","1","short_term_enabled","true","summary","stored summary",
                "recent_turns_json",recent,"reduced","false"));
        Context result=service.build(current);
        assertTrue(result.messages().stream().anyMatch(m->m.content().contains("stored summary")));
        assertEquals("cached answer",result.messages().get(result.messages().size()-1).content());
        verify(store,never()).history(1,"sid");
    }
    @Test void reducedCacheIsRebuiltSoPendingSummariesCanProgress() throws Exception {
        when(store.session(1,"sid")).thenReturn(Map.of("context_expires_at",Timestamp.from(Instant.now().plusSeconds(3600)),
                "context_summary","stored summary","revision",4L,"summary_revision",2L,
                "summary_covered_turn_seq",1L));
        @SuppressWarnings("unchecked") HashOperations<String,Object,Object> hashes=redis.opsForHash();
        when(hashes.entries("memory:session:sid:context")).thenReturn(Map.of(
                "schema_version","1","owner_user_id","1","session_revision","4","summary_revision","2",
                "recent_limit","1","short_term_enabled","true","summary","stored summary",
                "recent_turns_json","[]","reduced","true"));
        service.build(current);
        verify(store,atLeastOnce()).history(1,"sid");
    }
    @Test void expiredContextIsNotRecreated() {
        when(store.session(1,"sid")).thenReturn(Map.of("context_expires_at",LocalDateTime.now().minusSeconds(1)));
        service.sync(current);
        verify(store).contextExpired(4); verifyNoInteractions(redis); verifyNoInteractions(llm);
    }
    @Test void longTermMemoryFailureDropsHistoricalContext() {
        config.setMaxRecentTurns(10);
        when(store.documents(1)).thenThrow(new IllegalStateException("database read failed"));
        Context result=service.build(current);
        assertTrue(result.reduced());
        assertFalse(result.messages().stream().anyMatch(message -> message.content().equals("解释线程池")));
    }
    @Test void importsOnlyOwnedLiveLegacySummary() {
        String oldKey="memory:v2:session:sid:context";
        when(redis.getExpire(oldKey,java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(3000L);
        when(redis.opsForHash().entries(oldKey)).thenReturn(Map.of("owner_user_id","1", "summary","old summary",
                "covered_through_turn_seq","1"));
        when(store.importSummary(1,"sid","old summary",1)).thenReturn(true);
        assertTrue(service.migrateLegacySummary(1,"sid"));
        assertFalse(service.migrateLegacySummary(2,"sid"));
        verify(store).importSummary(1,"sid","old summary",1);
        verify(store,never()).importSummary(eq(2L),anyString(),anyString(),anyLong());
    }
    private MemoryDocument document(MemoryType type, String heading, String body) {
        return new MemoryDocument(20,1,type,"# Memory\n\n## "+heading+"\n\n"+body+"\n",
                List.of(new SectionMetadata(UUID.randomUUID(),20,heading,1,false)));
    }
    private String memory(Context c) { return c.messages().get(1).content(); }
    @Test void documentRecallBuildsIndexAndPrioritizesMatchingWholeSections() {
        config.setMemoryBytes(400);
        when(store.history(1,"sid")).thenReturn(List.of());
        when(store.documents(1)).thenReturn(List.of(document(MemoryType.PROFILE,"背景","Java developer"),
                document(MemoryType.PREFERENCE,"算法","先提示，再提供完整实现")));
        Context c=service.build(new Turn(4,1,"sid","r4","算法",null,"RUNNING",Instant.now()));
        String data=memory(c);
        assertTrue(data.contains("MEMORY.md")); assertTrue(data.contains("# Memory Index"));
        assertTrue(data.indexOf("MEMORY.md")<data.indexOf("preferences.md"));
        assertTrue(data.contains("## 算法"));
        assertTrue(c.messages().get(0).content().contains("不可信"));
        verifyNoInteractions(llm);
    }
    @Test void indexSurvivesWhenBodyDoesNotFitAndSignalsReduction() {
        config.setMemoryBytes(200);
        when(store.history(1,"sid")).thenReturn(List.of());
        when(store.documents(1)).thenReturn(List.of(document(MemoryType.PROFILE,"背景","Short paragraph.\n\n"+"X".repeat(500))));
        Context c=service.build(current);
        assertTrue(memory(c).contains("Short paragraph.")); assertFalse(memory(c).contains("XXX")); assertTrue(c.reduced());
    }
    @Test void existingDocumentSuppressesOnlyItsOwnLegacyType() {
        when(store.history(1,"sid")).thenReturn(List.of());
        when(store.documents(1)).thenReturn(List.of(new MemoryDocument(20,1,MemoryType.PROFILE,"# Profile Memory\n",List.of())));
        when(store.documents(1)).thenReturn(List.of(
                document(MemoryType.PROFILE,"职业","old-job"),
                document(MemoryType.PREFERENCE,"回答风格","concise")));
        String data=memory(service.build(current));
        assertTrue(data.contains("concise")); assertTrue(data.contains("old-job"));
        verifyNoInteractions(llm);
    }
}
