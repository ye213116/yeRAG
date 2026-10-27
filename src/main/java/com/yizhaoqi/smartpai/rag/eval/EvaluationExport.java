package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;
import static com.yizhaoqi.smartpai.rag.eval.EvaluationArtifacts.*;

/** Plain single-turn data for optional external scoring. No third-party framework runs implicitly. */
public final class EvaluationExport {
    private EvaluationExport() {}
    public static void write(ObjectMapper json,EvaluationArtifacts artifacts,EvaluationCases.Dataset dataset,List<ObjectNode> rows,Path output) throws Exception {
        Map<String,JsonNode> samples=new HashMap<>(); for(JsonNode sample:dataset.cases()) samples.put(sample.path("id").asText(),sample);
        List<String> exported=new ArrayList<>(),index=new ArrayList<>();
        for(JsonNode row:rows) {
            JsonNode sample=samples.get(row.path("id").asText());
            ObjectNode entry=json.createObjectNode().put("caseId",row.path("id").asText()).put("datasetHash",dataset.hash())
                .put("pipelineRunId",row.path("pipelineRunId").asText()).put("status","SKIPPED_INCOMPLETE_OR_UNVERIFIED");
            entry.set("humanReview",row.path("humanReview").deepCopy());
            if(sample.has("referenceAnswers")) entry.set("referenceAnswers",sample.path("referenceAnswers").deepCopy());
            if(row.path("deterministic").path("generationComplete").asBoolean()) {
                JsonNode pipeline=artifacts.read(row.path("pipelineRunId").asText());
                ObjectNode item=json.createObjectNode().put("user_input",sample.path("question").asText()).put("response",answer(pipeline))
                    .put("reference",sample.path("referenceAnswer").asText());
                ArrayNode contexts=item.putArray("retrieved_contexts");
                for(JsonNode passage:stage(pipeline,"ASSEMBLE").path("passages")) contexts.add(passage.path("text").asText());
                entry.put("status","EXPORTED").put("line",exported.size()+1); exported.add(item.toString());
            }
            index.add(entry.toString());
        }
        Files.write(output.resolve("ragas.jsonl"),exported,StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("ragas-index.jsonl"),index,StandardOpenOption.CREATE_NEW);
    }
    public static void provenance(ObjectMapper json,Path capture,Path preflight,Path artifacts,EvaluationCases.Dataset dataset,Path output) throws Exception {
        ObjectNode record=json.createObjectNode().put("schemaVersion",1);
        record.put("captureSha256",EvidenceRecorder.hash(Files.readString(capture)));
        record.put("preflightSha256",EvidenceRecorder.hash(Files.readString(preflight)));
        record.put("resultsSha256",EvidenceRecorder.hash(Files.readString(output.resolve("results.jsonl"))));
        record.put("summarySha256",EvidenceRecorder.hash(Files.readString(output.resolve("summary.json"))));
        record.put("ragasSha256",EvidenceRecorder.hash(Files.readString(output.resolve("ragas.jsonl"))));
        record.put("ragasIndexSha256",EvidenceRecorder.hash(Files.readString(output.resolve("ragas-index.jsonl"))));
        ObjectNode data=record.putObject("datasetFiles");
        List<String> inputs=new ArrayList<>(List.of("dataset.json","cases.jsonl"));
        for(JsonNode document:dataset.manifest().path("documents")) inputs.add(document.path("path").asText());
        if(dataset.manifest().has("source")) inputs.add(dataset.manifest().path("source").path("path").asText());
        for(String name:inputs) data.put(name,EvidenceRecorder.hash(Files.readString(dataset.directory().resolve(name))));
        ObjectNode hashes=record.putObject("artifacts");
        try(var files=Files.list(artifacts)) {
            for(Path file:files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).sorted().toList())
                hashes.put(file.getFileName().toString(),EvidenceRecorder.hash(Files.readString(file)));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("provenance.json").toFile(),record);
    }
}
