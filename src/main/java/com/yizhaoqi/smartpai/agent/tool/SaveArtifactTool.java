package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.artifact.ArtifactService;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SaveArtifactTool extends ArtifactTool {
    public SaveArtifactTool(ArtifactService service, ObjectMapper json, AiProperties properties) { super(service, json, properties); }
    @Override public String name() { return "save_artifact"; }
    @Override protected Set<String> fields() { return Set.of("artifactId", "expectedVersion", "title", "content"); }
    @Override public Map<String, Object> definition() {
        return schema("仅在用户要求生成/保存/修改报告时保存完整成果。新建省略 artifactId；更新必须提供读取到的 expectedVersion，保留旧版。来源由服务端绑定，禁止编造引用。返回 saved=true 才表示保存成功。", Map.of(
                "artifactId", Map.of("type", "string", "format", "uuid"),
                "expectedVersion", Map.of("type", "integer", "minimum", 0),
                "title", Map.of("type", "string", "minLength", 1, "maxLength", 160),
                "content", Map.of("type", "string", "minLength", 1, "maxLength", 30000)), List.of("title", "content"));
    }
    @Override protected ToolExecutionResult call(JsonNode root, ToolExecutionContext context, long owner) throws Exception { return save(root, context, owner); }
}
