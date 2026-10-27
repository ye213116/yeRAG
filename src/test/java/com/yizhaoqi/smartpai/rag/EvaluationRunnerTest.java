package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunnerTest {
    @TempDir Path root;
    @Test void completeHitIsIndependentOfAnyHitAndNegativesHaveNoCompleteHit() throws Exception {
        var partial=new PhaseOneFixture(root,2);
        JsonNode top=partial.evaluate(partial.approve()).path("deterministic").path("top5");
        assertEquals(1,top.path("hit").asInt()); assertEquals(0,top.path("completeHit").asInt());
        assertEquals(1,EvidencePositions.candidates(partial.json,partial.check.path("units"),partial.json.valueToTree(partial.originals)).path("completeHit").asInt());
        assertFalse(EvidencePositions.candidates(partial.json,partial.json.createArrayNode(),partial.json.createArrayNode()).has("completeHit"));
    }
    @Test void topKAssemblyDecisionIsNotMislabelledAsTokenBudgetLoss() throws Exception {
        var f=new PhaseOneFixture(root,2); ObjectNode assembled=(ObjectNode)EvaluationArtifacts.stage(f.pipeline,"ASSEMBLE").deepCopy();
        assembled.putArray("decisions").addObject().put("id",f.originals.get(2).id()).put("reason","TOP_K");
        f.stage(f.pipeline,"ASSEMBLE",assembled); f.stage(f.pipeline,"MERGE",f.json.createObjectNode().set("passages",f.passages(f.originals))); f.save();
        String findings=f.evaluate(f.approve()).path("deterministic").path("findings").toString();
        assertTrue(findings.contains("CONTEXT_TOP_K_WINDOW_LOSS")); assertFalse(findings.contains("CONTEXT_BUDGET_LOSS"));
    }
    @ParameterizedTest @CsvSource({"0,0,NONE","1,1,PARTIAL","2,1,PARTIAL","3,1,FULL"})
    void necessaryUnitsDistinguishZeroPartialAndFull(int covered,int hit,String state) throws Exception {
        var f=new PhaseOneFixture(root,covered); JsonNode row=f.evaluate(f.approve());
        assertEquals("AVAILABLE",row.path("deterministic").path("status").asText());
        JsonNode top=row.path("deterministic").path("top5");
        assertEquals(hit,top.path("hit").asInt()); assertEquals(covered/3.0,top.path("coverage").asDouble()); assertEquals(state,top.path("state").asText());
        assertEquals(3-covered,top.path("missingUnits").size());
    }
    @Test void judgeChangesCannotAlterDeterministicFacts() throws Exception {
        var f=new PhaseOneFixture(root,2); Path reviews=f.approve();
        JsonNode wrong=f.evaluate(reviews); f.setJudge("correct",false); f.save(); JsonNode correct=f.evaluate(reviews);
        ObjectNode differentModel=(ObjectNode)EvaluationArtifacts.event(f.judge,"request").deepCopy(); differentModel.put("requestedModel","another-judge-model");
        f.event(f.judge,"request",differentModel); f.save(); correct=f.evaluate(reviews);
        assertEquals(wrong.path("deterministic"),correct.path("deterministic"));
        assertEquals("PARTIAL",correct.path("deterministic").path("top5").path("state").asText());
        assertEquals("correct",correct.path("semantic").path("assessment").path("verdict").asText());
    }
    @Test void poolAndTop5LossAndAssemblyLossAreDifferentFindings() throws Exception {
        var f=new PhaseOneFixture(root,2); f.setAssembled(f.top.stream().filter(c -> c.id().endsWith("p0")).toList()); f.setJudge("wrong",false); f.save();
        JsonNode d=f.evaluate(f.approve()).path("deterministic");
        assertEquals("FULL",d.path("retrievalPool").path("state").asText());
        assertEquals("PARTIAL",d.path("top5").path("state").asText());
        assertEquals(1,d.path("assembled").path("covered").asInt());
        String findings=d.path("findings").toString(); assertTrue(findings.contains("RERANK_OR_TOP5_WINDOW_LOSS")); assertTrue(findings.contains("ASSEMBLY_LOSS"));
    }
    @Test void failedJudgeKeepsRetrievalDenominatorButNotAnswerDenominator() throws Exception {
        var f=new PhaseOneFixture(root,3); f.judge.put("status","FAILED"); f.save(); Path reviews=f.approve();
        new EvaluationRunner(f.artifacts).run(f.directory,root.resolve("preflight.json"),root.resolve("capture.json"),root.resolve("report"),reviews);
        JsonNode summary=f.json.readTree(root.resolve("report/summary.json").toFile());
        assertEquals(1,summary.path("retrieval").path("count").asInt()); assertEquals(0,summary.path("answers").path("count").asInt());
        assertEquals(1,summary.path("retrieval").path("coverageAt5").asDouble());
    }
    @Test void pendingOrDisputedLabelsAreNotOfficialZeros() throws Exception {
        var f=new PhaseOneFixture(root,2); JsonNode pending=f.evaluate(null);
        assertFalse(pending.path("eligibility").path("retrieval").asBoolean());
        assertEquals(2.0/3,pending.path("deterministic").path("top5").path("coverage").asDouble());
        Path review=f.approve(); String approval=Files.readString(review); ObjectNode dispute=(ObjectNode)f.json.readTree(approval);
        dispute.put("previousReviewId","test-label-review").put("reviewId","test-dispute").put("state","DISPUTED");
        Files.writeString(review,dispute+"\n",StandardOpenOption.APPEND);
        assertEquals("DISPUTED",f.evaluate(review).path("humanReview").path("labels").asText());
        assertFalse(f.evaluate(review).path("eligibility").path("answer").asBoolean());
    }
    @Test void humanAnswerRevisionPreservesRawJudgeAndHistory() throws Exception {
        var f=new PhaseOneFixture(root,3); Path reviews=f.approve();
        ObjectNode answer=f.json.createObjectNode().put("reviewId","answer-review-1").put("type","ANSWER").put("datasetHash",f.dataset.hash()).put("caseId",f.sample.path("id").asText())
            .put("pipelineRunId",f.pipelineId).put("answerSha256",EvidenceRecorder.hash(f.answer)).put("verdict","correct").put("reviewer","test-reviewer").put("reason","test-only override").put("createdAt","2026-10-26T01:00:00Z");
        Files.writeString(reviews,answer+"\n",StandardOpenOption.APPEND);
        JsonNode row=f.evaluate(reviews);
        assertEquals("wrong",row.path("semantic").path("assessment").path("verdict").asText()); assertEquals("correct",row.path("effectiveVerdict").asText());
        assertEquals(2,row.path("humanReview").path("history").size());
    }
    @Test void corruptModelOrJudgeCannotEraseValidRetrievalButCorruptPipelineIsUnavailable() throws Exception {
        var f=new PhaseOneFixture(root,3); Path reviews=f.approve();
        ((ObjectNode)f.model.path("events").path(0).path("data")).put("model","tampered"); f.save();
        assertTrue(f.evaluate(reviews).path("eligibility").path("retrieval").asBoolean());
        assertFalse(f.evaluate(reviews).path("eligibility").path("answer").asBoolean());
        ((ObjectNode)f.pipeline.path("events").path(0).path("data")).put("question","tampered"); f.save();
        assertEquals("UNAVAILABLE",f.evaluate(reviews).path("deterministic").path("status").asText());
    }
    @Test void failedGenerationRetainsCompletedTop5Metrics() throws Exception {
        var f=new PhaseOneFixture(root,3); f.pipeline.put("status","TRUNCATED"); f.model.put("status","TRUNCATED"); f.save();
        JsonNode row=f.evaluate(f.approve());
        assertTrue(row.path("eligibility").path("retrieval").asBoolean()); assertFalse(row.path("eligibility").path("answer").asBoolean());
        assertEquals("GENERATION_INCOMPLETE",row.path("semantic").path("status").asText());
    }
    @Test void sourceMetadataCannotClaimEvidenceAbsentFromActualAssembledText() throws Exception {
        var f=new PhaseOneFixture(root,3);
        ObjectNode assembly=(ObjectNode)EvaluationArtifacts.stage(f.pipeline,"ASSEMBLE").deepCopy(); assembly.put("content","正文已被错误替换");
        f.stage(f.pipeline,"ASSEMBLE",assembly); f.setJudge("correct",false); f.save();
        JsonNode row=f.evaluate(f.approve());
        assertTrue(row.path("eligibility").path("retrieval").asBoolean()); assertFalse(row.path("eligibility").path("answer").asBoolean());
        assertEquals("ASSEMBLED_CONTENT_SOURCE_MISMATCH",row.path("deterministic").path("downstreamReason").asText());
    }
    @Test void negativeCasesHaveNoPositiveMetricAndFixedAbstentionStillHasAnAnswer() throws Exception {
        var f=new PhaseOneFixture(root,0,"unanswerable");
        f.setAssembled(List.of()); ObjectNode generation=f.json.createObjectNode();
        generation.putObject("outcome").put("status","NO_EVIDENCE").put("reason","FIXED_ABSTENTION").putObject("evidence").put("answer",f.answer);
        f.event(f.pipeline,"GENERATE",generation); f.attempt.remove("modelRunId"); f.setJudge("correct",false); f.save();
        JsonNode row=f.evaluate(f.approve());
        assertEquals("NOT_APPLICABLE",row.path("deterministic").path("top5").path("state").asText());
        assertFalse(row.path("eligibility").path("retrieval").asBoolean()); assertTrue(row.path("eligibility").path("answer").asBoolean());
    }
    @Test void newAnnotationVersionRecomputesOldFactsWithoutReusingOldJudge() throws Exception {
        var f=new PhaseOneFixture(root,1); Path oldReviews=f.approve();
        new EvaluationRunner(f.artifacts).run(f.directory,root.resolve("preflight.json"),root.resolve("capture.json"),root.resolve("old-report"),oldReviews);
        String oldResults=Files.readString(root.resolve("old-report/results.jsonl"));
        ObjectNode manifest=(ObjectNode)f.json.readTree(f.directory.resolve("dataset.json").toFile()); manifest.put("datasetVersion","test-v2");
        f.json.writeValue(f.directory.resolve("dataset.json").toFile(),manifest); ((ArrayNode)f.sample.get("evidenceUnits")).remove(2);
        Files.writeString(f.directory.resolve("cases.jsonl"),f.sample+"\n"); var revised=EvaluationCases.load(f.directory,f.json);
        ObjectNode recheck=new EvaluationPreflight(f.json).build(revised,f.owner,"permissions",f.originals,List.of());
        f.json.writeValue(root.resolve("recheck.json").toFile(),recheck);
        new EvaluationRunner(f.artifacts).run(f.directory,root.resolve("recheck.json"),root.resolve("capture.json"),root.resolve("new-report"),oldReviews);
        JsonNode row=f.json.readTree(Files.readAllLines(root.resolve("new-report/results.jsonl")).get(0));
        assertEquals(.5,row.path("deterministic").path("top5").path("coverage").asDouble());
        assertEquals("LABEL_VERSION_CHANGED_REJUDGE_REQUIRED",row.path("semantic").path("status").asText());
        assertEquals("PENDING",row.path("humanReview").path("labels").asText()); assertEquals(oldResults,Files.readString(root.resolve("old-report/results.jsonl")));
    }
}
