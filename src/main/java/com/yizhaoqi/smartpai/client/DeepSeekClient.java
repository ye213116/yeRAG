package com.yizhaoqi.smartpai.client;

import com.yizhaoqi.smartpai.agent.AgentLlmClient;
import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.agent.model.ToolCall;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;

@Service
public class DeepSeekClient implements AgentLlmClient {

    private final WebClient webClient;
    private final String model;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.yizhaoqi.smartpai.memory.MemoryProperties memoryProperties;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);
    
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        
        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        
        this.webClient = builder.build();
        this.model = model;
        this.aiProperties = aiProperties;
    }

    @Override
    public Mono<AgentDecision> decide(List<AgentMessage> messages, List<Map<String, Object>> tools) {
        Map<String, Object> request = buildAgentRequest(messages, false);
        if (!tools.isEmpty()) { request.put("tools", tools); request.put("tool_choice", "auto"); }
        checkMemoryBudget(request);
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    try { return parseDecision(objectMapper, body); }
                    catch (Exception e) { throw new IllegalArgumentException("Invalid tool-calling response", e); }
                });
    }

    @Override
    public Flux<String> streamFinal(List<AgentMessage> messages) {
        Map<String, Object> request = buildAgentRequest(messages, true);
        checkMemoryBudget(request);
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .map(this::extractContent)
                .filter(content -> !content.isEmpty());
    }

    private Map<String, Object> buildAgentRequest(List<AgentMessage> messages, boolean stream) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", messages.stream().map(this::toWireMessage).toList());
        request.put("stream", stream);
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) request.put("temperature", gen.getTemperature());
        if (gen.getTopP() != null) request.put("top_p", gen.getTopP());
        if (gen.getMaxTokens() != null) request.put("max_tokens", gen.getMaxTokens());
        return request;
    }

    private Map<String, Object> toWireMessage(AgentMessage message) {
        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("role", message.role());
        if (message.content() != null) wire.put("content", message.content());
        if (message.toolCallId() != null) wire.put("tool_call_id", message.toolCallId());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(), "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.arguments()))).toList());
        }
        return wire;
    }

    static AgentDecision parseDecision(ObjectMapper mapper, String body) throws Exception {
        JsonNode choices = mapper.readTree(body).path("choices");
        if (!choices.isArray() || choices.isEmpty()) throw new IllegalArgumentException("Response has no choices");
        JsonNode message = choices.path(0).path("message");
        String content = message.path("content").isNull() ? null : message.path("content").asText(null);
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            if (!"function".equals(call.path("type").asText()) || !call.path("id").isTextual()
                    || call.path("id").asText().isBlank() || !function.path("name").isTextual()
                    || function.path("name").asText().isBlank() || !function.path("arguments").isTextual()) {
                throw new IllegalArgumentException("Malformed tool call");
            }
            calls.add(new ToolCall(call.path("id").textValue(), function.path("name").textValue(),
                    function.path("arguments").textValue()));
        }
        return new AgentDecision(content, calls);
    }

    private String extractContent(String raw) {
        String chunk = raw == null ? "" : raw.trim();
        if (chunk.startsWith("data:")) chunk = chunk.substring(5).trim();
        if (chunk.isEmpty() || "[DONE]".equals(chunk)) return "";
        try {
            JsonNode root = objectMapper.readTree(chunk);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) throw new IllegalArgumentException("SSE frame has no choices");
            JsonNode content = choices.path(0).path("delta").path("content");
            return content.isTextual() ? content.textValue() : "";
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid streaming response frame", e);
        }
    }
    
    public void streamResponse(String userMessage, 
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError) {
        streamResponse(userMessage, context, history, onChunk, onError, () -> {});
    }

    public void streamResponse(String userMessage,
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError,
                             Runnable onComplete) {
        streamResponseDisposable(userMessage, context, history, onChunk, onError, onComplete);
    }

    public Disposable streamResponseDisposable(String userMessage,
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError,
                             Runnable onComplete) {
        
        Map<String, Object> request = buildRequest(userMessage, context, history);
        checkMemoryBudget(request);
        
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processChunk(chunk, onChunk),
                    onError,
                    onComplete
                );
    }
    
    private void checkMemoryBudget(Map<String,Object> request) {
        if (memoryProperties == null) return;
        try {
            if (objectMapper.writeValueAsBytes(request).length > memoryProperties.getMaxInputBytes())
                throw new IllegalArgumentException("Model input budget exceeded");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Cannot encode model input",e); }
    }

    private Map<String, Object> buildRequest(String userMessage,
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息长度：{}，上下文长度：{}，历史消息数：{}",
                   userMessage == null ? 0 : userMessage.length(),
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }
    
    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }
    
    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }
            
            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                               .path(0)
                               .path("delta")
                               .path("content")
                               .asText("");
            
            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }
}
