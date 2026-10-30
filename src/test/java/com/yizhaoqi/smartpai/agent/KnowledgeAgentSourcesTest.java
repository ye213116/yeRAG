package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.model.*;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAgentSourcesTest {
    @Test void knowledgeModeHidesAndRejectsWebAndUnclassifiedMcpTools() {
        var knowledge = new EvidenceTool("search_knowledge_base", AgentTool.Source.KNOWLEDGE);
        var web = new EvidenceTool("search_web", AgentTool.Source.WEB);
        var mcp = new EvidenceTool("remote__lookup", AgentTool.Source.OTHER);
        var client = new ScriptedClient(List.of(toolDecision("search_web"), answer("缺少可用的公开来源")));
        var result = service(client, 8, knowledge, web, mcp).run("42", "联网", List.of(), SourceMode.KNOWLEDGE, listener()).block();
        assertEquals(Set.of("search_knowledge_base"), client.toolNames.get(0));
        assertTrue(client.histories.get(1).stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("tool_not_allowed")));
        assertTrue(web.offsets.isEmpty()); assertTrue(mcp.offsets.isEmpty());
        assertTrue(result.sources().isEmpty());
    }

    @Test void webModeDoesNotExposeOrExecuteKnowledgeTools() {
        var knowledge = new EvidenceTool("search_knowledge_base", AgentTool.Source.KNOWLEDGE);
        var web = new EvidenceTool("search_web", AgentTool.Source.WEB);
        var client = new ScriptedClient(List.of(toolDecision("search_knowledge_base"), answer("只能查询公开资料")));
        service(client, 8, knowledge, web).run("42", "内部制度", List.of(), SourceMode.WEB, listener()).block();
        assertEquals(Set.of("search_web"), client.toolNames.get(0));
        assertTrue(knowledge.offsets.isEmpty());
    }

    @Test void mixedQuestionReadsBothSourcesAndPersistsClickableEvidenceInTheAnswer() {
        var search = new EvidenceTool("search_knowledge_base", AgentTool.Source.KNOWLEDGE);
        var read = new EvidenceTool("read_knowledge_document", AgentTool.Source.KNOWLEDGE);
        var web = new EvidenceTool("search_web", AgentTool.Source.WEB);
        var page = new EvidenceTool("read_web_page", AgentTool.Source.WEB);
        var client = new ScriptedClient(List.of(answer("{\"steps\":[]}"), toolDecision(search.name()), toolDecision(read.name()),
                toolDecision(web.name()), toolDecision(page.name()), answer("内部方案[来源#2]与官方建议[来源#4]有差异")));
        List<String> chunks = new ArrayList<>();
        var history = List.of(AgentMessage.user("上文是部署方案"));
        var result = service(client, 8, search, read, web, page).run("42", "和官方最新建议比较", history,
                new AgentEventListener() { public void onChunk(String s) { chunks.add(s); } }).block();
        assertEquals(List.of(0), search.offsets); assertEquals(List.of(1), read.offsets);
        assertEquals(List.of(2), web.offsets); assertEquals(List.of(3), page.offsets);
        assertEquals(4, result.sources().size());
        assertEquals(Set.of(1, 2), result.references().keySet());
        assertTrue(result.answer().contains("MD5:" + "a".repeat(32)));
        assertTrue(result.answer().contains("https://example.com/official"));
        assertTrue(result.answer().contains("搜索摘要，未读取正文"));
        assertTrue(result.answer().contains("网页正文"));
        assertEquals(result.answer(), String.join("", chunks));
        assertTrue(client.histories.get(0).containsAll(history));
    }

    @Test void processesEveryCallInADecisionAndReturnsAResponseForCallsOverBudget() {
        var knowledge = new EvidenceTool("search_knowledge_base", AgentTool.Source.KNOWLEDGE);
        var web = new EvidenceTool("search_web", AgentTool.Source.WEB);
        var client = new ScriptedClient(List.of(new AgentDecision(null, List.of(
                new ToolCall("1", knowledge.name(), "{}"), new ToolCall("2", web.name(), "{}"),
                new ToolCall("3", web.name(), "{}")))));
        var result = service(client, 2, knowledge, web).run("42", "比较", List.of(), listener()).block();
        assertEquals(List.of(0), knowledge.offsets); assertEquals(List.of(1), web.offsets);
        assertEquals(3, client.finalHistory.stream().filter(m -> "tool".equals(m.role())).count());
        assertTrue(client.finalHistory.stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("tool_budget_exhausted")));
        assertTrue(result.answer().startsWith("根据已有证据回答"));
        assertEquals(2, result.sources().size());
    }

    @Test void unavailableWebModeReportsTheMissingCapabilityWithoutCallingTheModel() {
        var client = new ScriptedClient(List.of());
        var result = service(client, 8).run("42", "最新信息", List.of(), SourceMode.WEB, listener()).block();
        assertTrue(result.answer().contains("尚未启用"));
        assertTrue(client.histories.isEmpty());
    }

    private KnowledgeAgentService service(AgentLlmClient client, int budget, AgentTool... tools) {
        var ai = new AiProperties(); ai.getAgent().setMaxToolCalls(budget);
        return new KnowledgeAgentService(client, new AgentToolRegistry(List.of(tools)), ai);
    }
    private AgentEventListener listener() { return new AgentEventListener() { public void onChunk(String s) { } }; }
    private static AgentDecision toolDecision(String tool) { return new AgentDecision(null, List.of(new ToolCall(UUID.randomUUID().toString(), tool, "{}"))); }
    private static AgentDecision answer(String s) { return new AgentDecision(s, List.of()); }

    static class EvidenceTool implements AgentTool {
        final String name; final Source source; final List<Integer> offsets = new ArrayList<>();
        EvidenceTool(String name, Source source) { this.name = name; this.source = source; }
        public String name() { return name; }
        public Source source() { return source; }
        public Map<String, Object> definition() { return Map.of("name", name); }
        public ToolExecutionResult execute(String json, ToolExecutionContext ctx) {
            assertEquals("42", ctx.userId());
            offsets.add(ctx.referenceOffset());
            int n = ctx.referenceOffset() + 1;
            boolean kb = source == Source.KNOWLEDGE;
            var evidence = kb ? SourceReference.knowledge(n, "部署.md", "a".repeat(32), 3)
                    : SourceReference.web(n, "官方建议", "https://example.com/official", name.equals("search_web") ? "search_snippet" : "page_text");
            return new ToolExecutionResult(true, "{\"reference\":" + n + "}", 1,
                    kb ? Map.of(n, "a".repeat(32)) : Map.of(), Map.of(n, evidence));
        }
    }
    static class ScriptedClient implements AgentLlmClient {
        final List<AgentDecision> replies; final AtomicInteger index = new AtomicInteger();
        final List<List<AgentMessage>> histories = new ArrayList<>();
        final List<Set<String>> toolNames = new ArrayList<>();
        List<AgentMessage> finalHistory = List.of();
        ScriptedClient(List<AgentDecision> replies) { this.replies = replies; }
        public Mono<AgentDecision> decide(List<AgentMessage> messages, List<Map<String, Object>> tools) {
            histories.add(messages);
            toolNames.add(tools.stream().map(t -> (String) t.get("name")).collect(java.util.stream.Collectors.toSet()));
            return Mono.just(replies.get(index.getAndIncrement()));
        }
        public Flux<String> streamFinal(List<AgentMessage> messages) { finalHistory = messages; return Flux.just("根据已有证据回答"); }
    }
}
