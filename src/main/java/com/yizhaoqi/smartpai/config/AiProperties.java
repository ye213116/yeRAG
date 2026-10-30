package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();
    private Agent agent = new Agent();

    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 8192;
        /** nucleus top-p */
        private Double topP = 0.9;
    }

    @Data
    public static class Agent {
        private boolean enabled = true;
        private boolean fallbackEnabled = true;
        private int maxToolCalls = 8;
        private boolean queryRewriteEnabled = true;
        private Duration queryRewriteTimeout = Duration.ofSeconds(2);
        private int maxQueryLength = 500;
        private int defaultTopK = 5;
        private int maxTopK = 10;
        /** Resource ceiling; model context is governed by ai.rag token settings. */
        private int maxToolResultCharacters = 4 * 1024 * 1024;
        private Duration llmTimeout = Duration.ofSeconds(30);
        private Duration toolTimeout = Duration.ofSeconds(10);
        private Duration totalTimeout = Duration.ofSeconds(60);
        private Planning planning = new Planning();
    }

    @Data
    public static class Planning {
        private boolean enabled = true;
        /** Includes the final synthesis task; bounded to 3–6 at runtime. */
        private int maxSteps = 4;
        private Duration timeout = Duration.ofSeconds(5);
    }
}
