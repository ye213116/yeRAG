package com.yizhaoqi.smartpai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.agent.model.ToolCall;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.artifact.ArtifactService;
import com.yizhaoqi.smartpai.artifact.ArtifactStore;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAgentArtifactTest {
    final ObjectMapper json = new ObjectMapper();
    final ArtifactService artifacts = mock(ArtifactService.class);
    final AiProperties config = new AiProperties();
    final String id = UUID.randomUUID().toString();

    AgentTool saveTool() {
        when(artifacts.save(eq(42L), any(), anyList(), eq(SourceMode.KNOWLEDGE))).thenAnswer(invocation -> {
            var input = invocation.getArgument(1, ArtifactStore.Save.class);
            return new ArtifactStore.Version(id, 1, input.title(), input.content(), invocation.getArgument(2), "now");
        });
        return new SaveArtifactTool(artifacts, json, config);
    }
    AgentTool evidence() {
        return new AgentTool() {
            public String name() { return "search_knowledge_base"; }
            public Source source() { return Source.KNOWLEDGE; }
            public Map<String,Object> definition() { return Map.of("name", name()); }
            public ToolExecutionResult execute(String arguments, ToolExecutionContext context) {
                int number = context.referenceOffset() + 1;
                var source = SourceReference.knowledge(number, "方案", "a".repeat(32), number);
                return new ToolExecutionResult(true, "原文[来源#"+number+"]", 1, Map.of(number, source.fileMd5()), Map.of(number, source));
            }
        };
    }
    static AgentDecision answer(String text) { return new AgentDecision(text, List.of()); }
    static AgentDecision call(String id, String tool, String args) { return new AgentDecision(null, List.of(new ToolCall(id, tool, args))); }

    @Test void synthesisCanSaveAReportAfterPlannedEvidenceCollection() {
        var client = new Client(answer("{\"steps\":[\"读取第一份方案\",\"读取第二份方案\"]}"),
                call("a", "search_knowledge_base", "{}"), answer("第一份记录"),
                call("b", "search_knowledge_base", "{}"), answer("第二份记录"),
                call("save", "save_artifact", "{\"title\":\"选型报告\",\"content\":\"结论[来源#1][来源#2]\"}"), answer("报告已保存"));
        var registry = new AgentToolRegistry(List.of(evidence(), saveTool()));
        var result = new KnowledgeAgentService(client, registry, config).run("42", "综合两份文档，比较风险并生成报告", List.of(),
                SourceMode.KNOWLEDGE, "conversation", new AgentEventListener() {}).block();
        assertNotNull(result);
        assertTrue(result.answer().startsWith("报告已保存"));
        assertFalse(client.toolNames.get(1).contains("save_artifact"));
        assertEquals(List.of("save_artifact"), client.toolNames.get(5));
        verify(artifacts).save(eq(42L), any(), argThat(sources -> sources.size() == 2), eq(SourceMode.KNOWLEDGE));
    }

    @Test void exhaustedRetrievalBudgetStillAllowsSavingAndSuppliesTrustedSources() {
        config.getAgent().setMaxToolCalls(1);
        var client = new Client(call("search", "search_knowledge_base", "{}"),
                call("save", "save_artifact", "{\"title\":\"报告\",\"content\":\"结论[来源#1]\"}"), answer("已保存"));
        var result = new KnowledgeAgentService(client, new AgentToolRegistry(List.of(evidence(), saveTool())), config)
                .run("42", "生成一份报告", List.of(), SourceMode.KNOWLEDGE, "conversation", new AgentEventListener() {}).block();
        assertNotNull(result);
        assertEquals(List.of("save_artifact"), client.toolNames.get(1));
        verify(artifacts).save(eq(42L), any(), argThat(sources -> sources.size() == 1 && sources.get(0).number() == 1), eq(SourceMode.KNOWLEDGE));
    }

    static class Client implements AgentLlmClient {
        final Deque<AgentDecision> decisions;
        final List<List<String>> toolNames = new ArrayList<>();
        Client(AgentDecision... decisions) { this.decisions = new ArrayDeque<>(List.of(decisions)); }
        public Mono<AgentDecision> decide(List<AgentMessage> messages, List<Map<String,Object>> tools) {
            toolNames.add(tools.stream().map(t -> t.containsKey("function")
                    ? ((Map<?,?>) t.get("function")).get("name").toString() : t.get("name").toString()).toList());
            return Mono.just(decisions.removeFirst());
        }
        public Flux<String> streamFinal(List<AgentMessage> messages) { return Flux.error(new AssertionError("report synthesis must allow save_artifact")); }
    }
}
