package com.yizhaoqi.smartpai.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.tool.WebPageTool;
import com.yizhaoqi.smartpai.agent.tool.WebSearchTool;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.*;

class WebConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties({WebProperties.class, AiProperties.class})
    @Import({PublicWebHttpClient.class, WebResearchClient.class, WebSearchTool.class, WebPageTool.class})
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    }

    @Test void webToolsAreAbsentByDefaultAndRegisteredWhenExplicitlyEnabled() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(WebSearchTool.class).isEmpty());
            assertTrue(context.getBeansOfType(WebPageTool.class).isEmpty());
        });
        runner.withPropertyValues("ai.web.enabled=true", "ai.web.api-key=test").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(WebSearchTool.class).size());
            assertEquals(1, context.getBeansOfType(WebPageTool.class).size());
            assertEquals("test", context.getBean(WebProperties.class).getApiKey());
        });
    }

    @Test void rejectsUnsafeSearchEndpointAndUnboundedConfiguration() {
        runner.withPropertyValues("ai.web.search-url=http://example.com/search").run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("ai.web.max-response-bytes=999999999").run(context -> assertNotNull(context.getStartupFailure()));
    }
}
