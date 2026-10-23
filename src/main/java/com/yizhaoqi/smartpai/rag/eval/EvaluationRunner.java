package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Offline scoring of frozen runs. Labels are human/test-fixture input, never inferred from an LLM answer. */
public final class EvaluationRunner {
    private final ObjectMapper json=new ObjectMapper();
    private final Path artifacts;
    public EvaluationRunner(Path artifacts) { this.artifacts=artifacts.toAbsolutePath(); }
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("Usage: cases.jsonl artifact-directory output-directory");
        new EvaluationRunner(Path.of(args[1])).run(Path.of(args[0]),Path.of(args[2]));
    }
    public void run(Path cases,Path output) throws Exception {
        List<ObjectNode> rows=new ArrayList<>();
        for (ObjectNode sample : EvaluationCases.read(cases,json)) rows.add(evaluate(sample));
        Files.createDirectories(output);
        Files.write(output.resolve("results.jsonl"),rows.stream().map(JsonNode::toString).toList());
        ObjectNode summary=json.createObjectNode(); summary.put("total",rows.size());
        ObjectNode variants=summary.putObject("variants");
        rows.stream().collect(Collectors.groupingBy(r -> r.path("variant").asText())).forEach((variant,group) -> {
            ObjectNode metrics=variants.putObject(variant); metrics.put("total",group.size());
            metrics.put("scored",group.stream().filter(r -> r.path("scored").asBoolean()).count());
            for(String field:List.of("recall","hit","mrr","ndcg","contextCoverage","answerCorrect","expectedFactsMatch","elapsedMs","degraded","citationPrecision","citationRecall","evidenceSupported","inputTokens","outputTokens","totalTokens","firstAnswerMs","modelElapsedMs")) {
                var values=group.stream().filter(r -> r.path("scored").asBoolean()).map(r -> r.get(field)).filter(Objects::nonNull).filter(n -> n.isNumber() || n.isBoolean()).mapToDouble(n -> n.isBoolean()?(n.asBoolean()?1:0):n.asDouble()).toArray();
                if(values.length>0) metrics.put("mean_"+field,Arrays.stream(values).average().orElseThrow());
                metrics.put("count_"+field,values.length);
            }
            ObjectNode diagnoses=metrics.putObject("diagnoses");
            group.stream().collect(Collectors.groupingBy(r -> r.path("diagnosis").asText(),Collectors.counting())).forEach(diagnoses::put);
            ObjectNode prefixes=metrics.putObject("prefixGroups");
            group.stream().filter(r -> r.has("bucket")).collect(Collectors.groupingBy(r -> prefix(r.path("caseId").asText())))
                .forEach((prefix,casesInGroup) -> {
                    ObjectNode counts=prefixes.putObject(prefix).put("total",casesInGroup.size());
                    ObjectNode buckets=counts.putObject("provisionalBuckets");
                    casesInGroup.stream().collect(Collectors.groupingBy(r -> r.path("bucket").asText(),Collectors.counting())).forEach(buckets::put);
                    counts.put("retrievalHitCount",casesInGroup.stream().filter(r -> r.path("phraseRetrieval").path("hitAtK").asBoolean()).count());
                    counts.put("judgeReviewedCount",casesInGroup.stream().filter(r -> r.path("answerCorrect").isBoolean()).count());
                });
        });
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(),summary);
    }
    public ObjectNode evaluate(JsonNode sample) {
        ObjectNode row=json.createObjectNode().put("caseId",sample.path("caseId").asText(sample.path("id").asText()))
            .put("variant",sample.path("variant").asText("baseline")).put("pipelineRunId",sample.path("pipelineRunId").asText()).put("scored",false);
        boolean phraseLabels=sample.has("expectedEvidence");
        var phrases=new PhraseEvidenceEvaluator(json);
        try { if(phraseLabels) phrases.validate(sample); }
        catch(IllegalArgumentException invalid) { return row.put("diagnosis","LABELS_INVALID"); }
        try {
            JsonNode pipeline=read(sample.path("pipelineRunId").asText());
            if(!Set.of("SUCCEEDED","DEGRADED","NO_EVIDENCE").contains(pipeline.path("status").asText()))
                return row.put("diagnosis","RUN_"+pipeline.path("status").asText("UNKNOWN"));
            JsonNode request=event(pipeline,"request");
            if(sample.hasNonNull("corpusVersion") && !sample.get("corpusVersion").asText().equals(pipeline.path("corpusVersion").asText()))
                return row.put("diagnosis","CORPUS_VERSION_MISMATCH");
            JsonNode retrieval=stage(pipeline,"RETRIEVE"), rerank=stage(pipeline,"RERANK"), merge=stage(pipeline,"MERGE"), context=stage(pipeline,"ASSEMBLE");
            Map<String,Integer> relevance=new LinkedHashMap<>();
            sample.path("relevance").fields().forEachRemaining(e -> { if(e.getValue().asInt()>0) relevance.put(e.getKey(),e.getValue().asInt()); });
            if(relevance.isEmpty() && !phraseLabels) return row.put("diagnosis","LABELS_MISSING");
            int k=sample.path("k").asInt(request.path("topK").asInt(5));
            if(k<1) throw new IllegalArgumentException("Invalid K");
            if(phraseLabels && k>request.path("topK").asInt(5)) return row.put("diagnosis","TOP_K_MISMATCH");
            List<String> recalled=ids(retrieval.path("candidates")), ranked=ids(rerank.path("candidates"));
            row.put("k",k).put("scored",true).put("elapsedMs",pipeline.path("elapsedMs").asLong())
                .put("degraded","DEGRADED".equals(pipeline.path("status").asText()));
            if(!relevance.isEmpty()) {
                metrics(recalled,relevance,k).fields().forEachRemaining(e -> row.set(e.getKey(),e.getValue()));
                row.set("rerank",metrics(ranked,relevance,k));
                ObjectNode routes=row.putObject("routes");
                for(JsonNode route:retrieval.path("routes")) {
                    if("SUCCEEDED".equals(route.path("status").asText())) routes.set(route.path("route").asText(),metrics(ids(route.path("candidates")),relevance,k));
                    else routes.putObject(route.path("route").asText()).put("status",route.path("status").asText());
                }
            }
            if(phraseLabels) row.set("phraseRetrieval",phrases.metrics(sample,rerank.path("candidates"),k));
            Double mergeCoverage=coverage(sample.path("ranges"),merge.path("passages"));
            Double contextCoverage=coverage(sample.path("ranges"),context.path("passages"));
            if(contextCoverage!=null) row.put("contextCoverage",contextCoverage);
            row.put("budgetDropRate",budgetDropRate(context.path("decisions")));
            Set<String> required=new HashSet<>();
            for(JsonNode id:sample.path("requiredIds")) required.add(id.asText());
            if(required.isEmpty()) for(JsonNode range:sample.path("ranges")) required.add(range.path("sourceId").asText());
            String diagnosis;
            if(required.isEmpty()) diagnosis="REQUIRED_LABELS_MISSING";
            else if(sample.path("queryDrift").asBoolean()) diagnosis="QUERY_DRIFT_REVIEWED";
            else if(!new HashSet<>(recalled).containsAll(required)) diagnosis="RETRIEVAL_MISS";
            else if(!new HashSet<>(ranked).containsAll(required)) diagnosis="RERANK_DROP";
            else if(mergeCoverage!=null && mergeCoverage<1) diagnosis="MERGE_LOSS";
            else if(contextCoverage!=null && contextCoverage<1) diagnosis="CONTEXT_LOSS";
            else diagnosis=contextCoverage==null?"RANGE_LABELS_MISSING":"CONTEXT_RETAINED";
            if(phraseLabels && required.isEmpty()) diagnosis=phrases.diagnosis(sample,retrieval.path("candidates"),rerank.path("candidates"),merge.path("passages"),context.path("passages"),k);
            boolean verifiedAnswer=false;
            if(sample.hasNonNull("modelRunId")) {
                JsonNode model=read(sample.get("modelRunId").asText());
                if(!"SUCCEEDED".equals(model.path("status").asText())) return row.put("scored",false).put("diagnosis","MODEL_"+model.path("status").asText());
                if(!model.path("owner").equals(pipeline.path("owner")) || !rootId(model).equals(rootId(pipeline)))
                    throw new IllegalStateException("Model/pipeline lineage mismatch");
                JsonNode wire=event(model,"request");
                event(model,"dispatch");
                String sent=StreamSupport.stream(wire.path("messages").spliterator(),false).map(m -> m.path("content").asText()).collect(Collectors.joining("\n"));
                // Tool observations contain escaped JSON, so check the exact observation too.
                String observation=context.path("observation").asText();
                boolean seen=!context.path("passages").isEmpty() && ((!context.path("content").asText().isEmpty() && sent.contains(context.path("content").asText())) || (!observation.isEmpty() && sent.contains(observation)));
                row.put("wireContextConfirmed",seen);
                JsonNode generation=optionalEvent(model,"generation");
                row.put("modelElapsedMs",model.path("elapsedMs").asLong());
                if(generation.path("firstAnswerMs").isNumber()) row.set("firstAnswerMs",generation.get("firstAnswerMs"));
                JsonNode usage=generation.get("usage");
                if(usage==null || usage.isNull()) usage=optionalEvent(model,"response").get("usage");
                row.put("usageStatus",usage==null || usage.isNull()?"UNAVAILABLE":"REPORTED");
                if(usage!=null && !usage.isNull()) {
                    for(var mapping:Map.of("prompt_tokens","inputTokens","completion_tokens","outputTokens","total_tokens","totalTokens").entrySet())
                        if(usage.path(mapping.getKey()).isNumber()) row.set(mapping.getValue(),usage.get(mapping.getKey()));
                }
                String answer=generation.path("answer").asText(null);
                if(answer==null) answer=optionalEvent(model,"response").path("choices").path(0).path("message").path("content").asText("");
                verifiedAnswer=!answer.isBlank();
                if(sample.path("expectedFacts").isArray() && !sample.get("expectedFacts").isEmpty()) {
                    int matches=0; for(JsonNode fact:sample.get("expectedFacts")) if(answer.contains(fact.asText())) matches++;
                    row.put("expectedFactsMatch",(double)matches/sample.get("expectedFacts").size());
                }
                if(sample.path("evidenceSupported").isBoolean()) row.put("evidenceSupported",sample.get("evidenceSupported").asBoolean());
                Set<Integer> expectedCitations=new HashSet<>();
                for(JsonNode citation:sample.path("expectedCitations")) expectedCitations.add(citation.asInt());
                if(!expectedCitations.isEmpty()) {
                    Set<Integer> actual=new HashSet<>();
                    var matcher=java.util.regex.Pattern.compile("\\[(?:来源#)?([0-9]+)\\]").matcher(answer);
                    while(matcher.find()) actual.add(Integer.parseInt(matcher.group(1)));
                    long correct=actual.stream().filter(expectedCitations::contains).count();
                    row.put("citationPrecision",actual.isEmpty()?0:(double)correct/actual.size());
                    row.put("citationRecall",(double)correct/expectedCitations.size());
                }
                if(sample.path("answerCorrect").isBoolean()) {
                    row.put("answerCorrect",sample.get("answerCorrect").asBoolean());
                    if(diagnosis.equals("CONTEXT_RETAINED") && seen && !sample.get("answerCorrect").asBoolean()) diagnosis="GENERATION_ERROR_REVIEWED";
                }
                if(diagnosis.equals("CONTEXT_RETAINED") && !seen) diagnosis="WIRE_CONTEXT_UNCONFIRMED";
            } else {
                row.put("wireContextConfirmed",false);
                JsonNode generated=optionalEvent(pipeline,"GENERATE").path("outcome");
                verifiedAnswer="NO_EVIDENCE".equals(generated.path("status").asText())
                    && "FIXED_ABSTENTION".equals(generated.path("reason").asText())
                    && context.path("passages").isArray() && context.path("passages").isEmpty()
                    && !generated.path("evidence").path("answer").asText().isBlank();
            }
            if(phraseLabels) {
                if(verifiedAnswer) {
                    JsonNode verdict=sample.path("judge").path("verdict");
                    if(!verdict.isMissingNode()) {
                        if(!Set.of("correct","partial","wrong").contains(verdict.asText())) return row.put("scored",false).put("diagnosis","LABELS_INVALID");
                        boolean correct="correct".equals(verdict.asText());
                        if(sample.path("answerCorrect").isBoolean() && sample.get("answerCorrect").asBoolean()!=correct)
                            return row.put("scored",false).put("diagnosis","LABELS_CONFLICT");
                        row.put("judgeVerdict",verdict.asText()).put("answerCorrect",correct);
                    } else if(sample.path("answerCorrect").isBoolean()) row.set("answerCorrect",sample.get("answerCorrect"));
                    if(diagnosis.equals("CONTEXT_RETAINED") && row.path("wireContextConfirmed").asBoolean()
                            && row.path("answerCorrect").isBoolean() && !row.get("answerCorrect").asBoolean()) diagnosis="GENERATION_ERROR_REVIEWED";
                } else row.remove("answerCorrect");
                phrases.classify(sample,row,request);
            }
            return row.put("diagnosis",diagnosis);
        } catch(Exception missing) {
            // Avoid using partially calculated metrics when any required artifact is missing/corrupt.
            ObjectNode incomplete=json.createObjectNode();
            incomplete.set("caseId",row.get("caseId")); incomplete.set("variant",row.get("variant"));
            incomplete.set("pipelineRunId",row.get("pipelineRunId"));
            return incomplete.put("scored",false).put("diagnosis","EVIDENCE_INCOMPLETE");
        }
    }
    private static String prefix(String id) {
        for(String prefix:List.of("cross-doc","conflict","multi","fact","para","neg","acl"))
            if(id.startsWith(prefix+"-")) return prefix;
        return "other";
    }
    private String rootId(JsonNode run) throws Exception {
        Set<String> seen=new HashSet<>(); String owner=run.path("owner").asText();
        while(run.hasNonNull("parentRunId") && !run.path("parentRunId").asText().isBlank()) {
            if(!seen.add(run.path("runId").asText())) throw new IllegalStateException("Cyclic run lineage");
            run=read(run.path("parentRunId").asText());
            if(!owner.equals(run.path("owner").asText())) throw new IllegalStateException("Owner mismatch");
        }
        return run.path("runId").asText();
    }
    private JsonNode read(String id) throws Exception {
        UUID.fromString(id);
        JsonNode document=json.readTree(artifacts.resolve(id+".json").toFile());
        if(!id.equals(document.path("runId").asText()) || !"COMPLETE".equals(document.path("evidenceStatus").asText())) throw new IllegalStateException("Incomplete evidence");
        for(JsonNode event:document.path("events")) if(!EvidenceRecorder.hash(event.path("data").toString()).equals(event.path("sha256").asText())) throw new IllegalStateException("Corrupt evidence");
        return document;
    }
    private static JsonNode optionalEvent(JsonNode document,String name) {
        for(JsonNode event:document.path("events")) if(name.equals(event.path("name").asText())) return event.path("data");
        return MissingNode.getInstance();
    }
    private static JsonNode event(JsonNode document,String name) {
        JsonNode data=optionalEvent(document,name);
        if(data.isMissingNode()) throw new IllegalStateException("Missing stage: "+name);
        return data;
    }
    private static JsonNode stage(JsonNode document,String name) {
        JsonNode stage=event(document,name).path("outcome").path("evidence");
        if(stage.isMissingNode()) throw new IllegalStateException("Missing outcome");
        return stage;
    }
    private static List<String> ids(JsonNode candidates) {
        if(!candidates.isArray()) throw new IllegalStateException("Missing candidates");
        return StreamSupport.stream(candidates.spliterator(),false).map(c -> c.path("id").asText()).distinct().toList();
    }
    public ObjectNode metrics(List<String> ranking,Map<String,Integer> relevance,int k) {
        var selected=ranking.stream().distinct().limit(k).toList();
        long hits=selected.stream().filter(relevance::containsKey).count(); double reciprocal=0,dcg=0;
        for(int i=0;i<selected.size();i++) { int grade=relevance.getOrDefault(selected.get(i),0); if(grade>0 && reciprocal==0) reciprocal=1.0/(i+1); dcg+=(Math.pow(2,grade)-1)/log2(i+2); }
        var ideal=relevance.values().stream().sorted(Comparator.reverseOrder()).limit(k).toList(); double idcg=0;
        for(int i=0;i<ideal.size();i++) idcg+=(Math.pow(2,ideal.get(i))-1)/log2(i+2);
        return json.createObjectNode().put("recall",(double)hits/relevance.size()).put("hit",hits>0?1:0).put("mrr",reciprocal).put("ndcg",idcg==0?0:dcg/idcg);
    }
    private static double log2(int value) { return Math.log(value)/Math.log(2); }
    public static Double coverage(JsonNode expected,JsonNode passages) {
        if(!expected.isArray() || expected.isEmpty()) return null;
        long total=0,covered=0;
        for(JsonNode range:expected) {
            int start=range.path("start").asInt(),end=range.path("end").asInt();
            if(start<0 || end<=start) throw new IllegalArgumentException("Invalid UTF-16 range");
            List<int[]> spans=new ArrayList<>();
            for(JsonNode passage:passages) for(JsonNode source:passage.path("sources"))
                if(source.path("sourceId").asText().equals(range.path("sourceId").asText())
                        && (!range.hasNonNull("sourceHash") || range.path("sourceHash").equals(source.path("sourceHash")))) {
                    int left=Math.max(start,source.path("sourceStart").asInt()),right=Math.min(end,source.path("sourceEnd").asInt());
                    if(left<right) spans.add(new int[]{left,right});
                }
            spans.sort(Comparator.comparingInt(a -> a[0])); int cursor=start;
            for(int[] span:spans) { covered+=Math.max(0,span[1]-Math.max(cursor,span[0])); cursor=Math.max(cursor,span[1]); }
            total+=end-start;
        }
        return (double)covered/total;
    }
    private static double budgetDropRate(JsonNode decisions) {
        if(decisions.isEmpty()) return 0;
        long count=StreamSupport.stream(decisions.spliterator(),false).filter(d -> "TOKEN_BUDGET".equals(d.path("reason").asText()) || "LEGACY_CHAR_LIMIT".equals(d.path("reason").asText())).count();
        return (double)count/decisions.size();
    }
}
