package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesNativeToolCall() throws Exception {
        String json = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"c1","type":"function","function":{"name":"search_knowledge_base","arguments":"{\\\"query\\\":\\\"policy\\\"}"}}
                ]}}]}
                """;
        AgentDecision decision = DeepSeekClient.parseDecision(mapper, json);
        assertTrue(decision.requestsTool());
        assertEquals("c1", decision.toolCalls().get(0).id());
        assertEquals("search_knowledge_base", decision.toolCalls().get(0).name());
    }

    @Test
    void parsesDirectAnswerAndRejectsMissingChoices() throws Exception {
        AgentDecision answer = DeepSeekClient.parseDecision(mapper,
                "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}");
        assertEquals("hello", answer.content());
        assertThrows(IllegalArgumentException.class,
                () -> DeepSeekClient.parseDecision(mapper, "{\"choices\":[]}"));
    }
}
