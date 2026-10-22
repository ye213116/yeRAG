package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Configuration.class);

    @EnableConfigurationProperties(RetrievalProperties.class)
    static class Configuration {
    }

    @Test
    void defaultsToRrfWithThirtyTimesWindowAndFiftyCandidateRerank() {
        runner.run(context -> {
            RetrievalProperties properties = context.getBean(RetrievalProperties.class);
            assertEquals(RetrievalProperties.Mode.RRF, properties.getMode());
            assertEquals(30, properties.getRecallMultiplier());
            assertEquals(50, properties.getMaxTopK());
            assertEquals(60d, properties.getRrfRankConstant(), 1e-12);
            assertEquals(Duration.ofSeconds(2), properties.getRouteTimeout());
            assertEquals(50, properties.getRerank().getTopN());
            assertEquals(Duration.ofMillis(1500), properties.getRerank().getTimeout());
            assertEquals("gte-rerank", properties.getRerank().getModel());
        });
    }

    @Test
    void bindsDeploymentStyleValuesIncludingLowercaseModeAndDurationSuffixes() {
        runner.withPropertyValues(
                        "ai.retrieval.mode=legacy",
                        "ai.retrieval.recall-multiplier=10",
                        "ai.retrieval.max-top-k=20",
                        "ai.retrieval.rrf-rank-constant=40",
                        "ai.retrieval.bm25-weight=2.0",
                        "ai.retrieval.knn-weight=0.5",
                        "ai.retrieval.route-timeout=3s",
                        "ai.retrieval.rerank.enabled=false",
                        "ai.retrieval.rerank.url=https://example.invalid/rerank",
                        "ai.retrieval.rerank.top-n=25",
                        "ai.retrieval.rerank.max-doc-chars=800",
                        "ai.retrieval.rerank.timeout=800ms")
                .run(context -> {
                    RetrievalProperties properties = context.getBean(RetrievalProperties.class);
                    assertEquals(RetrievalProperties.Mode.LEGACY, properties.getMode());
                    assertEquals(10, properties.getRecallMultiplier());
                    assertEquals(20, properties.getMaxTopK());
                    assertEquals(2.0d, properties.getBm25Weight(), 1e-12);
                    assertEquals(0.5d, properties.getKnnWeight(), 1e-12);
                    assertEquals(Duration.ofSeconds(3), properties.getRouteTimeout());
                    assertEquals(false, properties.getRerank().isEnabled());
                    assertEquals(25, properties.getRerank().getTopN());
                    assertEquals(800, properties.getRerank().getMaxDocChars());
                    assertEquals(Duration.ofMillis(800), properties.getRerank().getTimeout());
                });
    }
}
