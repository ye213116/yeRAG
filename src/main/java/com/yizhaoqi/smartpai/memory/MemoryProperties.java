package com.yizhaoqi.smartpai.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.List;

@Data
@Configuration
@EnableScheduling
@ConfigurationProperties(prefix = "ai.memory")
public class MemoryProperties {
    private boolean shortTermEnabled = true;
    private boolean extractionEnabled = true;
    private boolean recallEnabled = true;
    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration contextTtl = Duration.ofDays(7);
    private Duration contentRetention = Duration.ofDays(7);
    private Duration llmTimeout = Duration.ofSeconds(30);
    private Duration taskLease = Duration.ofMinutes(2);
    private Duration runTimeout = Duration.ofMinutes(3);
    private int maxAttempts = 5;
    /** 0 retains complete turns until the model context budget requires summarization. */
    private int maxRecentTurns = 0;
    private int maxInputBytes = 32 * 1024 * 1024;
    private int summaryBytes = 60_000;
    private int memoryBytes = 256 * 1024;
    private int maxQuestionBytes = 8 * 1024 * 1024;
    private int workerConcurrency = 2;
    private int maxHeadingCodePoints = 80;
    private int maxSectionBytes = 8192;
    private int maxDocumentBytes = 32768;
    private int maxPatches = 8;
    private int maxExtractionInputBytes = 32 * 1024 * 1024;
    private int maxExtractionOutputBytes = 65536;
    private int indexDescriptionCodePoints = 120;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (workerConcurrency < 1 || workerConcurrency > 32 || maxHeadingCodePoints < 1
                || maxHeadingCodePoints > 120 || maxSectionBytes < 1 || maxDocumentBytes < maxSectionBytes
                || maxPatches < 1 || maxExtractionInputBytes < 1 || maxExtractionOutputBytes < 1
                || indexDescriptionCodePoints < 1)
            throw new IllegalArgumentException("Invalid document memory configuration");
        for (Duration duration : List.of(idleTimeout, contextTtl, contentRetention, llmTimeout, taskLease, runTimeout))
            if (duration.isZero() || duration.isNegative())
                throw new IllegalArgumentException("Memory durations must be positive");
        if (taskLease.compareTo(llmTimeout.plusSeconds(5)) <= 0 || maxAttempts < 1 || maxRecentTurns < 0
                || maxQuestionBytes < 1 || summaryBytes < 1 || memoryBytes < 1
                || maxInputBytes < 2 * (maxQuestionBytes + summaryBytes + memoryBytes + 1024))
            throw new IllegalArgumentException("Invalid memory limits or lease; leave space for context and tool messages");
    }
}
