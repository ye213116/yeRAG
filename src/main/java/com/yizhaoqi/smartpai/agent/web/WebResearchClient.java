package com.yizhaoqi.smartpai.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.utils.URIBuilder;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Provider-specific search and bounded, text-only page extraction. */
@Component
public class WebResearchClient {
    public record SearchHit(String title, String url, String snippet) { }
    public record Page(String title, String url, String text, boolean truncated, int nextOffset) { }
    private final PublicWebHttpClient http;
    private final WebProperties config;
    private final ObjectMapper mapper;

    public WebResearchClient(PublicWebHttpClient http, WebProperties config, ObjectMapper mapper) {
        this.http = http;
        this.config = config;
        this.mapper = mapper;
    }

    public List<SearchHit> search(String query, int count) throws Exception {
        if (!config.isEnabled() || config.getApiKey().isBlank()) throw new IOException("web search is not configured");
        URI uri = new URIBuilder(config.getSearchUrl()).addParameter("q", query)
                .addParameter("count", Integer.toString(count)).addParameter("text_decorations", "false")
                .addParameter("result_filter", "web").build();
        var response = http.get(uri, Map.of("Accept", "application/json", "X-Subscription-Token", config.getApiKey()), false);
        var root = mapper.readTree(response.body());
        var results = root.path("web").path("results");
        if (!results.isMissingNode() && !results.isArray()) throw new IOException("invalid search response");
        List<SearchHit> hits = new ArrayList<>();
        for (var item : results) {
            String url = item.path("url").asText("");
            try { PublicWebHttpClient.validateUrl(url); }
            catch (IllegalArgumentException unsupported) { continue; }
            hits.add(new SearchHit(plain(item.path("title").asText(url), 200), url,
                    plain(item.path("description").asText(""), 600)));
            if (hits.size() >= count) break;
        }
        return hits;
    }

    public Page read(String url, int offset) throws IOException {
        if (!config.isEnabled()) throw new IOException("web access is disabled");
        var response = http.get(PublicWebHttpClient.validateUrl(url), Map.of("Accept", "text/html,text/plain"), true);
        String type = response.contentType().toLowerCase(Locale.ROOT);
        String text;
        String title;
        if (type.startsWith("text/html") || type.startsWith("application/xhtml+xml")) {
            var document = Jsoup.parse(new ByteArrayInputStream(response.body()), null, response.url().toString());
            title = document.title();
            document.select("script,style,noscript,nav,footer,header,iframe,form,svg").remove();
            var main = document.selectFirst("main,article,[role=main]");
            text = (main == null ? document.body() : main).wholeText().trim();
        } else if (type.startsWith("text/plain")) {
            var contentType = org.apache.http.entity.ContentType.parse(response.contentType());
            var charset = contentType.getCharset();
            text = new String(response.body(), charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset);
            title = response.url().getHost();
        } else throw new IOException("only HTML and plain text pages are supported");
        if (text.isBlank()) throw new IOException("page has no readable text");
        int start = Math.min(offset, text.length());
        int end = Math.min(text.length(), start + config.getMaxPageCharacters());
        return new Page(plain(title, 200), response.url().toString(), text.substring(start, end), end < text.length(), end);
    }

    private static String plain(String text, int max) {
        String value = Jsoup.parse(text).text();
        return value.substring(0, Math.min(value.length(), max));
    }
}
