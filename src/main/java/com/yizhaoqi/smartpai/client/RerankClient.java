package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.RetrievalProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gte-rerank 文本重排客户端。
 *
 * <p>重排只负责给候选打分排序，不参与召回与权限判断。调用超时、HTTP 错误、
 * 响应格式异常等任何失败都返回 {@code null}，由调用方退回 RRF 结果，
 * 因此重排序属于“增益项”，不会成为检索的故障点。</p>
 */
@Component
public class RerankClient {

    private static final Logger logger = LoggerFactory.getLogger(RerankClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024;

    /** 重排返回项：候选下标 + 相关度分数 */
    public record RerankHit(int index, double score) {
    }

    private final RetrievalProperties properties;
    private final WebClient webClient;
    private final String apiKey;

    public RerankClient(RetrievalProperties properties,
                        @Value("${embedding.api.key:}") String embeddingKey) {
        this.properties = properties;
        String configuredKey = properties.getRerank().getApiKey();
        this.apiKey = isBlank(configuredKey) ? (embeddingKey == null ? "" : embeddingKey.trim()) : configuredKey.trim();
        this.webClient = WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                        .build())
                .build();
    }

    @PostConstruct
    public void logConfiguration() {
        RetrievalProperties.Rerank config = properties.getRerank();
        if (!config.isEnabled()) {
            logger.info("重排序未启用，检索结果直接使用 RRF 排序");
        } else if (isBlank(config.getUrl())) {
            logger.warn("重排序已启用但未配置 ai.retrieval.rerank.url，检索结果直接使用 RRF 排序");
        } else if (isBlank(apiKey)) {
            logger.warn("重排序缺少 API Key（ai.retrieval.rerank.api-key 与 embedding.api.key 均为空），检索结果直接使用 RRF 排序");
        } else {
            logger.info("重排序已启用 - 模型: {}, 候选上限: {}, 超时: {}ms",
                    config.getModel(), config.getTopN(), config.getTimeout().toMillis());
        }
    }

    /** 配置完整时才真正发起重排请求 */
    public boolean available() {
        RetrievalProperties.Rerank config = properties.getRerank();
        return config.isEnabled() && !isBlank(config.getUrl()) && !isBlank(apiKey);
    }

    /**
     * 对候选文档重排。
     *
     * @param query     用户查询
     * @param documents 候选文本，顺序即候选下标
     * @param topN      期望返回的条数
     * @return 按相关度降序的重排结果；不可用、超时或任何失败时返回 {@code null}
     */
    public List<RerankHit> rerank(String query, List<String> documents, int topN) {
        RetrievalProperties.Rerank config = properties.getRerank();
        if (!available() || documents == null || documents.isEmpty()) {
            return null;
        }
        Duration timeout = config.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofMillis(1500);
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", documents);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("return_documents", false);
            parameters.put("top_n", Math.max(1, Math.min(topN, documents.size())));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("input", input);
            body.put("parameters", parameters);

            String response = webClient.post()
                    .uri(config.getUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();
            return parse(response, documents.size());
        } catch (Exception e) {
            logger.warn("重排序调用失败，退回 RRF 结果 - 模型: {}, 候选数: {}, 原因: {}",
                    config.getModel(), documents.size(), e.toString());
            return null;
        }
    }

    /**
     * 解析 DashScope 风格的文本重排响应：
     * <pre>{"output":{"results":[{"index":0,"relevance_score":0.99}]}}</pre>
     *
     * @return 降序的重排结果；响应结构不符合预期时返回 {@code null}，越界下标直接丢弃
     */
    static List<RerankHit> parse(String response, int documentCount) throws IOException {
        if (isBlank(response)) {
            return null;
        }
        JsonNode results = MAPPER.readTree(response).path("output").path("results");
        if (!results.isArray()) {
            return null;
        }
        List<RerankHit> hits = new ArrayList<>(results.size());
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= documentCount) {
                logger.warn("丢弃越界的重排结果 - index: {}, 候选数: {}", index, documentCount);
                continue;
            }
            hits.add(new RerankHit(index, item.path("relevance_score").asDouble(0d)));
        }
        hits.sort((left, right) -> Double.compare(right.score(), left.score()));
        return hits;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
