package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Admission and snapshot limits for connection-independent Work answers. */
@Data
@Component
@ConfigurationProperties(prefix="ai.chat")
public class ChatRuntimeProperties {
    private int maxConcurrentRunsPerUser=3;
    private int maxConcurrentRuns=16;
    private int maxBufferBytes=2*1024*1024;
    @jakarta.annotation.PostConstruct void validate() {
        if(maxConcurrentRunsPerUser<1 || maxConcurrentRuns<maxConcurrentRunsPerUser || maxBufferBytes<1024)
            throw new IllegalArgumentException("Invalid chat runtime limits");
    }
}
