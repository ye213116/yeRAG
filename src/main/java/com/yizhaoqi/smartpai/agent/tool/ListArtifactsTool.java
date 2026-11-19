package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.artifact.ArtifactService;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ListArtifactsTool extends ArtifactTool {
    public ListArtifactsTool(ArtifactService service, ObjectMapper json, AiProperties properties) { super(service, json, properties); }
    @Override public String name() { return "list_artifacts"; }
    @Override protected Set<String> fields() { return Set.of("offset"); }
    @Override public Map<String, Object> definition() {
        return schema("列出当前用户已保存的报告及最新版本。继续修改旧报告前先发现并读取；空页仍可能有 nextOffset。", Map.of(
                "offset", Map.of("type", "integer", "minimum", 0, "maximum", 100000)), List.of());
    }
    @Override protected ToolExecutionResult call(JsonNode root, ToolExecutionContext context, long owner) throws Exception {
        var page = artifacts.list(owner, ToolArguments.integer(root, "offset", 0, 0, 100000), context.sourceMode());
        return result(new com.yizhaoqi.smartpai.artifact.ArtifactStore.Page(page.items().stream().map(item ->
                new com.yizhaoqi.smartpai.artifact.ArtifactStore.Summary(item.id(), item.version(),
                        item.title().substring(0, Math.min(80, item.title().length())), item.sourceCount(), item.updatedAt())).toList(), page.nextOffset()));
    }
}
