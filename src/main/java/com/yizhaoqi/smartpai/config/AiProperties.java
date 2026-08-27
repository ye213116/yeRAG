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
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
    }

    @Data
    public static class Agent {
        private boolean enabled = true;
        private boolean fallbackEnabled = true;
        private int maxToolCalls = 3;
        private int maxQueryLength = 500;
        private int defaultTopK = 5;
        private int maxTopK = 10;
        private int maxToolResultCharacters = 6000;
        private Duration llmTimeout = Duration.ofSeconds(30);
        private Duration toolTimeout = Duration.ofSeconds(10);
        private Duration totalTimeout = Duration.ofSeconds(60);
    }
}
