package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeSearchTool implements AgentTool {
    public static final String NAME = "search_knowledge_base";
    private final RagPipeline pipeline;
    private final ObjectMapper objectMapper;
    private final AiProperties.Agent config;

    public KnowledgeSearchTool(RagPipeline pipeline, ObjectMapper objectMapper, AiProperties properties) {
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.config = properties.getAgent();
    }

    @Override public String name() { return NAME; }
    @Override public Source source() { return Source.KNOWLEDGE; }

    @Override
    public Map<String, Object> definition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "需要在知识库中检索的问题或关键词"),
                        "topK", Map.of("type", "integer", "minimum", 1, "maximum", config.getMaxTopK())),
                "required", List.of("query"),
                "additionalProperties", false);
        return Map.of("type", "function", "function", Map.of(
                "name", NAME,
                "description", "检索当前用户有权限访问的企业知识库。内部资料或用户文档问题必须使用此工具。",
                "parameters", parameters));
    }

    @Override
    public ToolExecutionResult execute(String arguments, ToolExecutionContext context) {
        return executeAsync(arguments,context).block(config.getToolTimeout());
    }

    @Override
    public Mono<ToolExecutionResult> executeAsync(String arguments, ToolExecutionContext context) {
        return Mono.deferContextual(view -> {
            final String query;
            final int topK;
            try {
                JsonNode root=objectMapper.readTree(arguments);
                if(root==null || !root.isObject() || !hasOnlyAllowedFields(root) || !root.path("query").isTextual()
                        || (root.has("topK") && !root.path("topK").isIntegralNumber()))
                    return Mono.just(ToolExecutionResult.error("tool_arguments_invalid","only query and integer topK are allowed"));
                query=root.path("query").asText().trim();
                if(query.isBlank() || query.length()>config.getMaxQueryLength())
                    return Mono.just(ToolExecutionResult.error("invalid_query","query length is invalid"));
                topK=root.has("topK")?root.path("topK").bigIntegerValue().max(java.math.BigInteger.ONE)
                    .min(java.math.BigInteger.valueOf(config.getMaxTopK())).intValue():config.getDefaultTopK();
            } catch(Exception invalid) { return Mono.just(ToolExecutionResult.error("tool_arguments_invalid","arguments must be valid JSON")); }
            if(context==null || context.userId()==null || context.userId().isBlank())
                return Mono.just(ToolExecutionResult.error("tool_context_invalid","authenticated user context is required"));
            var request=pipeline.request(context.userId(),query,List.of(),Mode.RETRIEVE_ONLY,topK,context.referenceOffset());
            return pipeline.run(request,new RagObserver(){})
                .map(result -> new ToolExecutionResult(true,result.context().observation(),result.context().passages().size(),result.context().references(),RagSources.from(result.context(),context.referenceOffset())))
                .contextWrite(c -> c.put("rag.toolCallId",java.util.Objects.toString(context.toolCallId(),"")));
        });
    }

    private boolean hasOnlyAllowedFields(JsonNode root) {
        java.util.Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!"query".equals(name) && !"topK".equals(name)) return false;
        }
        return true;
    }

}
