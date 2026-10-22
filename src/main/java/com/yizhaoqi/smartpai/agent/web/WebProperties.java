package com.yizhaoqi.smartpai.agent.web;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "ai.web")
public class WebProperties {
    private boolean enabled = false;
    @lombok.ToString.Exclude private String apiKey = "";
    private String searchUrl = "https://api.search.brave.com/res/v1/web/search";
    private Duration timeout = Duration.ofSeconds(6);
    private int maxResponseBytes = 1_048_576;
    private int maxPageCharacters = 4000;
    private int maxResults = 5;

    @PostConstruct
    public void validate() {
        if (timeout == null || timeout.toMillis() < 1 || timeout.toSeconds() > 30
                || maxResponseBytes < 1024 || maxResponseBytes > 5_242_880
                || maxPageCharacters < 500 || maxPageCharacters > 20_000 || maxResults < 1 || maxResults > 10)
            throw new IllegalArgumentException("invalid ai.web limits");
        var uri = PublicWebHttpClient.validateUrl(searchUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("ai.web.search-url requires HTTPS");
    }
}
