package com.yizhaoqi.smartpai.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties("ai.rag")
public class RagProperties {
    private boolean rewriteEnabled = true;
    private boolean rerankEnabled = true;
    private boolean mergeEnabled;
    private boolean legacyClipping;
    private int candidateK; // 0 means use ai.retrieval.recall-multiplier * topK
    private String modelProfile = "deepseek-v4.1-flash";
    private String tokenizerResource = "classpath:tokenizers/deepseek-v4/tokenizer.json.gz";
    private int contextTokens = 1_048_576;
    /** 0 uses the remaining model context instead of an independent small evidence ceiling. */
    private int evidenceTokens = 0;
    private int safetyTokens = 4096;
    private double compressionTriggerRatio = 0.95;
    private double rerankThreshold = 0;
    private Duration stageTimeout = Duration.ofSeconds(10);
    private String codeVersion = "unknown";
    private String corpusVersion = "unknown";
    private String evidenceBackend = "local";
    private String evidenceDirectory = System.getProperty("java.io.tmpdir") + "/yerag-evidence";
    private String evidenceBucket = "rag-evidence";
    private long maxEvidenceBytes = 8 * 1024 * 1024;
    private int retentionDays = 7;

    public void validate() {
        if (candidateK < 0 || candidateK > 10000 || evidenceTokens < 0
                || contextTokens < 1 || safetyTokens < 0 || stageTimeout.isNegative()
                || stageTimeout.isZero() || maxEvidenceBytes < 4096 || maxEvidenceBytes > 64L*1024*1024 || retentionDays < 1
                || !java.util.Set.of("local","minio").contains(evidenceBackend) || evidenceDirectory==null || evidenceDirectory.isBlank()
                || !Double.isFinite(rerankThreshold) || !Double.isFinite(compressionTriggerRatio)
                || compressionTriggerRatio <= 0 || compressionTriggerRatio > 1
                || tokenizerResource == null || tokenizerResource.isBlank()) throw new IllegalArgumentException("Invalid ai.rag configuration");
    }

    public int inputTokenLimit(int outputTokens) {
        long available = (long) contextTokens - outputTokens - safetyTokens;
        return (int) Math.max(0, Math.floor(available * compressionTriggerRatio));
    }
}
