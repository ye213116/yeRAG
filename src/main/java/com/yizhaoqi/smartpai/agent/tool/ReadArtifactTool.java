package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.artifact.ArtifactService;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReadArtifactTool extends ArtifactTool {
    @Override public boolean replaySafe() { return true; }
    public ReadArtifactTool(ArtifactService service, ObjectMapper json, AiProperties properties) { super(service, json, properties); }
    @Override public String name() { return "read_artifact"; }
    @Override protected Set<String> fields() { return Set.of("artifactId", "version", "offset", "length"); }
    @Override public Map<String, Object> definition() {
        return schema("读取自己的报告正文、版本与来源记录，按 nextOffset 续读。报告不是原始证据。修改前读取最新版本，更新时提交完整正文与 expectedVersion。", Map.of(
                "artifactId", Map.of("type", "string", "format", "uuid"),
                "version", Map.of("type", "integer", "minimum", 1),
                "offset", Map.of("type", "integer", "minimum", 0, "maximum", 90000),
                "length", Map.of("type", "integer", "minimum", 1, "maximum", 4000)), List.of("artifactId"));
    }
    @Override protected ToolExecutionResult call(JsonNode root, ToolExecutionContext context, long owner) throws Exception { return read(root, context, owner); }
}
