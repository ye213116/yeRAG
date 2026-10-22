package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.web.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebToolsTest {
    final WebResearchClient client = mock(WebResearchClient.class);
    final WebProperties web = new WebProperties();
    final AiProperties ai = new AiProperties();
    final ObjectMapper mapper = new ObjectMapper();
    final WebSearchTool search = new WebSearchTool(client, web, ai, mapper);
    final WebPageTool read = new WebPageTool(client, mapper, ai);

    @Test void labelsSearchAsSnippetAndDoesNotCreateKnowledgeFileReferences() throws Exception {
        web.setEnabled(true); web.setApiKey("test");
        when(client.search("公开问题", 5)).thenReturn(List.of(new WebResearchClient.SearchHit("官方", "https://example.com/", "摘要")));
        var result = search.execute("{\"query\":\"公开问题\"}", new ToolExecutionContext("42", 3));
        assertTrue(result.success()); assertTrue(result.references().isEmpty());
        assertEquals("search_snippet", result.sources().get(4).evidence());
        assertNotNull(result.sources().get(4).fetchedAt());
    }

    @Test void retainsOnlySnippetEvidenceWhenPageReadingFails() throws Exception {
        when(client.read("https://example.com", 0)).thenThrow(new java.io.IOException("private diagnostic"));
        var result = read.execute("{\"url\":\"https://example.com\"}", new ToolExecutionContext("42"));
        assertFalse(result.success()); assertTrue(result.sources().isEmpty());
        assertTrue(result.content().contains("snippet-only")); assertFalse(result.content().contains("private diagnostic"));
    }

    @Test void validatesArgumentsBeforeMakingRequests() {
        for (String json : List.of("{\"query\":\"q\",\"userId\":\"victim\"}", "{\"query\":\"q\",\"count\":100}",
                "{\"query\":\"q\",\"query\":\"other\"}", "{\"query\":\"q\"} {}"))
            assertFalse(search.execute(json, new ToolExecutionContext("42")).success());
        for (String json : List.of("{\"url\":\"file:///etc/passwd\"}", "{\"url\":\"https://example.com\",\"offset\":-1}"))
            assertFalse(read.execute(json, new ToolExecutionContext("42")).success());
        verifyNoInteractions(client);
    }

    @Test void limitsEscapedPageResultsAndReturnsCorrectContinuationOffset() throws Exception {
        String text = "\"\\\n".repeat(3000);
        when(client.read("https://example.com", 100)).thenReturn(new WebResearchClient.Page("Title", "https://example.com", text, true, 100 + text.length()));
        var result = read.execute("{\"url\":\"https://example.com\",\"offset\":100}", new ToolExecutionContext("42", 2));
        assertTrue(result.success()); assertTrue(result.content().length() <= 6000);
        var root = mapper.readTree(result.content());
        assertEquals(100 + root.path("untrusted_content").asText().length(), root.path("next_offset").asInt());
        assertTrue(root.path("truncated").asBoolean());
        assertEquals("page_text", result.sources().get(3).evidence());
    }
}
