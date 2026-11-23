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
        /** Separate budget so evidence collection cannot consume report persistence calls. */
        private int maxArtifactToolCalls = 4;
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
        private Profiles profiles = new Profiles();
    }

    @Data
    public static class Profiles {
        private Profile work = new Profile();
        private Profile coding = codingDefaults();
        private static Profile codingDefaults() {
            Profile profile = new Profile();
            profile.setEnabled(false);
            profile.setAllowedTools(new java.util.LinkedHashSet<>(java.util.List.of(
                    "search_knowledge_base", "read_knowledge_document", "search_web", "read_web_page",
                    "read_memory", "list_memory_sections", "search_conversation_history", "read_conversation_turns",
                    "list_files", "search_code", "read_file", "apply_patch", "execute_command", "git_diff")));
            profile.setMaxToolCalls(40);
            profile.setMaxDecisions(50);
            profile.setLlmTimeout(Duration.ofSeconds(90));
            profile.setToolTimeout(Duration.ofSeconds(135));
            profile.setTotalTimeout(Duration.ofMinutes(15));
            return profile;
        }
    }

    @Data
    public static class Profile {
        private boolean enabled = true;
        private java.util.Set<String> allowedTools = new java.util.LinkedHashSet<>(java.util.List.of(
                "search_knowledge_base", "read_knowledge_document", "search_web", "read_web_page",
                "read_memory", "list_memory_sections", "search_conversation_history", "read_conversation_turns",
                "list_artifacts", "read_artifact", "save_artifact"));
        private Integer maxToolCalls;
        private Integer maxDecisions;
        private Duration llmTimeout;
        private Duration toolTimeout;
        private Duration totalTimeout;
    }

    @Data
    public static class Planning {
        private boolean enabled = true;
        /** Includes the final synthesis task; bounded to 3–6 at runtime. */
        private int maxSteps = 4;
        private Duration timeout = Duration.ofSeconds(5);
    }
}
