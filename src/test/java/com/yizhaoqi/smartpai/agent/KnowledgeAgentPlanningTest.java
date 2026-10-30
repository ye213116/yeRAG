package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.model.*;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAgentPlanningTest {
    private static final String COMPLEX = "综合两份文档，详细比较方案的成本、风险并给出建议";
    private static final AgentDecision PLAN = answer("{\"steps\":[\"读取甲方案成本与风险\",\"读取乙方案成本与风险\"]}");

    @Test void onlyAdmitsComplexComparisonsAndCrossDocumentSynthesis() {
        var config = new AiProperties().getAgent();
        var planner = new AgentPlanner(new Client(), config);
        for (String question : List.of("你好", "什么是RAG", "Java和Python有什么区别", "总结这篇文档", "报销流程是什么", "比较"))
            assertFalse(planner.shouldPlan(question, List.of()), question);
        for (String question : List.of(COMPLEX, "跨文档综合分析部署要求", "Compare internal policy with official recommendations", "Summarize these documents"))
            assertTrue(planner.shouldPlan(question, List.of()), question);
        assertTrue(planner.shouldPlan("继续比较它们的风险", List.of(AgentMessage.user("甲乙两份文档"))));
        config.getPlanning().setEnabled(false);
        assertFalse(planner.shouldPlan(COMPLEX, List.of()));
    }

    @Test void simpleQuestionDoesNotMakeAPlanningCallAndDisabledPlanningUsesOriginalLoop() {
        for (boolean enabled : List.of(true, false)) {
            var client = new Client(answer("直接回答"));
            var properties = new AiProperties();
            properties.getAgent().getPlanning().setEnabled(enabled);
            var result = service(client, properties, new EvidenceTool()).run("42", enabled ? "你好" : COMPLEX, List.of(), new Events()).block();
            assertEquals("直接回答", result.answer());
            assertEquals(1, client.histories.size());
            assertFalse(client.definitions.get(0).isEmpty());
        }
    }

    @Test void executesTasksInOrderKeepsEvidenceAndEmitsOnlyTheSynthesis() {
        var client = new Client(PLAN, call("a"), answer("甲依据[来源#1]"), call("b"), answer("乙依据[来源#2]"));
        var tool = new EvidenceTool();
        var events = new Events();
        var result = service(client, new AiProperties(), tool).run("42", COMPLEX,
                List.of(AgentMessage.user("背景约束")), SourceMode.KNOWLEDGE, events).block();

        assertTrue(client.definitions.get(0).isEmpty()); // Planning cannot execute tools.
        assertEquals(List.of(0, 1), tool.offsets);
        assertEquals(Set.of(1, 2), result.references().keySet());
        assertEquals(2, result.sources().size());
        assertTrue(result.answer().startsWith("综合回答"));
        assertEquals(result.answer(), String.join("", events.chunks));
        assertFalse(result.answer().contains("甲依据"));
        assertTrue(client.finalHistory.stream().anyMatch(m -> COMPLEX.equals(m.content())));
        assertTrue(client.finalHistory.stream().anyMatch(m -> "甲依据[来源#1]".equals(m.content())));
        assertTrue(client.histories.stream().allMatch(messages -> messages.stream().anyMatch(m -> "背景约束".equals(m.content()))));
        assertEquals(List.of("pending", "pending", "pending"), events.states(0));
        assertEquals(List.of("completed", "completed", "completed"), events.lastStates());
        assertTrue(events.plans.stream().anyMatch(tasks -> states(tasks).equals(List.of("completed", "running", "pending"))));
    }

    @Test void allTasksShareTheOriginalToolBudgetAndUnfinishedWorkIsVisible() {
        var client = new Client(PLAN, new AgentDecision(null, List.of(
                new ToolCall("a", "search_knowledge_base", "{}"),
                new ToolCall("b", "search_knowledge_base", "{}"),
                new ToolCall("c", "search_knowledge_base", "{}"))));
        var properties = new AiProperties();
        properties.getAgent().setMaxToolCalls(2);
        var tool = new EvidenceTool();
        var events = new Events();
        service(client, properties, tool).run("42", COMPLEX, List.of(), events).block();
        assertEquals(List.of(0, 1), tool.offsets);
        assertEquals(List.of("failed", "skipped", "completed"), events.lastStates());
        assertTrue(client.finalHistory.stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("tool_budget_exhausted")));
        assertTrue(client.finalHistory.stream().anyMatch(m -> m.content() != null && m.content().contains("任务未完整完成")));
    }

    @Test void plannedToolsStillRespectSourceMode() {
        var client = new Client(PLAN,
                new AgentDecision(null, List.of(new ToolCall("bad", "search_web", "{}"))),
                answer("缺少公开资料"), answer("内部资料记录"));
        var events = new Events();
        var tool = new EvidenceTool();
        service(client, new AiProperties(), tool).run("42", COMPLEX, List.of(), SourceMode.KNOWLEDGE, events).block();
        assertTrue(tool.offsets.isEmpty());
        assertEquals(List.of("failed", "completed", "completed"), events.lastStates());
        assertTrue(client.finalHistory.stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("tool_not_allowed")));
    }

    @Test void malformedOrUnneededPlansFallBackToOriginalLoopWithoutLeakingPlannerOutput() {
        for (AgentDecision invalid : List.of(answer("not json"), answer("{\"steps\":[]}"),
                answer("{\"steps\":[\"one\"]}"), answer("{\"steps\":[\"one\",\"one\"]}"),
                answer("{\"steps\":[\"one\",\"two\"],\"userId\":\"admin\"}"),
                answer("{\"steps\":[\"one\",\"two\",\"three\",\"four\"]}"),
                answer("{\"steps\":[\"one\",\"two\"]} {}"), call("planner-must-not-execute"))) {
            var client = new Client(invalid, answer("原流程答案"));
            var events = new Events();
            var tool = new EvidenceTool();
            assertEquals("原流程答案", service(client, new AiProperties(), tool).run("42", COMPLEX, List.of(), events).block().answer());
            assertTrue(events.plans.isEmpty());
            assertTrue(tool.offsets.isEmpty());
            assertEquals(List.of("原流程答案"), events.chunks);
        }
    }

    @Test void plannerTimeoutFallsBackAndIncludesNoTools() {
        var client = new Client(answer("原流程答案"));
        client.pendingPlanner = true;
        var properties = new AiProperties();
        properties.getAgent().getPlanning().setTimeout(Duration.ofMillis(10));
        var events = new Events();
        assertEquals("原流程答案", service(client, properties, new EvidenceTool()).run("42", COMPLEX, List.of(), events).block().answer());
        assertTrue(events.plans.isEmpty());
        assertTrue(client.definitions.get(0).isEmpty());
    }

    @Test void executionFailureAndCancellationSettleAllTasksWithoutPublishingNotes() {
        var events = new Events();
        var client = new Client(PLAN);
        client.afterReplies = Mono.error(new SecurityException("denied"));
        StepVerifier.create(service(client, new AiProperties(), new EvidenceTool()).run("42", COMPLEX, List.of(), events))
                .expectError(SecurityException.class).verify();
        assertEquals(List.of("failed", "skipped", "skipped"), events.lastStates());
        assertTrue(events.chunks.isEmpty());

        var cancelled = new Events();
        var pending = new Client(PLAN);
        StepVerifier.create(service(pending, new AiProperties(), new EvidenceTool()).run("42", COMPLEX, List.of(), cancelled))
                .then(() -> assertEquals(List.of("running", "pending", "pending"), cancelled.lastStates()))
                .thenCancel().verify();
        assertEquals(List.of("cancelled", "skipped", "skipped"), cancelled.lastStates());
        assertTrue(cancelled.chunks.isEmpty());
    }

    @Test void totalDeadlineAlsoCoversPlanningAndExecution() {
        var client = new Client(PLAN);
        var properties = new AiProperties();
        properties.getAgent().setTotalTimeout(Duration.ofMillis(30));
        var events = new Events();
        StepVerifier.create(service(client, properties, new EvidenceTool()).run("42", COMPLEX, List.of(), events))
                .expectError(java.util.concurrent.TimeoutException.class).verify();
        assertEquals(List.of("failed", "skipped", "skipped"), events.lastStates());
    }

    private static AgentDecision answer(String content) { return new AgentDecision(content, List.of()); }
    private static AgentDecision call(String id) { return new AgentDecision(null, List.of(new ToolCall(id, "search_knowledge_base", "{}"))); }
    private static KnowledgeAgentService service(Client client, AiProperties properties, AgentTool tool) {
        return new KnowledgeAgentService(client, new AgentToolRegistry(List.of(tool)), properties);
    }
    private static List<String> states(List<Map<String, String>> tasks) { return tasks.stream().map(t -> t.get("status")).toList(); }

    static class Events implements AgentEventListener {
        final List<String> chunks = new ArrayList<>();
        final List<List<Map<String, String>>> plans = new ArrayList<>();
        @SuppressWarnings("unchecked")
        public void onStatus(String status, String message, Map<String, Object> details) {
            if (status.equals("plan_updated")) plans.add((List<Map<String, String>>) details.get("tasks"));
        }
        public void onChunk(String chunk) { chunks.add(chunk); }
        List<String> states(int index) { return KnowledgeAgentPlanningTest.states(plans.get(index)); }
        List<String> lastStates() { return states(plans.size() - 1); }
    }

    static class Client implements AgentLlmClient {
        final Deque<AgentDecision> replies;
        final List<List<AgentMessage>> histories = new ArrayList<>();
        final List<List<Map<String, Object>>> definitions = new ArrayList<>();
        List<AgentMessage> finalHistory;
        Mono<AgentDecision> afterReplies = Mono.never();
        boolean pendingPlanner;
        Client(AgentDecision... replies) { this.replies = new ArrayDeque<>(List.of(replies)); }
        public Mono<AgentDecision> decide(List<AgentMessage> messages, List<Map<String, Object>> tools) {
            histories.add(messages); definitions.add(tools);
            if (pendingPlanner && tools.isEmpty()) return Mono.never();
            return replies.isEmpty() ? afterReplies : Mono.just(replies.removeFirst());
        }
        public Flux<String> streamFinal(List<AgentMessage> messages) {
            finalHistory = messages;
            return Flux.just("综合", "回答");
        }
    }

    static class EvidenceTool implements AgentTool {
        final List<Integer> offsets = new ArrayList<>();
        public String name() { return "search_knowledge_base"; }
        public Source source() { return Source.KNOWLEDGE; }
        public Map<String, Object> definition() { return Map.of("name", name()); }
        public ToolExecutionResult execute(String arguments, ToolExecutionContext context) {
            assertEquals("42", context.userId());
            offsets.add(context.referenceOffset());
            int n = context.referenceOffset() + 1;
            var source = SourceReference.knowledge(n, "方案.md", "a".repeat(32), n);
            return new ToolExecutionResult(true, "原文证据[来源#" + n + "]", 1, Map.of(n, "a".repeat(32)), Map.of(n, source));
        }
    }
}
