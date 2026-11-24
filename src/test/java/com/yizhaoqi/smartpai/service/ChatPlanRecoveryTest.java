package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.model.*;
import com.yizhaoqi.smartpai.agent.plan.*;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.memory.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.socket.*;
import reactor.core.publisher.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatPlanRecoveryTest {
    static final String QUESTION = "综合两份文档，详细比较成本风险";
    MemoryStore memory; PlanStore plans; ChatHandler handler; String conversation;
    Client client; Evidence evidence; List<String> frames;
    WebSocketSession socket;

    javax.sql.DataSource dataSource() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:chatplan_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return ds;
    }
    @BeforeEach void setup() throws Exception {
        var ds = dataSource();
        var db = new JdbcTemplate(ds);
        try (var c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new FileSystemResource("docs/databases/ddl.sql")); }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER')");
        var tx = new DataSourceTransactionManager(ds); var ai = new AiProperties(); var json = new ObjectMapper();
        memory = new MemoryStore(db, tx, new MemoryProperties()); plans = new PlanStore(db, tx, json, memory, ai);
        conversation = memory.current(1, false);
        client = new Client(); evidence = new Evidence(); frames = new CopyOnWriteArrayList<>();
        var agent = new KnowledgeAgentService(client, new AgentToolRegistry(List.of(evidence)), ai);
        var guard = mock(PlanRecoveryGuard.class); when(guard.validate(any(), any(), any())).thenReturn(Mono.empty()); agent.configureRecovery(guard);
        var context = mock(MemoryContextService.class); when(context.build(any())).thenReturn(new MemoryTypes.Context(List.of(), false));
        handler = new ChatHandler(agent, mock(RagFallbackService.class), ai, json, memory, context); handler.configurePlans(plans);
        socket = mock(WebSocketSession.class); when(socket.getId()).thenReturn("socket"); when(socket.isOpen()).thenReturn(true);
        doAnswer(call -> { frames.add(((TextMessage) call.getArgument(0)).getPayload()); return null; }).when(socket).sendMessage(any());
    }
    static AgentDecision answer(String text) { return new AgentDecision(text, List.of()); }
    static AgentDecision call(String id) { return new AgentDecision(null, List.of(new ToolCall(id, "search_knowledge_base", "{}"))); }
    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    Map<String, Object> paused() throws Exception {
        await(() -> {
            var snapshot = handler.snapshot(1, conversation);
            return Boolean.TRUE.equals(snapshot.get("resumable")) && ((Number) snapshot.get("seq")).intValue() == 0;
        });
        return handler.snapshot(1, conversation);
    }
    void begin() { handler.processMessage("1", QUESTION, conversation, "request", SourceMode.KNOWLEDGE, socket); }
    @AfterEach void shutdown() { handler.cancelSession("socket"); handler.stop(1, conversation, "request"); }

    @Test void chatResumeCommitsExactlyOneAnswerToTheOriginalTurnAndPreservesStepHistory() throws Exception {
        client.add(answer("{\"steps\":[\"甲方案\",\"乙方案\"]}"), call("a"), answer("甲证据"));
        begin(); var snapshot = paused();
        long turnId = Long.parseLong(snapshot.get("turnId").toString());
        assertEquals(1, evidence.calls);
        client.add(call("b"), answer("乙证据"));
        handler.resume(1, conversation, "request", ((Number) snapshot.get("planVersion")).longValue());
        await(() -> memory.history(1, conversation).size() == 1);
        var result = memory.history(1, conversation).get(0);
        assertEquals(turnId, result.id()); assertEquals("request", result.requestId());
        assertTrue(result.answer().startsWith("full answer")); assertEquals(2, evidence.calls);
        await(() -> frames.stream().anyMatch(frame -> frame.contains("run_resumed") && frame.contains("\"planAttempt\":2")));
        assertFalse(Boolean.TRUE.equals(handler.snapshot(1, conversation).get("resumable")));
    }
    @Test void synthesisRecoveryReplacesPartialOutputAndReusesAllCompletedEvidence() throws Exception {
        client.failFirstStream = true;
        client.add(answer("{\"steps\":[\"甲方案\",\"乙方案\"]}"), call("a"), answer("甲证据"), call("b"), answer("乙证据"));
        begin(); var snapshot = paused();
        assertTrue(memory.history(1, conversation).isEmpty());
        assertEquals(2, evidence.calls);
        assertEquals(PlanStep.Status.RUNNING, plans.find(1, conversation, "request").orElseThrow().checkpoint().steps().get(2).status);
        assertTrue(frames.stream().anyMatch(frame -> frame.contains("partial answer")));
        handler.resume(1, conversation, "request", ((Number) snapshot.get("planVersion")).longValue());
        await(() -> memory.history(1, conversation).size() == 1);
        assertEquals(2, evidence.calls);
        assertFalse(memory.history(1, conversation).get(0).answer().contains("partial answer"));
        assertTrue(memory.history(1, conversation).get(0).answer().startsWith("full answer"));
    }

    static final class Client implements AgentLlmClient {
        Queue<AgentDecision> decisions = new ConcurrentLinkedQueue<>();
        boolean failFirstStream; int streams;
        void add(AgentDecision... replies) { decisions.addAll(Arrays.asList(replies)); }
        public Mono<AgentDecision> decide(List<AgentMessage> history, List<Map<String, Object>> definitions) {
            var next = decisions.poll();
            return next == null ? Mono.error(new IllegalStateException("provider interrupted")) : Mono.just(next);
        }
        public Flux<String> streamFinal(List<AgentMessage> history) {
            return failFirstStream && streams++ == 0 ? Flux.concat(Flux.just("partial answer"), Flux.error(new IllegalStateException("stream lost"))) : Flux.just("full answer");
        }
    }
    static final class Evidence implements AgentTool {
        volatile int calls;
        public String name() { return "search_knowledge_base"; }
        public Source source() { return Source.KNOWLEDGE; }
        public boolean replaySafe() { return true; }
        public Map<String, Object> definition() { return Map.of("name", name()); }
        public ToolExecutionResult execute(String args, ToolExecutionContext context) {
            calls++; int n = context.referenceOffset() + 1;
            return new ToolExecutionResult(true, "evidence", 1, Map.of(n, "b".repeat(32)), Map.of(n, SourceReference.knowledge(n, "document", "b".repeat(32), n)));
        }
    }
}
