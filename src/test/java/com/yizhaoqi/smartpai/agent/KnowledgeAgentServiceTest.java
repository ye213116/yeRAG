package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import com.yizhaoqi.smartpai.agent.model.ToolCall;
import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionContext;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionResult;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAgentServiceTest {

    @Test
    void answersDirectlyWithoutCallingTool() {
        StubClient client = new StubClient(List.of(new AgentDecision("hello", List.of())), Flux.empty());
        CountingTool tool = new CountingTool();
        List<String> chunks = new ArrayList<>();
        KnowledgeAgentService service = service(client, tool, 3);

        AgentRunResult result = service.run("alice", "hi", List.of(), listener(chunks)).block();

        assertEquals("hello", result.answer());
        assertEquals(List.of("hello"), chunks);
        assertEquals(0, tool.calls.get());
    }

    @Test
    void executesToolThenStreamsFinalAnswer() {
        ToolCall call = new ToolCall("call-1", "search_knowledge_base", "{\"query\":\"policy\"}");
        StubClient client = new StubClient(List.of(
                new AgentDecision(null, List.of(call)),
                new AgentDecision("final answer", List.of())), Flux.empty());
        CountingTool tool = new CountingTool();
        KnowledgeAgentService service = service(client, tool, 3);

        AgentRunResult result = service.run("alice", "question", List.of(), listener(new ArrayList<>())).block();

        assertEquals("final answer", result.answer());
        assertEquals("md5", result.references().get(1));
        assertEquals(1, tool.calls.get());
        assertEquals("alice", tool.lastUser);
    }

    @Test
    void capsToolCallsAndForcesFinalAnswer() {
        ToolCall call = new ToolCall("call", "search_knowledge_base", "{\"query\":\"x\"}");
        StubClient client = new StubClient(List.of(
                new AgentDecision(null, List.of(call)),
                new AgentDecision(null, List.of(call))), Flux.just("forced"));
        CountingTool tool = new CountingTool();
        KnowledgeAgentService service = service(client, tool, 1);

        AgentRunResult result = service.run("alice", "q", List.of(), listener(new ArrayList<>())).block();

        assertEquals("forced", result.answer());
        assertEquals(1, tool.calls.get());
        assertEquals(1, client.decisions.get());
    }

    private KnowledgeAgentService service(AgentLlmClient client, AgentTool tool, int maxCalls) {
        AiProperties properties = new AiProperties();
        properties.getAgent().setMaxToolCalls(maxCalls);
        return new KnowledgeAgentService(client, new AgentToolRegistry(List.of(tool)), properties);
    }

    private AgentEventListener listener(List<String> chunks) {
        return new AgentEventListener() { @Override public void onChunk(String chunk) { chunks.add(chunk); } };
    }

    static class CountingTool implements AgentTool {
        AtomicInteger calls = new AtomicInteger();
        String lastUser;
        public String name() { return "search_knowledge_base"; }
        public Map<String, Object> definition() { return Map.of("type", "function"); }
        public ToolExecutionResult execute(String args, ToolExecutionContext context) {
            calls.incrementAndGet(); lastUser = context.userId();
            return new ToolExecutionResult(true, "{\"result_count\":1}", 1, Map.of(1, "md5"));
        }
    }

    static class StubClient implements AgentLlmClient {
        final List<AgentDecision> replies; final Flux<String> finalChunks; AtomicInteger decisions = new AtomicInteger();
        StubClient(List<AgentDecision> replies, Flux<String> finalChunks) { this.replies = replies; this.finalChunks = finalChunks; }
        public Mono<AgentDecision> decide(List<com.yizhaoqi.smartpai.agent.model.AgentMessage> messages, List<Map<String,Object>> tools) {
            return Mono.just(replies.get(decisions.getAndIncrement()));
        }
        public Flux<String> streamFinal(List<com.yizhaoqi.smartpai.agent.model.AgentMessage> messages) { return finalChunks; }
    }
}
