package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;
import static com.yizhaoqi.smartpai.rag.eval.EvaluationArtifacts.*;

/** Pure offline report: deterministic facts, model opinions and human reviews are separate outputs. */
public final class EvaluationRunner {
    private final ObjectMapper json=new ObjectMapper();
    private final EvaluationArtifacts artifacts;
    public EvaluationRunner(Path directory) { artifacts=new EvaluationArtifacts(directory,json); }
    public static void main(String[] args) throws Exception {
        if(args.length<4 || args.length>5) throw new IllegalArgumentException("Usage: dataset-directory preflight.json capture.json output-directory [reviews.jsonl]");
        Path capture=Path.of(args[2]);
        new EvaluationRunner(capture.toAbsolutePath().getParent().resolve("artifacts")).run(Path.of(args[0]),Path.of(args[1]),capture,Path.of(args[3]),args.length==5?Path.of(args[4]):null);
    }
    public void run(Path datasetDirectory,Path preflightFile,Path captureFile,Path output,Path reviewsFile) throws Exception {
        var dataset=EvaluationCases.load(datasetDirectory,json);
        JsonNode preflight=json.readTree(preflightFile.toFile()),capture=json.readTree(captureFile.toFile());
        EvaluationPreflight.verify(preflight); EvaluationPreflight.verify(capture);
        boolean publicDataset=dataset.manifest().path("schemaVersion").asInt()==3;
        if(publicDataset && (!dataset.manifest().path("source").path("sha256").equals(preflight.path("sourceSha256"))
                || !dataset.manifest().path("benchmark").equals(preflight.path("benchmark"))
                || !json.valueToTree(dataset.manifest().findValues("fileMd5").stream().map(JsonNode::asText).distinct().sorted().toList()).equals(preflight.path("corpusMd5s"))))
            throw new IllegalArgumentException("Public source/benchmark/corpus binding mismatch");
        boolean originalLabels=dataset.hash().equals(capture.path("datasetHash").asText());
        if(!dataset.hash().equals(preflight.path("datasetHash").asText()) || !preflight.path("owner").equals(capture.path("owner"))
                || !preflight.path("corpusFingerprint").equals(capture.path("corpusFingerprint"))
                || !preflight.path("corpusVersion").equals(capture.path("corpusVersion"))
                || (originalLabels && !preflight.path("sha256").equals(capture.path("preflightSha256"))))
            throw new IllegalArgumentException("Dataset/preflight/capture binding mismatch; revised labels require re-preflight of the same corpus");
        Map<String,JsonNode> attempts=index(capture.path("cases")),checks=index(preflight.path("cases"));
        for(JsonNode value:attempts.values()) {
            ((ObjectNode)value).put("expectedRecallStrategy",capture.path("experiment").path("recallStrategy").asText("HYBRID"));
            ((ObjectNode)value).put("expectedBaseline",capture.path("experiment").path("baseline").asBoolean());
        }
        var reviews=new EvaluationReviews(reviewsFile,json); List<ObjectNode> rows=new ArrayList<>();
        for(ObjectNode sample:dataset.cases()) rows.add(evaluate(sample,checks.get(sample.path("id").asText()),preflight,attempts.get(sample.path("id").asText()),reviews));
        rows.stream().filter(r -> r.path("eligibility").path("answer").asBoolean() && "correct".equals(r.path("effectiveVerdict").asText())
                && !r.path("humanReview").has("answer")).sorted(Comparator.comparing(r -> r.path("id").asText())).limit(5)
            .forEach(r -> { r.put("reviewRequired",true); r.put("fixedPassSample",true); });
        Files.createDirectories(output);
        Files.write(output.resolve("results.jsonl"),rows.stream().map(JsonNode::toString).toList(),StandardOpenOption.CREATE_NEW);
        ObjectNode summary=summarize(rows); summary.put("datasetHash",dataset.hash()).put("captureDatasetHash",capture.path("datasetHash").asText())
            .put("corpusFingerprint",preflight.path("corpusFingerprint").asText());
        summary.set("experiment",capture.path("experiment").deepCopy());
        summary.set("measurements",EvaluationMeasurements.summary(json,rows));
        summary.set("diagnosticCohorts",diagnosticCohorts(rows));
        summary.set("answerSupport",supportSummary(rows));
        if(publicDataset) summary.set("publicBenchmark",publicSummary(rows,dataset.manifest().path("benchmark")));
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(),summary);
        StringBuilder report=new StringBuilder("# yeRAG 单链路评测\n\n确定性证据、模型判断和人工复核分别保存。指标分母见 summary.json；待人工确认的标注不进入正式指标。\n\n");
        report.append("总题数：").append(rows.size()).append("；正式检索有效题：").append(summary.path("retrieval").path("count").asInt()).append("；正式答案有效题：").append(summary.path("answers").path("count").asInt()).append("。\n\n");
        if(publicDataset) report.append("公开来源指标（与人工审核口径分开）：\n\n```json\n").append(json.writerWithDefaultPrettyPrinter().writeValueAsString(summary.path("publicBenchmark")))
            .append("\n```\n\n共享候选子语料，非官方 distractor/fullwiki 榜单条件。指标使用原始完整回答；缺失或未完成回答计零，技术失败另计。\n\n");
        report.append("Hit@5=任一必要证据命中；CompleteHit@5=全部必要证据命中；Coverage@5=必要单元覆盖比例。三者不替代 qrels Recall。\n\n")
            .append("运行测量（Pipeline 延迟不含离线 Judge；Token 用量与估算分开，未换算金额）：\n\n```json\n")
            .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(summary.path("measurements"))).append("\n```\n\n")
            .append("独立支持性评审（自定义模型判断，非 RAGAS 分数；无要点或无事实陈述为 N/A）：\n\n```json\n")
            .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(summary.path("answerSupport"))).append("\n```\n\n")
            .append("入模证据与答案结果分组（观测关联，不直接宣称因果）：\n\n```json\n")
            .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(summary.path("diagnosticCohorts"))).append("\n```\n\n");
        report.append("| 题目 | Top5 覆盖 | 阶段发现 | Judge | 人工标签状态 |\n|---|---|---|---|---|\n");
        for(JsonNode row:rows) report.append('|').append(row.path("id").asText()).append('|').append(row.path("deterministic").path("top5").path("state").asText("UNAVAILABLE"))
            .append('|').append(row.path("deterministic").path("findings").toString()).append('|').append(row.path("semantic").path("assessment").path("verdict").asText(row.path("semantic").path("status").asText()))
            .append('|').append(row.path("humanReview").path("labels").asText()).append("|\n");
        Files.writeString(output.resolve("report.md"),report);
        Files.write(output.resolve("review-queue.jsonl"),rows.stream().filter(r -> r.path("reviewRequired").asBoolean()).map(JsonNode::toString).toList());
        EvaluationExport.write(json,artifacts,dataset,rows,output);
        EvaluationExport.provenance(json,captureFile,preflightFile,artifacts.directory(),dataset,output);
    }
    public ObjectNode evaluate(JsonNode sample,JsonNode check,JsonNode preflight,JsonNode attempt,EvaluationReviews reviews) {
        ObjectNode row=json.createObjectNode().put("id",sample.path("id").asText()).put("type",sample.path("type").asText());
        String pipelineId=attempt==null?"":attempt.path("pipelineRunId").asText(); row.put("pipelineRunId",pipelineId);
        ObjectNode deterministic=row.putObject("deterministic"),semantic=row.putObject("semantic").put("status","NOT_RUN");
        String answer="",context=""; boolean completed=false,available=false;
        try {
            if(check==null || !"READY".equals(check.path("status").asText())) throw new IllegalStateException("DATASET_INVALID");
            if(attempt==null) throw new IllegalStateException("NOT_RUN");
            if(!attempt.path("corpusVerified").asBoolean()) throw new IllegalStateException("CORPUS_OR_PERMISSION_CHANGED");
            JsonNode pipeline=artifacts.read(pipelineId),request=event(pipeline,"request");
            if(attempt.has("expectedRecallStrategy") && (!attempt.path("expectedRecallStrategy").asText().equals(request.path("recallStrategy").asText("HYBRID"))
                    || (attempt.path("expectedBaseline").asBoolean() && (request.path("options").path("rerank").asBoolean()
                        || request.path("options").path("rewrite").asBoolean() || !"RRF".equals(event(pipeline,"retrievalConfig").path("mode").asText())))))
                throw new IllegalStateException("BASELINE_CONFIG_MISMATCH");
            if(!"pipeline".equals(pipeline.path("kind").asText()) || !preflight.path("owner").equals(pipeline.path("owner"))
                    || !preflight.path("corpusVersion").equals(pipeline.path("corpusVersion")) || !sample.path("question").equals(request.path("question"))
                    || !"FIXED_QA".equals(request.path("mode").asText()) || request.path("topK").asInt()!=5
                    || !request.path("history").isArray() || !request.path("history").isEmpty())
                throw new IllegalStateException("PIPELINE_BINDING_MISMATCH");
            JsonNode top=topFive(pipeline),retrieval=stage(pipeline,"RETRIEVE"),units=check.path("units");
            if(!retrieval.path("candidates").isArray()) throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
            if(check.path("publicSourceMapped").asBoolean()) {
                if(!preflight.path("corpusMd5s").equals(request.path("corpusMd5s"))
                        || request.path("conciseAnswer").asBoolean()!=preflight.path("benchmark").path("answerStyle").asText().equals("short"))
                    throw new IllegalStateException("PUBLIC_RUN_CONFIG_MISMATCH");
                Set<String> scope=new HashSet<>(); for(JsonNode md5:preflight.path("corpusMd5s")) scope.add(md5.asText());
                for(JsonNode candidate:retrieval.path("candidates")) if(!scope.contains(candidate.path("fileMd5").asText()))
                    throw new IllegalStateException("OUT_OF_CORPUS_CANDIDATE");
            }
            // Detect changed indexed originals rather than silently interpreting them as a miss.
            for(JsonNode unit:units) for(JsonNode anchor:unit.path("anchors")) for(JsonNode candidate:retrieval.path("candidates"))
                if(anchor.path("sourceId").equals(candidate.path("id")) && !anchor.path("sourceHash").asText().equals(EvidenceRecorder.hash(candidate.path("text").asText())))
                    throw new IllegalStateException("CORPUS_CHANGED");
            deterministic.put("status","AVAILABLE").put("pipelineStatus",pipeline.path("status").asText());
            deterministic.put("degraded","DEGRADED".equals(pipeline.path("status").asText()) || "DEGRADED".equals(event(pipeline,"RERANK").path("outcome").path("status").asText()));
            deterministic.set("retrievalPool",EvidencePositions.candidates(json,units,retrieval.path("candidates")));
            deterministic.set("top5",EvidencePositions.candidates(json,units,top));
            available=true;
            JsonNode merged=stage(pipeline,"MERGE"),assembled=stage(pipeline,"ASSEMBLE");
            ArrayNode findings=deterministic.putArray("findings");
            losses(findings,units,deterministic.path("retrievalPool"),deterministic.path("top5"),"RERANK_OR_TOP5_WINDOW_LOSS");
            for(JsonNode id:deterministic.path("retrievalPool").path("missingUnits")) finding(findings,"RETRIEVAL_MISS",id.asText());
            if(merged.path("passages").isArray()) {
                deterministic.set("merged",EvidencePositions.passages(json,units,merged.path("passages")));
                losses(findings,units,deterministic.path("top5"),deterministic.path("merged"),"MERGE_LOSS");
            }
            if(assembled.path("passages").isArray()) {
                context=assembled.path("content").asText();
                for(JsonNode passage:assembled.path("passages")) if(passage.path("text").asText().isBlank() || !context.contains(passage.path("text").asText()))
                    throw new IllegalStateException("ASSEMBLED_CONTENT_SOURCE_MISMATCH");
                deterministic.set("assembled",EvidencePositions.passages(json,units,assembled.path("passages")));
                deterministic.set("assemblyDecisions",assembled.path("decisions"));
                assemblyLosses(findings,units,deterministic.has("merged")?deterministic.path("merged"):deterministic.path("top5"),deterministic.path("assembled"),merged,assembled);
            }
            answer=answer(pipeline);
            JsonNode generation=event(pipeline,"GENERATE").path("outcome");
            boolean terminal=Set.of("SUCCEEDED","DEGRADED","NO_EVIDENCE").contains(pipeline.path("status").asText()) && !answer.isBlank();
            if(attempt.hasNonNull("modelRunId")) {
                try {
                    JsonNode model=artifacts.read(attempt.path("modelRunId").asText()),wire=event(model,"request");
                    if(!pipelineId.equals(model.path("parentRunId").asText()) || !pipeline.path("owner").equals(model.path("owner"))
                            || !wire.path("stream").asBoolean() || !EvidenceRecorder.hash(wire.toString()).equals(event(model,"dispatch").path("requestSha256").asText())) throw new IllegalStateException("WIRE_BINDING_MISMATCH");
                    boolean seen=false;
                    boolean styleConfirmed=!request.path("conciseAnswer").asBoolean();
                    for(JsonNode message:wire.path("messages")) if("system".equals(message.path("role").asText())
                            && com.yizhaoqi.smartpai.rag.stage.GenerateStage.systemRules(true).equals(message.path("content").asText())) styleConfirmed=true;
                    for(JsonNode message:wire.path("messages")) if("user".equals(message.path("role").asText()) && !context.isBlank()
                            && message.path("content").asText().contains("参考资料：\n"+context+"\n问题："+sample.path("question").asText())) seen=true;
                    deterministic.put("wireContextConfirmed",seen);
                    if(seen && deterministic.has("assembled")) deterministic.set("wire",deterministic.get("assembled").deepCopy());
                    else finding(findings,"WIRE_CONTEXT_UNCONFIRMED","");
                    completed=terminal && seen && styleConfirmed && "SUCCEEDED".equals(model.path("status").asText()) && answer.equals(event(model,"generation").path("answer").asText());
                } catch(Exception wireFailure) { deterministic.put("wireStatus","UNAVAILABLE"); finding(findings,"WIRE_CONTEXT_UNCONFIRMED",""); }
            } else if(terminal && "FIXED_ABSTENTION".equals(generation.path("reason").asText()) && assembled.path("passages").isArray() && assembled.path("passages").isEmpty()) {
                deterministic.put("wireStatus","NOT_SENT_FIXED_ABSTENTION"); completed=true;
            }
            deterministic.put("generationComplete",completed);
            if(completed && "acl".equals(sample.path("type").asText())) {
                ArrayNode leaked=deterministic.putArray("forbiddenQuoteMatches");
                for(JsonNode quote:sample.path("forbiddenQuotes")) if(!EvidencePositions.locate(answer,quote.asText()).isEmpty()) leaked.add(quote.asText());
                deterministic.put("aclExactLeakDetected",!leaked.isEmpty());
            }
        } catch(Exception failure) {
            if(!available) { deterministic.removeAll(); deterministic.put("status","UNAVAILABLE").put("reason",safeReason(failure)); }
            else deterministic.put("downstreamEvidenceStatus","UNAVAILABLE").put("downstreamReason",safeReason(failure));
        }
        if(check!=null && check.path("publicSourceMapped").asBoolean()) {
            row.put("labelSource","PUBLIC_SOURCE_MAPPED_NOT_HUMAN_REVIEWED");
            row.set("nativeAnswer",BenchmarkAnswerMetrics.score(json,answer,sample.path("referenceAnswers"),completed,
                preflight.path("benchmark").path("scorer").asText()));
        }
        row.set("performance",EvaluationMeasurements.measure(json,artifacts,attempt,preflight.path("owner").asText()));
        ObjectNode support=row.putObject("answerSupport").put("status","NOT_RUN");
        if(completed && attempt!=null && attempt.hasNonNull("supportRunId")) try {
            JsonNode run=artifacts.read(attempt.path("supportRunId").asText()),request=event(run,"request");
            if(!pipelineId.equals(run.path("parentRunId").asText()) || !"answer-support".equals(run.path("kind").asText())
                    || !preflight.path("owner").equals(run.path("owner")) || !preflight.path("datasetHash").equals(request.path("datasetHash"))
                    || !sample.path("id").equals(request.path("caseId")) || !EvidenceRecorder.hash(answer).equals(request.path("answerSha256").asText())
                    || !EvidenceRecorder.hash(context).equals(request.path("contextSha256").asText())
                    || !EvaluationSupportJudge.VERSION.equals(request.path("promptVersion").asText())) throw new IllegalStateException("Support binding mismatch");
            if(!"SUCCEEDED".equals(run.path("status").asText())) support.put("status","FAILED");
            else {
                JsonNode assessment=EvaluationSupportJudge.parse(json,event(run,"assessment").toString(),answer,context,sample.path("keyPoints"));
                support.put("status","ASSESSED").set("assessment",assessment); support.set("metrics",EvaluationSupportJudge.metrics(json,assessment));
            }
        } catch(Exception invalid) { support.put("status","INVALID_OR_LABEL_VERSION_CHANGED"); }
        // All model/human processing below is intentionally unable to modify deterministic.
        if(completed && attempt!=null && attempt.hasNonNull("judgeRunId")) {
            try {
                JsonNode judge=artifacts.read(attempt.path("judgeRunId").asText()),request=event(judge,"request");
                if(!"judge".equals(judge.path("kind").asText()) || !preflight.path("owner").equals(judge.path("owner"))
                        || !pipelineId.equals(judge.path("parentRunId").asText())
                        || !sample.path("id").equals(request.path("caseId")) || !EvidenceRecorder.hash(answer).equals(request.path("answerSha256").asText())
                        || !EvidenceRecorder.hash(context).equals(request.path("contextSha256").asText())) throw new IllegalStateException("JUDGE_BINDING_MISMATCH");
                semantic.put("runId",judge.path("runId").asText());
                if(!preflight.path("datasetHash").equals(request.path("datasetHash"))) semantic.put("status","LABEL_VERSION_CHANGED_REJUDGE_REQUIRED");
                else if(!"SUCCEEDED".equals(judge.path("status").asText())) semantic.put("status","JUDGE_FAILED");
                else {
                    JsonNode assessment=event(judge,"assessment"); EvaluationJudge.parse(json,assessment.toString());
                    semantic.put("status","ASSESSED").set("assessment",assessment);
                }
            } catch(Exception failed) { semantic.put("status","JUDGE_EVIDENCE_INVALID"); }
        } else semantic.put("status",completed?"JUDGE_NOT_RUN":"GENERATION_INCOMPLETE");
        ObjectNode review=reviews.forCase(preflight.path("datasetHash").asText(),sample.path("id").asText(),pipelineId,EvidenceRecorder.hash(answer));
        row.set("humanReview",review);
        boolean labels="APPROVED".equals(review.path("labels").asText());
        ObjectNode eligibility=row.putObject("eligibility");
        eligibility.put("retrieval",labels && available && "answerable".equals(sample.path("type").asText()));
        JsonNode human=review.get("answer");
        boolean disputed=human!=null && "disputed".equals(human.path("verdict").asText());
        boolean needsReview=semantic.path("assessment").path("reviewRequired").asBoolean() || deterministic.path("aclExactLeakDetected").asBoolean();
        eligibility.put("answer",labels && completed && !disputed && ((human!=null) || ("ASSESSED".equals(semantic.path("status").asText()) && !needsReview)));
        if(!eligibility.path("retrieval").asBoolean()) eligibility.put("retrievalExcludedReason",!labels?"LABELS_"+review.path("labels").asText():!"answerable".equals(sample.path("type").asText())?"NOT_APPLICABLE":deterministic.path("reason").asText("EVIDENCE_UNAVAILABLE"));
        if(!eligibility.path("answer").asBoolean()) eligibility.put("answerExcludedReason",!labels?"LABELS_"+review.path("labels").asText():!completed?"GENERATION_INCOMPLETE":disputed?"HUMAN_DISPUTE":needsReview?"REQUIRES_HUMAN_REVIEW":semantic.path("status").asText());
        if(human!=null && !disputed) row.put("effectiveVerdict",human.path("verdict").asText());
        else if("ASSESSED".equals(semantic.path("status").asText())) row.put("effectiveVerdict",semantic.path("assessment").path("verdict").asText());
        row.put("reviewRequired",!labels || disputed || needsReview || !"correct".equals(row.path("effectiveVerdict").asText()));
        return row;
    }
    private ObjectNode summarize(List<ObjectNode> rows) {
        ObjectNode summary=json.createObjectNode().put("total",rows.size());
        ObjectNode retrieval=summary.putObject("retrieval"),answers=summary.putObject("answers"),states=retrieval.putObject("states");
        var eligible=rows.stream().filter(r -> r.path("eligibility").path("retrieval").asBoolean()).toList();
        retrieval.put("count",eligible.size()).put("excludedPositive",rows.stream().filter(r -> "answerable".equals(r.path("type").asText())).count()-eligible.size());
        if(!eligible.isEmpty()) {
            retrieval.put("hitAt5",eligible.stream().mapToDouble(r -> r.path("deterministic").path("top5").path("hit").asDouble()).average().orElseThrow());
            retrieval.put("coverageAt5",eligible.stream().mapToDouble(r -> r.path("deterministic").path("top5").path("coverage").asDouble()).average().orElseThrow());
            retrieval.put("completeHitAt5",eligible.stream().filter(r -> "FULL".equals(r.path("deterministic").path("top5").path("state").asText())).count()/(double)eligible.size());
        }
        for(String state:List.of("NONE","PARTIAL","FULL")) states.put(state,eligible.stream().filter(r -> state.equals(r.path("deterministic").path("top5").path("state").asText())).count());
        var judged=rows.stream().filter(r -> r.path("eligibility").path("answer").asBoolean()).toList();
        answers.put("count",judged.size()).put("excluded",rows.size()-judged.size());
        for(String verdict:List.of("correct","partial","wrong")) answers.put(verdict,judged.stream().filter(r -> verdict.equals(r.path("effectiveVerdict").asText())).count());
        ObjectNode negative=summary.putObject("negativeCases");
        for(String type:List.of("unanswerable","acl")) {
            var group=rows.stream().filter(r -> type.equals(r.path("type").asText())).toList();
            negative.putObject(type).put("total",group.size()).put("reviewed",group.stream().filter(r -> r.path("eligibility").path("answer").asBoolean()).count())
                .put("correct",group.stream().filter(r -> r.path("eligibility").path("answer").asBoolean() && "correct".equals(r.path("effectiveVerdict").asText())).count());
        }
        summary.put("pendingOrDisputedLabels",rows.stream().filter(r -> !"APPROVED".equals(r.path("humanReview").path("labels").asText())).count());
        summary.put("reviewQueue",rows.stream().filter(r -> r.path("reviewRequired").asBoolean()).count());
        summary.put("degraded",rows.stream().filter(r -> r.path("deterministic").path("degraded").asBoolean()).count());
        summary.put("aclExactLeaks",rows.stream().filter(r -> r.path("deterministic").path("aclExactLeakDetected").asBoolean()).count());
        return summary;
    }
    private ObjectNode publicSummary(List<ObjectNode> rows,JsonNode benchmark) {
        ObjectNode result=json.createObjectNode(); result.set("configuration",benchmark.deepCopy());
        result.put("labelSource","PUBLIC_SOURCE_MAPPED_NOT_HUMAN_REVIEWED");
        result.put("count",rows.size()).put("completed",rows.stream().filter(r -> r.path("nativeAnswer").path("generationComplete").asBoolean()).count());
        var scored=rows.stream().filter(r -> "SCORED".equals(r.path("nativeAnswer").path("status").asText())).toList();
        result.put("answerMetricCount",scored.size()).put("incomplete",rows.size()-result.path("completed").asInt());
        if(!scored.isEmpty()) {
            result.put("em",scored.stream().mapToDouble(r -> r.path("nativeAnswer").path("em").asDouble()).average().orElseThrow());
            result.put("f1",scored.stream().mapToDouble(r -> r.path("nativeAnswer").path("f1").asDouble()).average().orElseThrow());
        } else result.put("answerMetricsStatus","NOT_APPLICABLE");
        var evidence=rows.stream().filter(r -> "AVAILABLE".equals(r.path("deterministic").path("status").asText())
            && r.path("deterministic").path("top5").path("required").asInt()>0).toList();
        ObjectNode observed=result.putObject("observedSupportingEvidence").put("count",evidence.size());
        if(evidence.isEmpty()) observed.put("status","NOT_APPLICABLE_OR_UNAVAILABLE");
        else observed.put("hitAt5",evidence.stream().mapToDouble(r -> r.path("deterministic").path("top5").path("hit").asDouble()).average().orElseThrow())
            .put("coverageAt5",evidence.stream().mapToDouble(r -> r.path("deterministic").path("top5").path("coverage").asDouble()).average().orElseThrow())
            .put("completeHitAt5",evidence.stream().filter(r -> "FULL".equals(r.path("deterministic").path("top5").path("state").asText())).count()/(double)evidence.size());
        result.put("judgeFailures",rows.stream().filter(r -> Set.of("JUDGE_FAILED","JUDGE_EVIDENCE_INVALID").contains(r.path("semantic").path("status").asText())).count());
        result.put("judgeUnavailable",rows.stream().filter(r -> !"ASSESSED".equals(r.path("semantic").path("status").asText())).count());
        return result;
    }
    private static Map<String,JsonNode> index(JsonNode rows) {
        Map<String,JsonNode> result=new HashMap<>();
        for(JsonNode row:rows) if(result.put(row.path("id").asText(),row)!=null) throw new IllegalArgumentException("Duplicate captured case");
        return result;
    }
    private void losses(ArrayNode findings,JsonNode units,JsonNode before,JsonNode after,String reason) {
        Set<String> present=new HashSet<>(); for(JsonNode id:before.path("coveredUnits")) present.add(id.asText());
        for(JsonNode id:after.path("missingUnits")) if(present.contains(id.asText())) finding(findings,reason,id.asText());
    }
    private void assemblyLosses(ArrayNode findings,JsonNode units,JsonNode before,JsonNode after,JsonNode merged,JsonNode assembled) {
        Set<String> present=new HashSet<>(); for(JsonNode id:before.path("coveredUnits")) present.add(id.asText());
        for(JsonNode missing:after.path("missingUnits")) if(present.contains(missing.asText())) {
            Set<String> causes=new HashSet<>();
            for(JsonNode unit:units) if(unit.path("id").equals(missing)) for(JsonNode anchor:unit.path("anchors")) for(JsonNode passage:merged.path("passages"))
                for(JsonNode source:passage.path("sources")) if(anchor.path("sourceId").equals(source.path("sourceId")))
                    for(JsonNode decision:assembled.path("decisions")) if(passage.path("id").equals(decision.path("id"))) causes.add(decision.path("reason").asText());
            String reason=causes.equals(Set.of("TOP_K"))?"CONTEXT_TOP_K_WINDOW_LOSS":causes.contains("TOKEN_BUDGET")?"CONTEXT_BUDGET_LOSS":
                causes.contains("LEGACY_CHAR_LIMIT")?"LEGACY_CLIPPING_LOSS":"ASSEMBLY_LOSS";
            finding(findings,reason,missing.asText());
        }
    }
    private ObjectNode diagnosticCohorts(List<ObjectNode> rows) {
        ObjectNode result=json.createObjectNode().put("interpretation","Observed association; exact-match mismatch is not proof of hallucination or evidence-utilization failure");
        for(String state:List.of("FULL","PARTIAL","NONE","UNAVAILABLE")) {
            var group=rows.stream().filter(r -> state.equals(r.path("deterministic").path("wire").path("state").asText("UNAVAILABLE"))).toList();
            ObjectNode cohort=result.putObject(state).put("count",group.size());
            cohort.put("nativeAnswerCount",group.stream().filter(r -> r.path("nativeAnswer").path("generationComplete").asBoolean()).count());
            cohort.put("exactMatchCorrect",group.stream().filter(r -> r.path("nativeAnswer").path("em").asDouble()==1).count());
            for(String verdict:List.of("correct","partial","wrong")) cohort.put("judge_"+verdict,group.stream().filter(r -> verdict.equals(r.path("semantic").path("assessment").path("verdict").asText())).count());
        }
        return result;
    }
    private ObjectNode supportSummary(List<ObjectNode> rows) {
        ObjectNode result=json.createObjectNode().put("method",EvaluationSupportJudge.VERSION).put("total",rows.size());
        result.put("assessed",rows.stream().filter(r -> "ASSESSED".equals(r.path("answerSupport").path("status").asText())).count());
        result.put("failedOrInvalid",rows.stream().filter(r -> Set.of("FAILED","INVALID_OR_LABEL_VERSION_CHANGED").contains(r.path("answerSupport").path("status").asText())).count());
        for(String field:List.of("judgeClaimSupportRate","judgeAnswerCompleteness")) {
            var measured=rows.stream().map(r -> r.path("answerSupport").path("metrics").path(field)).filter(JsonNode::isNumber).toList();
            ObjectNode metric=result.putObject(field).put("count",measured.size()).put("unavailable",rows.size()-measured.size());
            if(measured.isEmpty()) metric.put("status","NOT_APPLICABLE_OR_NOT_ASSESSED");
            else metric.put("mean",measured.stream().mapToDouble(JsonNode::asDouble).average().orElseThrow());
        }
        return result;
    }
    private void finding(ArrayNode findings,String reason,String unit) { findings.addObject().put("reason",reason).put("unit",unit); }
    private static String safeReason(Exception error) { return error instanceof IllegalStateException?Objects.toString(error.getMessage(),"EVIDENCE_INVALID"):"EVIDENCE_INVALID"; }
}
