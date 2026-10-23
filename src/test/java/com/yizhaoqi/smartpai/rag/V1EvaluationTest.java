package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.EvaluationRunner;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class V1EvaluationTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final Path fixtures = Path.of("src/test/resources/rag-eval");
    private Path artifacts;

    @BeforeEach void copyArtifacts() throws Exception {
        artifacts = temporary.resolve("artifacts");
        Files.createDirectories(artifacts);
        try (var files = Files.list(fixtures.resolve("artifacts"))) {
            for (Path file : files.toList()) Files.copy(file, artifacts.resolve(file.getFileName()));
        }
    }

    @Test void acceptsOriginalIdsCommentsAndReportsCountsByPrefix() throws Exception {
        ObjectNode sample = sample(false);
        sample.put("id", "cross-doc-01").remove("caseId");
        Path cases = temporary.resolve("v1.jsonl");
        Files.writeString(cases, "# 原设计允许注释\n\n  # 第二行注释\n" + sample + "\n");
        Path output = temporary.resolve("output");
        new EvaluationRunner(artifacts).run(cases, output);
        JsonNode row = json.readTree(Files.readAllLines(output.resolve("results.jsonl")).get(0));
        assertEquals("cross-doc-01", row.path("caseId").asText());
        assertEquals("B_DOWNSTREAM", row.path("bucket").asText());
        JsonNode group = json.readTree(output.resolve("summary.json").toFile())
            .path("variants").path("legacy-clipping").path("prefixGroups").path("cross-doc");
        assertEquals(1, group.path("total").asInt());
        assertEquals(1, group.path("provisionalBuckets").path("B_DOWNSTREAM").asInt());
        assertFalse(group.has("passRate"));
    }

    @Test void phraseMatchNormalizesLayoutAndKeepsDocumentAndChildMetricsSeparate() throws Exception {
        ObjectNode sample = sample(true);
        sample.set("expectedEvidence", json.valueToTree(List.of("试 用期\u00a0不适用", "试用期不适用", "第二个必需短语")));
        sample.set("expectedFileNames", json.valueToTree(List.of("合成报销规则", "另一份文档")));
        JsonNode row = new EvaluationRunner(artifacts).evaluate(sample);
        JsonNode retrieval = row.path("phraseRetrieval");
        assertTrue(retrieval.path("hitAtK").asBoolean());
        assertEquals(0.5, retrieval.path("evidencePhraseCoverage").asDouble());
        assertEquals(0.5, retrieval.path("docRecallAtK").asDouble());
        assertEquals(1, retrieval.path("mrrAtK").asDouble());
        assertEquals(0, retrieval.path("matches").path(0).path("chunkId").asInt(-1));
        assertFalse(row.has("recall")); // No fabricated ID relevance labels.
    }

    @ParameterizedTest
    @CsvSource({"true,true,PASS", "true,false,B_DOWNSTREAM", "false,true,C_REVIEW", "false,false,A_RETRIEVAL"})
    void fourBucketsUseChildHitAndIndependentReview(boolean hit, boolean correct, String bucket) throws Exception {
        ObjectNode sample = sample(true);
        sample.put("answerCorrect", correct);
        if (!hit) sample.set("expectedEvidence", json.valueToTree(List.of("没有召回的证据")));
        JsonNode row = new EvaluationRunner(artifacts).evaluate(sample);
        assertEquals(bucket, row.path("bucket").asText());
        assertEquals("PROVISIONAL", row.path("bucketStatus").asText());
    }

    @Test void childHitWithClippedEvidenceIsContextLossNotModelError() throws Exception {
        JsonNode row = new EvaluationRunner(artifacts).evaluate(sample(false));
        assertEquals("B_DOWNSTREAM", row.path("bucket").asText());
        assertEquals("CONTEXT_LOSS", row.path("diagnosis").asText());
        assertTrue(row.path("phraseRetrieval").path("hitAtK").asBoolean());
    }

    @Test void parentOnlyEvidenceCannotImproveRetrievalHit() throws Exception {
        ObjectNode sample = sample(true);
        sample.set("expectedEvidence", json.valueToTree(List.of("仅父块有的短语")));
        mutate(sample, "MERGE", data -> ((ObjectNode) data.path("passages").path(0)).put("text", "仅父块有的短语"));
        JsonNode row = new EvaluationRunner(artifacts).evaluate(sample);
        assertFalse(row.path("phraseRetrieval").path("hitAtK").asBoolean());
        assertEquals("C_REVIEW", row.path("bucket").asText());
    }

    @ParameterizedTest
    @CsvSource({"false,true,EXPECTED_BEHAVIOR", "false,false,NEGATIVE_ANSWER_FAILURE", "true,true,UNEXPECTED_HIT"})
    void expectedMissNeverFallsIntoRetrievalProblem(boolean hit, boolean correct, String bucket) throws Exception {
        ObjectNode sample = sample(true);
        sample.put("retrievalExpectation", "miss").put("answerCorrect", correct);
        if (!hit) sample.set("expectedEvidence", json.valueToTree(List.of("组织外的证据")));
        assertEquals(bucket, new EvaluationRunner(artifacts).evaluate(sample).path("bucket").asText());
    }

    @Test void partialReviewIsWrongAndConflictingLabelsAreRejected() throws Exception {
        ObjectNode sample = sample(true);
        sample.remove("answerCorrect");
        sample.putObject("judge").put("verdict", "partial");
        assertEquals("B_DOWNSTREAM", new EvaluationRunner(artifacts).evaluate(sample).path("bucket").asText());
        sample.put("answerCorrect", true);
        JsonNode conflict = new EvaluationRunner(artifacts).evaluate(sample);
        assertEquals("LABELS_CONFLICT", conflict.path("diagnosis").asText());
        assertFalse(conflict.path("scored").asBoolean());
    }

    @Test void missingReviewOrAnswerCannotBeInventedAndAgentRunsAreDiagnosticOnly() throws Exception {
        ObjectNode sample = sample(true);
        sample.remove("answerCorrect");
        assertEquals("PENDING_JUDGE", new EvaluationRunner(artifacts).evaluate(sample).path("bucket").asText());
        sample.put("answerCorrect", true).remove("modelRunId");
        assertEquals("PENDING_JUDGE", new EvaluationRunner(artifacts).evaluate(sample).path("bucket").asText());
        mutate(sample, "request", data -> ((ObjectNode) data).put("mode", "RETRIEVE_ONLY"));
        assertEquals("DIAGNOSTIC_ONLY", new EvaluationRunner(artifacts).evaluate(sample).path("bucket").asText());
    }

    @Test void topTenCannotBeScoredAsAnEquivalentTopFiveGeneration() throws Exception {
        ObjectNode sample = sample(true);
        sample.put("k", 10);
        assertEquals("TOP_K_MISMATCH", new EvaluationRunner(artifacts).evaluate(sample).path("diagnosis").asText());
    }

    @Test void fixedAbstentionCanBeReviewedWithoutAModelCall() throws Exception {
        ObjectNode sample = sample(true);
        sample.remove("modelRunId");
        sample.put("retrievalExpectation", "miss");
        for (String name : List.of("RETRIEVE", "RERANK"))
            mutate(sample, name, data -> ((ObjectNode) data).putArray("candidates"));
        for (String name : List.of("MERGE", "ASSEMBLE"))
            mutate(sample, name, data -> ((ObjectNode) data).putArray("passages"));
        ObjectNode artifact = (ObjectNode) json.readTree(artifacts.resolve(sample.get("pipelineRunId").asText() + ".json").toFile());
        ObjectNode generated = ((ArrayNode) artifact.get("events")).addObject().put("name", "GENERATE");
        generated.putObject("data").putObject("outcome").put("status", "NO_EVIDENCE").put("reason", "FIXED_ABSTENTION")
            .putObject("evidence").put("answer", "当前知识库中没有足够资料回答这个问题。");
        generated.put("sha256", EvidenceRecorder.hash(generated.get("data").toString()));
        json.writeValue(artifacts.resolve(sample.get("pipelineRunId").asText() + ".json").toFile(), artifact);
        sample.set("expectedEvidence", json.valueToTree(List.of("组织外的证据")));
        assertEquals("EXPECTED_BEHAVIOR", new EvaluationRunner(artifacts).evaluate(sample).path("bucket").asText());
    }

    @Test void duplicateOrConflictingDatasetIdsFailBeforeWritingAReport() throws Exception {
        ObjectNode sample = sample(true);
        Path cases = temporary.resolve("invalid.jsonl");
        Files.writeString(cases, sample + "\n" + sample + "\n");
        assertThrows(IllegalArgumentException.class, () -> new EvaluationRunner(artifacts).run(cases, temporary.resolve("output")));
        sample.put("id", "different");
        Files.writeString(cases, sample + "\n");
        assertThrows(IllegalArgumentException.class, () -> new EvaluationRunner(artifacts).run(cases, temporary.resolve("output")));
    }

    private ObjectNode sample(boolean complete) throws Exception {
        ObjectNode sample = (ObjectNode) json.readTree(Files.readAllLines(fixtures.resolve("cases.jsonl")).get(complete ? 1 : 0));
        sample.remove(List.of("relevance", "ranges", "expectedFacts", "expectedCitations"));
        sample.set("expectedEvidence", json.valueToTree(List.of("试用期不适用")));
        sample.put("retrievalExpectation", "hit");
        return sample;
    }

    private void mutate(ObjectNode sample, String name, Consumer<JsonNode> mutation) throws Exception {
        Path file = artifacts.resolve(sample.get("pipelineRunId").asText() + ".json");
        JsonNode artifact = json.readTree(file.toFile());
        for (JsonNode event : artifact.path("events")) if (name.equals(event.path("name").asText())) {
            JsonNode data = name.equals("request") ? event.get("data") : event.path("data").path("outcome").path("evidence");
            mutation.accept(data);
            ((ObjectNode) event).put("sha256", EvidenceRecorder.hash(event.get("data").toString()));
        }
        json.writeValue(file.toFile(), artifact);
    }
}
