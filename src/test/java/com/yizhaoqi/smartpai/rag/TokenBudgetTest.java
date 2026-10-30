package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBudgetTest {
    private final TokenBudget.Counter tokens = TokenBudget.counter(TokenBudget.DEFAULT_RESOURCE);

    @Test void pinnedTokenizerCountsBpeTokensWithoutTheDefault512TokenTruncation() {
        assertEquals("c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b",tokens.sha256());
        assertEquals(2,tokens.count("Hello!"));
        assertTrue(tokens.count("alpha beta gamma ".repeat(3000))>5000);
        String chinese="企业知识库的完整资料".repeat(100);
        assertTrue(tokens.count(chinese)<chinese.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test void httpUnicodeEscapingAndGenerationParametersDoNotChangeModelVisibleInputCount() throws Exception {
        var json=new ObjectMapper();
        var actual=json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"汉字😀\\n\\\"quote\\\"\"}]}");
        var escaped=json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"\\u6c49\\u5b57\\uD83D\\uDE00\\n\\\"quote\\\"\"}],\"max_tokens\":8192,\"temperature\":0.3}");
        assertEquals(tokens.requestTokens(actual),tokens.requestTokens(escaped));
        ((com.fasterxml.jackson.databind.node.ObjectNode)escaped).set("tools",json.readTree("[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Search reference evidence\"}}]"));
        assertTrue(tokens.requestTokens(escaped)>tokens.requestTokens(actual));
    }

    @Test void profileReservesOutputSafetyAndTheNearCapacityMargin() {
        var profile=new RagProperties();
        assertEquals(1_048_576,profile.getContextTokens());
        assertEquals(984_473,profile.inputTokenLimit(8192));
        profile.setCompressionTriggerRatio(1);
        assertEquals(1_036_288,profile.inputTokenLimit(8192));
        profile.setCompressionTriggerRatio(1.1);
        assertThrows(IllegalArgumentException.class,profile::validate);
    }
}
