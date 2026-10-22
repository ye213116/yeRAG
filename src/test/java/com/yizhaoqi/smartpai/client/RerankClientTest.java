package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yizhaoqi.smartpai.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankClientTest {

    private static RetrievalProperties properties() {
        return new RetrievalProperties();
    }

    @Test
    void parsesScoresAndSortsDescending() throws Exception {
        String response = "{\"output\":{\"results\":["
                + "{\"index\":2,\"relevance_score\":0.91},"
                + "{\"index\":0,\"relevance_score\":0.42}]}}";

        List<RerankClient.RerankHit> hits = RerankClient.parse(response, 3);

        assertEquals(2, hits.size());
        assertEquals(2, hits.get(0).index());
        assertEquals(0.91, hits.get(0).score(), 1e-12);
        assertEquals(0, hits.get(1).index());
    }

    @Test
    void dropsOutOfRangeIndexesInsteadOfFailingTheWholeCall() throws Exception {
        String response = "{\"output\":{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.5},"
                + "{\"index\":9,\"relevance_score\":0.99}]}}";

        List<RerankClient.RerankHit> hits = RerankClient.parse(response, 2);

        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).index());
    }

    @Test
    void returnsNullForUnusablePayloads() throws Exception {
        assertNull(RerankClient.parse("{\"code\":\"InvalidParameter\",\"message\":\"boom\"}", 3));
        assertNull(RerankClient.parse("", 3));
        assertNull(RerankClient.parse(null, 3));
        assertThrows(JsonProcessingException.class, () -> RerankClient.parse("not-json", 3));
    }

    @Test
    void staysUnavailableWithoutUrlAndFallsBackToNull() {
        RerankClient client = new RerankClient(properties(), "embedding-key");

        assertFalse(client.available());
        assertNull(client.rerank("q", List.of("a", "b"), 2));
    }

    @Test
    void becomesAvailableWithUrlAndReusesEmbeddingKey() {
        RetrievalProperties properties = properties();
        properties.getRerank().setUrl("https://example.invalid/rerank");

        assertTrue(new RerankClient(properties, "embedding-key").available());
    }

    @Test
    void staysUnavailableWithoutAnyApiKey() {
        RetrievalProperties properties = properties();
        properties.getRerank().setUrl("https://example.invalid/rerank");

        RerankClient client = new RerankClient(properties, "");
        assertFalse(client.available());
        assertNull(client.rerank("q", List.of("a"), 1));
    }

    @Test
    void dedicatedKeyOverridesEmbeddingKey() {
        RetrievalProperties properties = properties();
        properties.getRerank().setUrl("https://example.invalid/rerank");
        properties.getRerank().setApiKey("rerank-key");

        assertTrue(new RerankClient(properties, "").available());
    }
}
