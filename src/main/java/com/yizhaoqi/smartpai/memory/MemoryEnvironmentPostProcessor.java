package com.yizhaoqi.smartpai.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes conventional AI_MEMORY_* names work when they come from the project's
 * imported .env properties file. Real OS environment variables already receive
 * relaxed-name handling from Spring; a plain properties source does not.
 */
public final class MemoryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ai.memory.worker-concurrency", "AI_MEMORY_WORKER_CONCURRENCY"),
            Map.entry("ai.memory.max-heading-code-points", "AI_MEMORY_MAX_HEADING_CODE_POINTS"),
            Map.entry("ai.memory.max-section-bytes", "AI_MEMORY_MAX_SECTION_BYTES"),
            Map.entry("ai.memory.max-document-bytes", "AI_MEMORY_MAX_DOCUMENT_BYTES"),
            Map.entry("ai.memory.max-patches", "AI_MEMORY_MAX_PATCHES"),
            Map.entry("ai.memory.max-extraction-input-bytes", "AI_MEMORY_MAX_EXTRACTION_INPUT_BYTES"),
            Map.entry("ai.memory.max-extraction-output-bytes", "AI_MEMORY_MAX_EXTRACTION_OUTPUT_BYTES"),
            Map.entry("ai.memory.index-description-code-points", "AI_MEMORY_INDEX_DESCRIPTION_CODE_POINTS"),
            Map.entry("ai.memory.short-term-enabled", "AI_MEMORY_SHORT_TERM_ENABLED"),
            Map.entry("ai.memory.extraction-enabled", "AI_MEMORY_EXTRACTION_ENABLED"),
            Map.entry("ai.memory.recall-enabled", "AI_MEMORY_RECALL_ENABLED"),
            Map.entry("ai.memory.idle-timeout", "AI_MEMORY_IDLE_TIMEOUT"),
            Map.entry("ai.memory.context-ttl", "AI_MEMORY_CONTEXT_TTL"),
            Map.entry("ai.memory.content-retention", "AI_MEMORY_CONTENT_RETENTION"),
            Map.entry("ai.memory.llm-timeout", "AI_MEMORY_LLM_TIMEOUT"),
            Map.entry("ai.memory.task-lease", "AI_MEMORY_TASK_LEASE"),
            Map.entry("ai.memory.run-timeout", "AI_MEMORY_RUN_TIMEOUT"),
            Map.entry("ai.memory.max-attempts", "AI_MEMORY_MAX_ATTEMPTS"),
            Map.entry("ai.memory.max-recent-turns", "AI_MEMORY_MAX_RECENT_TURNS"),
            Map.entry("ai.memory.max-input-bytes", "AI_MEMORY_MAX_INPUT_BYTES"),
            Map.entry("ai.memory.summary-bytes", "AI_MEMORY_SUMMARY_BYTES"),
            Map.entry("ai.memory.memory-bytes", "AI_MEMORY_MEMORY_BYTES"),
            Map.entry("ai.memory.max-question-bytes", "AI_MEMORY_MAX_QUESTION_BYTES"),
            Map.entry("ai.memory.worker-delay-ms", "AI_MEMORY_WORKER_DELAY_MS")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new LinkedHashMap<>();
        ALIASES.forEach((property, alias) -> {
            if (environment.getProperty(property) == null) {
                String value = environment.getProperty(alias);
                if (value != null && !value.isBlank()) values.put(property, value);
            }
        });
        if (!values.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("memoryEnvironmentAliases", values));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
