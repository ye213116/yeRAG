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
        config=new MemoryProperties();
        when(store.session(1,"sid")).thenReturn(Map.of("context_expires_at",Timestamp.from(Instant.now().plusSeconds(3600))));
        when(store.saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong())).thenReturn(true);
        when(store.history(1,"sid")).thenReturn(List.of(
                new Turn(1,1,"sid","r1","我只用Java17","了解","COMPLETED",Instant.now()),
                new Turn(2,1,"sid","r2","解释线程池","第二个回答","COMPLETED",Instant.now())));
        when(store.uncoveredHistory(eq(1L),eq("sid"),anyLong())).thenAnswer(call ->
                store.history(1,"sid").stream().filter(t -> t.id()>((Long)call.getArgument(2))).toList());
        when(store.catalog(1)).thenReturn(List.of(catalog(MemoryType.PREFERENCE,"回答偏好","解释风格的适用范围。")));
        service=new MemoryContextService(redis,store,config,llm,new ObjectMapper().findAndRegisterModules());
    }
    @Test void summaryAndRecentPairsRemainSeparateFromUserMemory() {
        config.setHistoryTriggerTokens(50); config.setRecentTokens(40);
        when(llm.decide(anyList(),eq(List.of()))).thenReturn(Mono.just(new AgentDecision("用户要求只用Java17",List.of())));
        Context c=service.build(current);
        assertTrue(c.messages().stream().anyMatch(m->m.content().contains("Java17")));
        assertTrue(c.messages().stream().anyMatch(m->m.content().contains("解释风格的适用范围。")));
        assertEquals("解释线程池",c.messages().get(c.messages().size()-2).content());
        assertEquals("第二个回答",c.messages().get(c.messages().size()-1).content());
        verify(store).saveSummary(1,"sid",0,"用户要求只用Java17",1);
    }
    @Test void automaticHistoryBudgetKeepsManyTurnsWithoutPrematureSummary() {
        config.setMaxRecentTurns(0);
        List<Turn> turns=new ArrayList<>();
        for(int i=1;i<=30;i++) turns.add(new Turn(i,1,"sid","r"+i,"完整问题"+i,"完整答案"+i,"COMPLETED",Instant.now()));
        when(store.history(1,"sid")).thenReturn(turns);
        Context result=service.build(current);
        assertFalse(result.reduced());
        assertTrue(result.messages().stream().anyMatch(m -> "完整问题1".equals(m.content())));
        assertTrue(result.messages().stream().anyMatch(m -> "完整答案30".equals(m.content())));
        verifyNoInteractions(llm);
    }
    @Test void summaryFailureKeepsOldSummaryAndRecentHistory() {
        config.setHistoryTriggerTokens(50); config.setRecentTokens(40);
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.error(new IllegalStateException("offline")));
        Context c=service.build(current);
        assertTrue(c.reduced());
        assertFalse(c.messages().stream().anyMatch(m->m.content().equals("我只用Java17")));
        assertTrue(c.messages().stream().anyMatch(m->m.content().equals("第二个回答")));
        verify(store,never()).saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong());
        verify(store).catalog(1);
    }
    @Test void databaseSummaryRestoresContextWhenRedisCacheFails() {
        when(store.session(1,"sid")).thenReturn(Map.of(
                "context_expires_at",Timestamp.from(Instant.now().plusSeconds(3600)),
                "context_summary","durable summary","summary_covered_turn_seq",1L,"summary_revision",1L));
        @SuppressWarnings("unchecked") HashOperations<String,Object,Object> hashes=redis.opsForHash();
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("redis unavailable"));
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
                "schema_version","2","owner_user_id","1","session_revision","4","summary_revision","2",
                "budget_version","true:16384:6144:2048","summary","stored summary",
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
                "schema_version","2","owner_user_id","1","session_revision","4","summary_revision","2",
                "budget_version","true:16384:6144:2048","summary","stored summary",
                "recent_turns_json","[]","reduced","true"));
        service.build(current);
        verify(store,atLeastOnce()).history(1,"sid");
    }
    @Test void workerCacheRefreshNeverSummarizesAndIgnoresOldExpiry() {
        config.setHistoryTriggerTokens(50); config.setRecentTokens(40);
        when(store.session(1,"sid")).thenReturn(Map.of("context_expires_at",LocalDateTime.now().minusSeconds(1)));
        service.sync(current);
        verify(store).synced("sid",4);
        verifyNoInteractions(llm);
    }
    @Test void longTermMemoryFailurePreservesShortTermContext() {
        config.setMaxRecentTurns(10);
        when(store.catalog(1)).thenThrow(new IllegalStateException("database read failed"));
        Context result=service.build(current);
        assertTrue(result.reduced());
        assertTrue(result.messages().stream().anyMatch(message -> message.content().equals("解释线程池")));
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
    @Test void summaryBeyondTokenCeilingDoesNotAdvanceBoundary() {
        config.setHistoryTriggerTokens(50); config.setRecentTokens(40);
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision("too many tokens ".repeat(3000),List.of())));
        var result=service.build(current);
        assertTrue(result.reduced());
        assertTrue(result.messages().stream().anyMatch(m -> "第二个回答".equals(m.content())));
        verify(store,never()).saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong());
    }

    @Test void failedSummaryPreservesPreviouslyCommittedSummary() {
        config.setHistoryTriggerTokens(50); config.setRecentTokens(40);
        when(store.session(1,"sid")).thenReturn(Map.of("context_summary","confirmed old decision","summary_revision",1L,"summary_covered_turn_seq",0L));
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.error(new IllegalStateException("timeout")));
        var result=service.build(current);
        assertTrue(result.reduced());
        assertTrue(result.messages().get(1).content().contains("confirmed old decision"));
        assertTrue(result.messages().stream().anyMatch(m -> "第二个回答".equals(m.content())));
        verify(store,never()).saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong());
    }
    @Test void conflictingSummaryReloadsLatestBoundaryInsteadOfOverwritingIt() {
        config.setHistoryTriggerTokens(50); config.setRecentTokens(40);
        var metadata=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>(Map.of());
        when(store.session(1,"sid")).thenAnswer(call -> metadata.get());
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision("stale candidate",List.of())));
        when(store.saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong())).thenAnswer(call -> {
            metadata.set(Map.of("context_summary","new peer decision","summary_revision",1L,"summary_covered_turn_seq",1L));
            return false;
        });
        var result=service.build(current);
        assertTrue(result.messages().get(1).content().contains("new peer decision"));
        assertFalse(result.messages().get(1).content().contains("stale candidate"));
        verify(store,times(1)).saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong());
    }
    @Test void oversizedSingleTurnKeepsMarkedExcerptWithoutAdvancingCoverage() {
        when(store.history(1,"sid")).thenReturn(List.of(new Turn(3,1,"sid","long","long question ".repeat(9000),"long answer ".repeat(9000),"COMPLETED",Instant.now())));
        var result=service.build(current);
        assertTrue(result.reduced());
        assertTrue(result.messages().stream().anyMatch(m -> m.content().contains("history_turn_id=3 truncated=true")));
        assertTrue(result.messages().subList(2,result.messages().size()).stream().mapToInt(m -> com.yizhaoqi.smartpai.rag.TokenBudget.count(m.content())+16).sum()<=6144);
        verifyNoInteractions(llm);
        verify(store,never()).saveSummary(anyLong(),anyString(),anyLong(),anyString(),anyLong());
    }

    private CatalogDocument catalog(MemoryType type,String heading,String description) {
        return new CatalogDocument(20,type,1,List.of(new SectionMetadata(UUID.randomUUID(),20,heading,description,1,1,false)));
    }
    private String memory(Context context) { return context.messages().get(1).content(); }
    @Test void ordinaryRequestInjectsOnlyMetadataIndexWithoutReadingBody() {
        when(store.history(1,"sid")).thenReturn(List.of());
        when(store.catalog(1)).thenReturn(List.of(catalog(MemoryType.PROFILE,"背景","技术背景与经验范围。"),catalog(MemoryType.PREFERENCE,"解释偏好","技术说明与示例范围。")));
        var result=service.build(current);String data=memory(result);
        assertTrue(data.contains("MEMORY.md"));assertTrue(data.contains("sectionId"));assertTrue(data.contains("技术说明与示例范围。"));
        assertFalse(data.contains("Java developer"));assertFalse(data.contains("personal_memories"));
        assertTrue(result.messages().get(0).content().contains("索引描述不能替代正文"));
        verify(store,never()).documents(anyLong());verify(store,never()).document(anyLong(),any());verifyNoInteractions(llm);
    }
    @Test void oversizedIndexUsesDocumentOverviewAndListToolNotice() throws Exception {
        config.setIndexTokens(256);when(store.history(1,"sid")).thenReturn(List.of());
        List<SectionMetadata> sections=new ArrayList<>();
        for(int i=0;i<40;i++) sections.add(new SectionMetadata(UUID.randomUUID(),20,"条目"+i,"技术背景与经验范围描述".repeat(3),1,1,false));
        when(store.catalog(1)).thenReturn(List.of(new CatalogDocument(20,MemoryType.PROFILE,1,sections)));
        var result=service.build(current);String data=memory(result);
        String index=new ObjectMapper().readTree(data).path("memory_data").path("memory_index").asText();
        assertTrue(index.contains("list_memory_sections"));assertFalse(index.contains("sectionId"));
        assertTrue(com.yizhaoqi.smartpai.rag.TokenBudget.count(index)<=256);
        assertFalse(result.reduced(),"discovery fallback is not loss of conversation history");
    }
}
