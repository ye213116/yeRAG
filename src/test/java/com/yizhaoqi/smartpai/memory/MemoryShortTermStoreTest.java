package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentLlmClient;
import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.RagProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryShortTermStoreTest {
    private JdbcTemplate db;
    private MemoryStore store;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:short_term_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(source);
        try (var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("docs/databases/ddl.sql"));
        }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        store=new MemoryStore(db,new DataSourceTransactionManager(source),new MemoryProperties());
    }

    @Test void summarySurvivesNewStoreAndRejectsStaleWrites() {
        var turn=store.begin(1,null,"r1","question").turn();
        assertTrue(store.complete(turn,"answer"));
        assertTrue(store.saveSummary(1,turn.sessionId(),0,"durable summary",turn.id()));
        assertFalse(store.saveSummary(1,turn.sessionId(),0,"stale summary",turn.id()+1));
        assertEquals("durable summary",store.session(1,turn.sessionId()).get("context_summary"));
        assertEquals(1L,((Number)store.session(1,turn.sessionId()).get("summary_revision")).longValue());
        assertThrows(ResponseStatusException.class,()->store.session(2,turn.sessionId()));
    }
    @Test void longQuestionBeyondTheOldByteCeilingIsStoredIntact() {
        String question="完整长问题资料".repeat(5000);
        var turn=store.begin(1,null,"long-question",question).turn();
        assertEquals(question,turn.question());
        assertTrue(store.complete(turn,"完整答案"));
        assertEquals(question,store.history(1,turn.sessionId()).get(0).question());
    }
    @Test void legacySummaryImportRequiresOwnershipAndCannotOverwriteNewSummary() {
        var turn=store.begin(1,null,"r1","question").turn(); store.complete(turn,"answer");
        assertFalse(store.importSummary(2,turn.sessionId(),"foreign",turn.id()));
        assertTrue(store.importSummary(1,turn.sessionId(),"legacy",turn.id()));
        assertFalse(store.importSummary(1,turn.sessionId(),"stale",turn.id()));
        assertEquals("legacy",store.session(1,turn.sessionId()).get("context_summary"));
    }

    @Test void idleAndLegacyExpiryNeverDeleteRawHistoryOrSummary() {
        var turn=store.begin(1,null,"r1","question").turn(); store.complete(turn,"answer");
        assertNull(db.queryForObject("SELECT content_expires_at FROM chat_turn WHERE id=?",Object.class,turn.id()));
        store.saveSummary(1,turn.sessionId(),0,"summary",turn.id());
        db.update("UPDATE chat_session SET context_expires_at=?,last_activity_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(86400)),Timestamp.from(Instant.now().minusSeconds(180*86400)),turn.sessionId());
        db.update("UPDATE chat_turn SET content_expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(86400)),turn.id());
        store.maintain();
        assertEquals(turn.sessionId(),store.current(1,false));
        assertEquals("summary",store.session(1,turn.sessionId()).get("context_summary"));
        assertEquals("answer",store.history(1,turn.sessionId()).get(0).answer());
        assertFalse(store.begin(1,turn.sessionId(),"r1","question").fresh());
    }
    @Test void resumeOldConversationAndDeleteRejectLateWritesAndLegacyImport() {
        var first=store.begin(1,null,"r1","original").turn(); store.complete(first,"answer");
        var task=store.claim().orElseThrow();
        String other=store.current(1,true);
        assertThrows(ResponseStatusException.class,()->store.resume(2,first.sessionId()));
        store.resume(1,first.sessionId());
        assertEquals(first.sessionId(),store.current(1,false));
        assertEquals("CLOSED",store.session(1,other).get("status"));
        var active=store.begin(1,first.sessionId(),"r2","continue").turn();
        store.deleteSession(1,first.sessionId());
        assertFalse(store.complete(active,"late answer"));
        assertFalse(store.saveSummary(1,first.sessionId(),0,"late summary",first.id()));
        assertDoesNotThrow(()->store.applyPatches(task,List.of()));
        assertThrows(ResponseStatusException.class,()->store.history(1,first.sessionId()));
        assertThrows(ResponseStatusException.class,()->store.resume(1,first.sessionId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_turn WHERE session_id=?",Integer.class,first.sessionId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_extraction_task",Integer.class));
        assertFalse(store.importLegacySession(1,first.sessionId(),List.of(new MemoryStore.LegacyTurn("old","old",Instant.now())),Instant.now().plusSeconds(3600)));
    }

    @Test void legacyImportIsIdempotentAndNeverReplacesAnOpenSession() {
        String id=UUID.randomUUID().toString();
        Instant now=Instant.now();
        List<MemoryStore.LegacyTurn> turns=List.of(new MemoryStore.LegacyTurn("old question","old answer",now));
        assertTrue(store.importLegacySession(1,id,turns,now.plusSeconds(3600)));
        assertFalse(store.importLegacySession(1,id,turns,now.plusSeconds(3600)));
        assertEquals(1,store.history(1,id).size());
        assertThrows(ResponseStatusException.class,()->store.history(2,id));
        String second=UUID.randomUUID().toString();
        assertTrue(store.importLegacySession(1,second,turns,now.plusSeconds(3600)));
        assertEquals("CLOSED",store.session(1,second).get("status"));
        assertEquals(id,store.current(1,false));
    }
    @Test void uncoveredHistoryStartsAtTheSummaryBoundary() {
        var first=store.begin(1,null,"r1","one").turn(); store.complete(first,"a1");
        var second=store.begin(1,first.sessionId(),"r2","two").turn(); store.complete(second,"a2");
        var third=store.begin(1,first.sessionId(),"r3","three").turn(); store.complete(third,"a3");
        assertEquals(List.of(second.id(),third.id()),store.uncoveredHistory(1,first.sessionId(),first.id())
                .stream().map(MemoryTypes.Turn::id).toList());
        assertTrue(store.hasContextGap(1,first.sessionId(),first.id(),third.id()));
        assertFalse(store.hasContextGap(1,first.sessionId(),second.id(),third.id()));
    }

    @Test void automaticContextRetainsMoreThanTwoPagesWhileHistoryDisplayStaysBounded() {
        String session=seedContextHistory(405);
        var redis=mock(StringRedisTemplate.class);
        var llm=mock(AgentLlmClient.class);
        var context=new MemoryContextService(redis,store,new MemoryProperties(),llm,
            new ObjectMapper().findAndRegisterModules());
        var current=store.begin(1,session,"current","Continue the conversation.").turn();

        var result=context.build(current);

        assertFalse(result.reduced());
        assertEquals(405,result.messages().stream().filter(m -> m.content().startsWith("question-")).count());
        assertTrue(result.messages().stream().anyMatch(m -> "question-1".equals(m.content())));
        assertTrue(result.messages().stream().anyMatch(m -> "question-405".equals(m.content())));
        assertEquals(200,store.history(1,session).size());
        verifyNoInteractions(llm);
    }

    @Test void automaticContextSummarizesOnlyThePrefixOutsideTheBudgetAcrossPages() throws Exception {
        String session=seedContextHistory(405);
        store=spy(store);
        var redis=mock(StringRedisTemplate.class);
        var llm=mock(AgentLlmClient.class);
        when(llm.decide(anyList(),eq(List.of()))).thenReturn(Mono.just(
            new AgentDecision("Summary of the omitted prefix.",List.of())));
        var json=new ObjectMapper().findAndRegisterModules();
        var properties=new MemoryProperties();
        properties.setHistoryTriggerTokens(8192); properties.setHistoryTargetTokens(4096);
        properties.setSummaryMaxTokens(1024); properties.setRecentTokens(3072);
        var context=new MemoryContextService(redis,store,properties,llm,json);
        var budget=new RagProperties();
        budget.setContextTokens(16000); budget.setSafetyTokens(100); budget.setCompressionTriggerRatio(1);
        var ai=new AiProperties(); ai.getGeneration().setMaxTokens(100);
        context.configureModelBudget(budget,ai);
        var result=context.build(store.begin(1,session,"current","Continue.").turn());

        long covered=((Number)store.session(1,session).get("summary_covered_turn_seq")).longValue();
        assertTrue(covered>0,"The prefix outside the model budget must enter the durable summary");
        assertTrue(result.messages().stream().filter(m -> m.content().startsWith("question-")).count()<200);
        assertFalse(result.messages().stream().anyMatch(m -> "question-1".equals(m.content())));
        assertTrue(result.messages().stream().anyMatch(m -> "question-405".equals(m.content())));
        assertTrue(result.messages().stream().anyMatch(m -> m.content().contains("Summary of the omitted prefix.")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentMessage>> input=ArgumentCaptor.forClass(List.class);
        verify(llm,atLeastOnce()).decide(input.capture(),eq(List.of()));
        var summarized=json.readTree(input.getValue().get(1).content()).path("completed_turns");
        var firstBatch=json.readTree(input.getAllValues().get(0).get(1).content()).path("completed_turns");
        assertEquals(1,firstBatch.get(0).path("id").asLong());
        assertEquals(covered,summarized.get(summarized.size()-1).path("id").asLong());

    }

    @Test void contextPagesKeepOwnershipRetentionStatusAndSummaryBoundaries() {
        String session=seedContextHistory(405);
        db.update("UPDATE chat_turn SET content_expires_at=? WHERE request_id='context-201'",
            Timestamp.from(Instant.now().minusSeconds(1)));
        db.update("UPDATE chat_turn SET status='CANCELLED' WHERE request_id='context-202'");
        db.update("UPDATE chat_turn SET user_id=2 WHERE request_id='context-203'");
        assertTrue(store.saveSummary(1,session,0,"Summary through turn 5.",5));
        var context=new MemoryContextService(mock(StringRedisTemplate.class),store,new MemoryProperties(),
            mock(AgentLlmClient.class),new ObjectMapper().findAndRegisterModules());
        var current=store.begin(1,session,"current","Continue.").turn();
        var result=context.build(current);

        assertFalse(result.reduced());
        assertEquals(398,result.messages().stream().filter(m -> m.content().startsWith("question-")).count());
        assertTrue(result.messages().stream().anyMatch(m -> "question-6".equals(m.content())));
        for (String excluded : List.of("question-1","question-5","question-202","question-203"))
            assertFalse(result.messages().stream().anyMatch(m -> excluded.equals(m.content())),excluded);
        assertThrows(ResponseStatusException.class,() -> store.historyBefore(2,session,206));

        var disabled=new MemoryProperties(); disabled.setShortTermEnabled(false);
        var withoutSummary=new MemoryContextService(mock(StringRedisTemplate.class),store,disabled,
            mock(AgentLlmClient.class),new ObjectMapper().findAndRegisterModules()).build(current);
        assertTrue(withoutSummary.messages().stream().anyMatch(m -> "question-1".equals(m.content())));
        assertFalse(withoutSummary.messages().stream().anyMatch(m -> m.content().contains("Summary through turn 5.")));
    }

    @Test void defaultThresholdCompressesAcrossPagesAndNextTurnImmediatelySeesTheLastAnswer() throws Exception {
        String session=seedContextHistory(600);
        var llm=mock(AgentLlmClient.class);
        when(llm.decide(anyList(),anyList())).thenReturn(Mono.just(new AgentDecision("Decisions and constraints with turn references.",List.of())));
        var json=new ObjectMapper().findAndRegisterModules();
        var context=new MemoryContextService(mock(StringRedisTemplate.class),store,new MemoryProperties(),llm,json);
        var active=store.begin(1,session,"active","newest explicit requirement").turn();
        var compressed=context.build(active);
        long covered=((Number)store.session(1,session).get("summary_covered_turn_seq")).longValue();
        assertTrue(covered>200,"summary must cross the first SQL page");
        String summary=json.readTree(compressed.messages().get(1).content()).path("memory_data").path("summary").asText();
        int raw=compressed.messages().subList(2,compressed.messages().size()).stream()
                .mapToInt(m -> com.yizhaoqi.smartpai.rag.TokenBudget.count(m.content())+16).sum();
        assertTrue(raw<=6144); assertTrue(raw+com.yizhaoqi.smartpai.rag.TokenBudget.count(summary)<=8192);
        int calls=mockingDetails(llm).getInvocations().size();
        assertTrue(store.complete(active,"latest full answer"));
        var next=store.begin(1,session,"next","continue").turn();
        var immediate=context.build(next);
        assertTrue(immediate.messages().stream().anyMatch(m -> "newest explicit requirement".equals(m.content())));
        assertTrue(immediate.messages().stream().anyMatch(m -> "latest full answer".equals(m.content())));
        assertEquals(calls,mockingDetails(llm).getInvocations().size(),"history grows before the next compression");
    }

    private String seedContextHistory(int count) {
        String session=store.current(1,false);
        for (int i=1;i<=count;i++) {
            String question="question-"+i;
            db.update("""
                INSERT INTO chat_turn(session_id,user_id,request_id,request_hash,question,answer,status,created_at,content_expires_at)
                VALUES (?,1,?,?,?,?,'COMPLETED',?,?)
                """,session,"context-"+i,MemoryStore.hash(question),question,"answer",
                Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(3600)));
        }
        return session;
    }

    @Test void administratorHistoryFiltersOwnerDatesCompletionAndRetentionAcrossSessions() {
        Instant start = Instant.parse("2026-10-20T00:00:00Z");
        Instant end = start.plusSeconds(86400);
        var first = store.begin(1, null, "first", "first question").turn();
        store.complete(first, "first answer");
        var second = store.begin(1, store.current(1, true), "second", "second question").turn();
        store.complete(second, "second answer");
        var outside = store.begin(1, second.sessionId(), "outside", "outside").turn();
        store.complete(outside, "outside");
        var expired = store.begin(1, second.sessionId(), "expired", "expired").turn();
        store.complete(expired, "expired");
        var running = store.begin(1, second.sessionId(), "running", "partial").turn();
        var foreign = store.begin(2, null, "foreign", "foreign").turn();
        store.complete(foreign, "foreign");
        db.update("UPDATE chat_turn SET created_at=?", Timestamp.from(start));
        // Include the final instant before the exclusive boundary, but not the next day.
        db.update("UPDATE chat_turn SET created_at=? WHERE id=?", Timestamp.from(end.minusMillis(1)), second.id());
        db.update("UPDATE chat_turn SET created_at=? WHERE id=?", Timestamp.from(end), outside.id());
        db.update("UPDATE chat_turn SET content_expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), expired.id());

        assertEquals(List.of(first.id(), expired.id(), second.id()), store.completedHistory(1, start, end)
                .stream().map(MemoryTypes.Turn::id).toList());
        assertEquals("RUNNING", db.queryForObject("SELECT status FROM chat_turn WHERE id=?", String.class, running.id()));
    }

    @Test void administratorHistoryReturnsLatest200TurnsInChronologicalOrder() {
        Instant start = Instant.now().minusSeconds(1000);
        String session = store.current(1, false);
        for (int i = 0; i < 205; i++) {
            db.update("""
                INSERT INTO chat_turn(session_id,user_id,request_id,request_hash,question,answer,status,created_at,content_expires_at)
                VALUES (?,1,?,?,'question','answer','COMPLETED',?,?)
                """, session, "history-" + i, MemoryStore.hash("question"), Timestamp.from(start.plusSeconds(i)),
                    Timestamp.from(Instant.now().plusSeconds(3600)));
        }
        var history = store.completedHistory(1, start, Instant.now());
        assertEquals(200, history.size());
        assertEquals("history-5", history.get(0).requestId());
        assertEquals("history-204", history.get(199).requestId());
    }
}
