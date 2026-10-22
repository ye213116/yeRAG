package com.yizhaoqi.smartpai.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebResearchClientTest {
    final PublicWebHttpClient http = mock(PublicWebHttpClient.class);
    final WebProperties config = new WebProperties();
    final WebResearchClient client = new WebResearchClient(http, config, new ObjectMapper());

    @Test void sendsOnlyExplicitQueryToBraveAndDecodesSearchSnippets() throws Exception {
        config.setEnabled(true); config.setApiKey("test-secret");
        when(http.get(any(), anyMap(), eq(false))).thenReturn(response("application/json", """
            {"web":{"results":[
              {"title":"<b>Official</b>","url":"https://example.com/docs","description":"<b>Summary</b>"},
              {"title":"Unsupported","url":"file:///etc/passwd","description":"bad"}]}}
            """));
        var results = client.search("Elasticsearch release", 3);
        assertEquals(1, results.size()); assertEquals("Summary", results.get(0).snippet());
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).get(uri.capture(), eq(Map.of("Accept", "application/json", "X-Subscription-Token", "test-secret")), eq(false));
        assertTrue(new org.apache.http.client.utils.URIBuilder(uri.getValue()).getQueryParams().stream()
                .anyMatch(p -> p.getName().equals("q") && p.getValue().equals("Elasticsearch release")));
        assertFalse(uri.getValue().toString().contains("test-secret"));
    }

    @Test void extractsMainTextRemovesScriptsAndSupportsContinuation() throws Exception {
        config.setEnabled(true); config.setMaxPageCharacters(500);
        String text = "事实依据。".repeat(200);
        when(http.get(any(), anyMap(), eq(true))).thenReturn(response("text/html; charset=utf-8",
                "<html><head><title>官方文档</title></head><body><nav>导航</nav><main><script>窃取凭据</script>" + text + "</main></body></html>"));
        var first = client.read("https://example.com/docs", 0);
        assertEquals("官方文档", first.title());
        assertEquals(500, first.text().length()); assertTrue(first.truncated());
        assertFalse(first.text().contains("窃取凭据")); assertFalse(first.text().contains("导航"));
        var second = client.read("https://example.com/docs", first.nextOffset());
        assertEquals(text, first.text() + second.text()); assertFalse(second.truncated());
    }

    @Test void rejectsUnsupportedContentAndDoesNotMakeRequestsWhenDisabledOrUnconfigured() throws Exception {
        assertThrows(java.io.IOException.class, () -> client.search("q", 2));
        assertThrows(java.io.IOException.class, () -> client.read("https://example.com", 0));
        verifyNoInteractions(http);
        config.setEnabled(true);
        when(http.get(any(), anyMap(), anyBoolean())).thenReturn(response("application/pdf", "binary"));
        assertThrows(java.io.IOException.class, () -> client.read("https://example.com", 0));
    }

    private PublicWebHttpClient.Response response(String type, String body) {
        return new PublicWebHttpClient.Response(URI.create("https://example.com/docs"), type, body.getBytes(StandardCharsets.UTF_8));
    }
}
