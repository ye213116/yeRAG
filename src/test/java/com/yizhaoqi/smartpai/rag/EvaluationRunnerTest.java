package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.rag.eval.EvaluationRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunnerTest {
    @TempDir Path output;
    ObjectMapper json=new ObjectMapper();
    Path fixtures=Path.of("src/test/resources/rag-eval");
    @Test void frozenCasesExposeLostExceptionAndGenerateVariantReport() throws Exception {
        new EvaluationRunner(fixtures.resolve("artifacts")).run(fixtures.resolve("cases.jsonl"),output);
        var rows=Files.readAllLines(output.resolve("results.jsonl"));
        var clipped=json.readTree(rows.get(0)); var complete=json.readTree(rows.get(1));
        assertEquals("CONTEXT_LOSS",clipped.path("diagnosis").asText()); assertEquals(0,clipped.path("contextCoverage").asDouble());
        assertEquals("CONTEXT_RETAINED",complete.path("diagnosis").asText()); assertEquals(1,complete.path("contextCoverage").asDouble());
        assertTrue(complete.path("wireContextConfirmed").asBoolean()); assertEquals(1,complete.path("citationPrecision").asDouble());
        assertEquals(2,json.readTree(output.resolve("summary.json").toFile()).path("total").asInt());
    }
    @Test void missingOrCorruptArtifactIsNotScoredAsRetrievalMiss() throws Exception {
        var evaluator=new EvaluationRunner(output);
        var sample=json.readTree(Files.readAllLines(fixtures.resolve("cases.jsonl")).get(0));
        assertFalse(evaluator.evaluate(sample).path("scored").asBoolean());
        try(var files=Files.list(fixtures.resolve("artifacts"))) { for(Path path:files.toList()) Files.copy(path,output.resolve(path.getFileName())); }
        Path artifact=output.resolve(sample.path("pipelineRunId").asText()+".json");
        var corrupt=json.readTree(artifact.toFile()); ((com.fasterxml.jackson.databind.node.ObjectNode)corrupt.path("events").path(0).get("data")).put("question","mutated");
        json.writeValue(artifact.toFile(),corrupt);
        assertEquals("EVIDENCE_INCOMPLETE",evaluator.evaluate(sample).path("diagnosis").asText());
    }
    @Test void rankMetricsUseGradedRelevanceAndCorrectDenominators() {
        var evaluator=new EvaluationRunner(output); var metrics=evaluator.metrics(List.of("irrelevant","a","b"),Map.of("a",3,"b",1),2);
        assertEquals(0.5,metrics.path("recall").asDouble()); assertEquals(0.5,metrics.path("mrr").asDouble());
        assertTrue(metrics.path("ndcg").asDouble()>0 && metrics.path("ndcg").asDouble()<1);
    }
}
