package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 嵌入向量生成客户端
@Component
public class EmbeddingClient {

    @Value("${embedding.api.model}")
    private String modelId;

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    @Value("${embedding.api.dimension:2048}")
    private int dimension;

    @Value("${embedding.api.url}")
    private String apiUrl;

    @Value("${embedding.api.key}")
    private String apiKey;

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(WebClient embeddingWebClient, ObjectMapper objectMapper) {
        this.webClient = embeddingWebClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        logger.info("EmbeddingClient 初始化 - 模型: {}, 批次大小: {}, 维度: {}, API地址: {}",
                modelId, batchSize, dimension, apiUrl);

        // 验证API key格式
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.startsWith("sk-") == false) {
            logger.warn("⚠️ API密钥格式可能无效，当前值前缀: {}",
                    apiKey == null ? "null" : apiKey.substring(0, Math.min(10, apiKey.length())));
        }
    }

    /**
     * 调用通义千问 API 生成向量
     * @param texts 输入文本列表
     * @return 对应的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        try {
            logger.info("开始生成向量，文本数量: {}", texts.size());

            List<float[]> all = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                logger.debug("调用向量 API, 批次: {}-{} (size={})", start, end - 1, sub.size());
                String response = callApiOnce(sub);
                all.addAll(parseVectors(response));
            }
            logger.info("成功生成向量，总数量: {}", all.size());
            return all;
        } catch (WebClientResponseException e) {
            // 提供详细的API响应错误信息
            logger.error("API调用失败 - 状态码: {}, 响应: {}, 请求头: {}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    e.getHeaders());
            throw new RuntimeException(String.format(
                    "向量生成失败 - API错误: HTTP %d - %s",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            logger.error("调用向量化 API 失败: {} - 类型: {}",
                    e.getMessage(),
                    e.getClass().getSimpleName(), e);
            throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
        }
    }

    private String callApiOnce(List<String> batch) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelId);
        requestBody.put("input", batch);
        requestBody.put("dimension", dimension);  // 直接在根级别设置dimension
        requestBody.put("encoding_format", "float");  // 添加编码格式

        logger.debug("发送嵌入请求 - 模型: {}, 维度: {}, 批次大小: {}, 文本预览: {}",
                modelId, dimension, batch.size(),
                batch.isEmpty() ? "空" : batch.get(0).substring(0, Math.min(50, batch.get(0).length())) + "...");

        return webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException)
                        .doBeforeRetry(signal -> logger.warn("重试API调用 - 尝试: {}, 错误: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .block(Duration.ofSeconds(30));
    }

    private List<float[]> parseVectors(String response) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");  // 兼容模式下使用data字段
        if (data == null || !data.isArray()) {
            throw new RuntimeException("API 响应格式错误: data 字段不存在或不是数组");
        }
        
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }
}
