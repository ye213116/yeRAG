package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationMeasurementsTest {
    @TempDir Path root;
    @Test void estimatesAndUnavailableUsageDoNotBecomeReportedZeroTokens() throws Exception {
        var f=new PhaseOneFixture(root,3); f.pipeline.put("elapsedMs",25).put("startedAt",1000); f.model.put("startedAt",1010);
        f.event(f.model,"generation",f.json.createObjectNode().put("answer",f.answer).put("firstAnswerMs",5).put("usageStatus","UNAVAILABLE")); f.save();
        var measured=EvaluationMeasurements.measure(f.json,new EvaluationArtifacts(f.artifacts,f.json),f.attempt,f.owner);
        assertEquals(25,measured.path("pipelineMs").asInt()); assertEquals(15,measured.path("firstAnswerFromPipelineStartMs").asInt());
        assertEquals("UNAVAILABLE",measured.path("generationUsage").path("status").asText()); assertFalse(measured.path("generationUsage").has("totalTokens"));
        assertFalse(measured.has("judgeMs"));
        f.attempt.put("corpusVerified",false);
        assertFalse(EvaluationMeasurements.measure(f.json,new EvaluationArtifacts(f.artifacts,f.json),f.attempt,f.owner).has("pipelineMs"));
    }
    @Test void nearestRankPercentilesAndReportedTokenDenominatorsAreExplicit() {
        ObjectMapper json=new ObjectMapper(); List<ObjectNode> rows=new ArrayList<>();
        for(int ms:List.of(10,20,100)) { ObjectNode row=json.createObjectNode(); row.putObject("performance").put("pipelineMs",ms); rows.add(row); }
        ((ObjectNode)rows.get(0).path("performance")).putObject("generationUsage").put("status","REPORTED").put("promptTokens",7).put("completionTokens",3).put("totalTokens",10);
        var summary=EvaluationMeasurements.summary(json,rows);
        assertEquals(20,summary.path("pipelineLatencyMs").path("p50").asInt()); assertEquals(100,summary.path("pipelineLatencyMs").path("p95").asInt());
        assertEquals(1,summary.path("generationUsage").path("reportedCalls").asInt()); assertEquals(2,summary.path("generationUsage").path("missingOrNotCalled").asInt());
        assertEquals(10,summary.path("generationUsage").path("totalTokens").asInt()); assertEquals(0,summary.path("contextEstimatedTokens").path("count").asInt());
    }
}
