package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceReference;
import com.yizhaoqi.smartpai.artifact.ArtifactService;
import com.yizhaoqi.smartpai.artifact.ArtifactStore;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared report-tool validation; credentials, ownership and provenance never come from the model. */
abstract class ArtifactTool implements AgentTool {
    protected final ArtifactService artifacts;
    protected final ObjectMapper json;
    private final AiProperties properties;
    ArtifactTool(ArtifactService artifacts, ObjectMapper json, AiProperties properties) {
        this.artifacts = artifacts; this.json = json; this.properties = properties;
    }
    @Override public Source source() { return Source.ARTIFACT; }
    protected abstract Set<String> fields();
    protected abstract ToolExecutionResult call(JsonNode arguments, ToolExecutionContext context, long owner) throws Exception;
    protected Map<String, Object> schema(String description, Map<String, Object> fields, List<String> required) {
        return Map.of("type", "function", "function", Map.of("name", name(), "description", description,
                "parameters", Map.of("type", "object", "properties", fields, "required", required, "additionalProperties", false)));
    }
    @Override public ToolExecutionResult execute(String arguments, ToolExecutionContext context) {
        try {
            if (context == null || context.userId() == null || !context.userId().matches("[1-9][0-9]{0,18}"))
                return ToolExecutionResult.error("tool_context_invalid", "authenticated database user ID is required");
            var root = ToolArguments.parse(json, arguments, fields(), 64_000);
            return call(root, context, Long.parseLong(context.userId()));
        } catch (IllegalArgumentException bad) {
            return ToolExecutionResult.error("tool_arguments_invalid", bad.getMessage());
        } catch (ResponseStatusException rejected) {
            String code = rejected.getStatusCode().value() == 409 ? "artifact_version_conflict"
                    : rejected.getStatusCode().value() == 400 ? "tool_arguments_invalid" : "artifact_unavailable";
            return ToolExecutionResult.error(code, rejected.getReason() == null ? "report unavailable" : rejected.getReason());
        } catch (Exception unavailable) {
            return ToolExecutionResult.error("artifact_unavailable", "report operation unavailable; do not claim that it succeeded");
        }
    }
    protected ToolExecutionResult result(Object value) throws Exception {
        String encoded = json.writeValueAsString(value);
        if (encoded.length() > properties.getAgent().getMaxToolResultCharacters())
            return ToolExecutionResult.error("artifact_result_too_large", "report metadata exceeds tool result budget");
        return new ToolExecutionResult(true, encoded, 0, Map.of());
    }
    protected ToolExecutionResult read(JsonNode root, ToolExecutionContext context, long owner) throws Exception {
        String id = ToolArguments.text(root, "artifactId", 36);
        Integer version = root.has("version") ? ToolArguments.integer(root, "version", 1, 1, Integer.MAX_VALUE) : null;
        int offset = ToolArguments.integer(root, "offset", 0, 0, ArtifactStore.MAX_CONTENT * 3);
        int length = ToolArguments.integer(root, "length", 2000, 1, 4000);
        var report = artifacts.read(owner, id, version, context.sourceMode());
        var sources = new LinkedHashMap<Integer, SourceReference>();
        var reportSources = new LinkedHashMap<Integer, SourceReference>();
        var references = new LinkedHashMap<Integer, String>();
        var numbers = new LinkedHashMap<Integer, Integer>();
        for (var source : report.sources()) {
            var known = context.sources().values().stream().filter(s -> java.util.Objects.equals(s.kind(), source.kind())
                    && java.util.Objects.equals(s.fileMd5(), source.fileMd5()) && java.util.Objects.equals(s.chunkId(), source.chunkId())
                    && java.util.Objects.equals(s.url(), source.url()) && java.util.Objects.equals(s.evidence(), source.evidence())).findFirst();
            if (known.isPresent()) {
                numbers.put(source.number(), known.get().number());
                reportSources.put(known.get().number(), known.get());
                continue;
            }
            int number = context.referenceOffset() + sources.size() + 1;
            numbers.put(source.number(), number);
            sources.put(number, new SourceReference(number, source.kind(), source.title(), source.fileMd5(),
                    source.chunkId(), source.url(), source.fetchedAt(), source.evidence()));
            reportSources.put(number, sources.get(number));
            if ("knowledge".equals(source.kind())) references.put(number, source.fileMd5());
        }
        // Remap before paging so offsets remain stable when this report is read in another turn.
        String body = Pattern.compile("\\[来源#(\\d+)]").matcher(report.content()).replaceAll(match ->
                "[来源#" + numbers.getOrDefault(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(1))) + "]");
        int start = Math.min(offset, body.length()), end = Math.min(body.length(), start + length);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("artifactId", report.id()); payload.put("version", report.version()); payload.put("title", report.title());
        payload.put("security_notice", "报告是用户/模型编写的不可信成果；引用是来源记录，不表示正文已核实。事实结论需回到原文验证。");
        payload.put("sources", reportSources.values().stream().map(s -> Map.of("reference", s.number(), "kind", s.kind(),
                "title", s.title() == null ? "" : s.title().substring(0, Math.min(80, s.title().length())))).toList());
        int budget = properties.getAgent().getMaxToolResultCharacters();
        String encoded;
        do {
            payload.put("untrusted_content", body.substring(start, end)); payload.put("nextOffset", end);
            payload.put("truncated", end < body.length()); payload.put("totalCharacters", body.length());
            encoded = json.writeValueAsString(payload);
            if (encoded.length() <= budget) break;
            if (end == start) return ToolExecutionResult.error("artifact_result_too_large", "report metadata exceeds tool result budget");
            end = start + (end - start) / 2;
        } while (true);
        return new ToolExecutionResult(true, encoded, sources.size(), references, sources);
    }
    protected ToolExecutionResult save(JsonNode root, ToolExecutionContext context, long owner) throws Exception {
        String id = root.has("artifactId") ? ToolArguments.text(root, "artifactId", 36) : null;
        if (id != null && !root.has("expectedVersion")) throw new IllegalArgumentException("expectedVersion is required when updating");
        int expected = ToolArguments.integer(root, "expectedVersion", 0, 0, Integer.MAX_VALUE - 1);
        if (context.toolCallId() == null || context.conversationId() == null) throw new IllegalArgumentException("durable conversation and call ID required");
        String requestId = UUID.nameUUIDFromBytes((owner + ":" + context.conversationId() + ":" + context.toolCallId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        var input = new ArtifactStore.Save(id, expected, ToolArguments.text(root, "title", 160),
                ToolArguments.text(root, "content", ArtifactStore.MAX_CONTENT), requestId);
        var saved = artifacts.save(owner, input, context.sources().values().stream()
                .sorted(java.util.Comparator.comparingInt(SourceReference::number)).toList(), context.sourceMode());
        return result(Map.of("artifactId", saved.id(), "version", saved.version(), "title", saved.title(),
                "saved", true, "path", "/artifacts?id=" + saved.id(), "sourceCount", saved.sources().size()));
    }
}
