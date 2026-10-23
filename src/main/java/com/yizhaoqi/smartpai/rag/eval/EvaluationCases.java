package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** One JSONL contract for online capture and offline replay, including the original v1 id alias. */
final class EvaluationCases {
    private EvaluationCases() {}

    static List<ObjectNode> read(Path path, ObjectMapper json) throws Exception {
        List<ObjectNode> cases = new ArrayList<>();
        Set<List<String>> keys = new HashSet<>();
        try (var lines = Files.lines(path)) {
            for (String line : lines.toList()) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (!(json.readTree(line) instanceof ObjectNode sample))
                    throw new IllegalArgumentException("Evaluation case must be a JSON object");
                String id = sample.path("caseId").asText(sample.path("id").asText());
                if (id.isBlank() || (sample.has("id") && !id.equals(sample.path("id").asText())))
                    throw new IllegalArgumentException("Missing or conflicting id/caseId");
                String variant = sample.path("variant").asText("baseline");
                if (variant.isBlank() || !keys.add(List.of(id, variant)))
                    throw new IllegalArgumentException("Duplicate or invalid case/variant: " + id + "/" + variant);
                sample.put("caseId", id).put("variant", variant);
                cases.add(sample);
            }
        }
        if (cases.isEmpty()) throw new IllegalArgumentException("Evaluation dataset is empty");
        return cases;
    }
}
