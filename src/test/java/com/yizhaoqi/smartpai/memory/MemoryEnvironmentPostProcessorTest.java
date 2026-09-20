package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryEnvironmentPostProcessorTest {
    @Test
    void aliasesDotEnvStyleNamesWithoutOverridingCanonicalProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AI_MEMORY_ENABLED", "true")
                .withProperty("AI_MEMORY_STORAGE_MODE", "document")
                .withProperty("AI_MEMORY_WORKER_CONCURRENCY", "3")
                .withProperty("AI_MEMORY_CONTEXT_TTL", "3d")
                .withProperty("ai.memory.context-ttl", "5d");

        new MemoryEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("true", environment.getProperty("ai.memory.enabled"));
        assertEquals("document", environment.getProperty("ai.memory.storage-mode"));
        assertEquals("3", environment.getProperty("ai.memory.worker-concurrency"));
        assertEquals("5d", environment.getProperty("ai.memory.context-ttl"));
    }
}
