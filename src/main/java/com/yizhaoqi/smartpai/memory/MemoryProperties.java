package com.yizhaoqi.smartpai.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Duration;

@Data
@Configuration
@EnableScheduling
@ConfigurationProperties(prefix = "ai.memory")
public class MemoryProperties {
    // Opt-in: apply the SQL migration before enabling on an existing installation.
    private boolean enabled = false;
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
    private int maxRecentTurns = 10;
    private int maxMemories = 30;
    private int maxInputBytes = 48000;
    private int summaryBytes = 4000;
    private int memoryBytes = 3200;
    private int maxQuestionBytes = 12000;
    private String storageMode = "slot";
    private boolean legacySlotRecall = true;
    private int workerConcurrency = 2;
    private int maxHeadingCodePoints = 80;
    private int maxSectionBytes = 8192;
    private int maxDocumentBytes = 32768;
    private int maxPatches = 8;
    private int maxExtractionInputBytes = 48000;
    private int maxExtractionOutputBytes = 16384;
    private int indexDescriptionCodePoints = 120;
    public boolean documentMode() { return "document".equals(storageMode); }
    private double minConfidence = 0.9;
    private Duration auditEvidenceRetention = Duration.ofDays(365);

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!java.util.Set.of("slot", "document").contains(storageMode == null ? "" : storageMode)
                || workerConcurrency < 1 || workerConcurrency > 32 || maxHeadingCodePoints < 1
                || maxHeadingCodePoints > 120 || maxSectionBytes < 1 || maxDocumentBytes < maxSectionBytes
                || maxPatches < 1 || maxExtractionInputBytes < 1 || maxExtractionOutputBytes < 1
                || indexDescriptionCodePoints < 1)
            throw new IllegalArgumentException("Invalid document memory configuration");
        for(Duration d : java.util.List.of(idleTimeout,contextTtl,contentRetention,llmTimeout,taskLease,runTimeout,auditEvidenceRetention))
            if(d.isZero() || d.isNegative()) throw new IllegalArgumentException("Memory durations must be positive");
        if(taskLease.compareTo(llmTimeout.plusSeconds(5))<=0 || maxAttempts<1 || maxRecentTurns<1 || maxMemories<1
                || maxQuestionBytes<1 || summaryBytes<1 || memoryBytes<1
                || maxInputBytes < 2*(maxQuestionBytes+summaryBytes+memoryBytes+1024)
                || !Double.isFinite(minConfidence) || minConfidence<0 || minConfidence>1)
            throw new IllegalArgumentException("Invalid memory limits or lease; leave space for context and tool messages");
    }
}
