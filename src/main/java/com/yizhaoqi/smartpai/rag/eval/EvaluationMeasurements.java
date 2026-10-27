package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.yizhaoqi.smartpai.rag.eval.EvaluationArtifacts.*;

/** Measurements from bound artifacts; actual provider usage and token estimates never share a denominator. */
public final class EvaluationMeasurements {
    private EvaluationMeasurements() {}
    public static ObjectNode measure(ObjectMapper json,EvaluationArtifacts artifacts,JsonNode attempt,String owner) {
        ObjectNode result=json.createObjectNode().put("status","UNAVAILABLE");
        if(attempt==null || !attempt.path("corpusVerified").asBoolean()) return result;
        try {
            String id=attempt.path("pipelineRunId").asText(); JsonNode pipeline=artifacts.read(id);
            if(!owner.equals(pipeline.path("owner").asText()) || !"pipeline".equals(pipeline.path("kind").asText())) return result;
            if(!pipeline.path("elapsedMs").isNumber() || pipeline.path("elapsedMs").asLong()<0) return result;
            result.put("status","AVAILABLE").put("pipelineMs",pipeline.path("elapsedMs").asLong());
            result.set("requestOptions",event(pipeline,"request").path("options").deepCopy());
            result.set("retrievalConfig",event(pipeline,"retrievalConfig").deepCopy());
            result.put("recallStrategy",event(pipeline,"request").path("recallStrategy").asText("HYBRID"));
            ObjectNode stages=result.putObject("stageMs");
            for(JsonNode e:pipeline.path("events")) if(Set.of("QUERY","RETRIEVE","RERANK","MERGE","ASSEMBLE","GENERATE").contains(e.path("name").asText())
                    && e.path("data").path("elapsedMs").isNumber()) stages.put(e.path("name").asText(),e.path("data").path("elapsedMs").asLong());
            JsonNode assembled=stage(pipeline,"ASSEMBLE");
            if(assembled.path("estimatedTokens").isNumber()) {
                result.put("contextEstimatedTokens",assembled.path("estimatedTokens").asLong());
                result.put("contextTokenCountKind",assembled.path("tokenCountKind").asText());
            }
            if(attempt.hasNonNull("modelRunId")) {
                JsonNode model=child(artifacts,attempt.path("modelRunId").asText(),id,owner,"model-call");
                JsonNode generation=event(model,"generation"); result.set("generationUsage",usage(json,generation));
                result.put("generationModel",event(model,"request").path("model").asText());
                ObjectNode config=result.putObject("generationConfiguration");
                for(String field:List.of("model","temperature","top_p","max_tokens")) if(event(model,"request").has(field)) config.set(field,event(model,"request").get(field));
                if(generation.path("firstAnswerMs").isNumber()) {
                    long first=model.path("startedAt").asLong()-pipeline.path("startedAt").asLong()+generation.path("firstAnswerMs").asLong();
                    if(first>=0) result.put("firstAnswerFromPipelineStartMs",first);
                }
            } else result.putObject("generationUsage").put("status","NOT_CALLED");
            measureJudge(json,artifacts,attempt,id,owner,"judgeRunId","judge",result);
            measureJudge(json,artifacts,attempt,id,owner,"supportRunId","answer-support",result);
        } catch(Exception invalid) { result.put("measurementError","INCOMPLETE_OR_INVALID_MEASUREMENT_ARTIFACT"); }
        return result;
    }
    private static JsonNode child(EvaluationArtifacts artifacts,String id,String parent,String owner,String kind) throws Exception {
        JsonNode child=artifacts.read(id);
        if(!parent.equals(child.path("parentRunId").asText()) || !owner.equals(child.path("owner").asText()) || !kind.equals(child.path("kind").asText()))
            throw new IllegalStateException("Measurement parent/owner mismatch");
        return child;
    }
    private static void measureJudge(ObjectMapper json,EvaluationArtifacts artifacts,JsonNode attempt,String pipeline,String owner,String field,String kind,ObjectNode output) throws Exception {
        if(!attempt.hasNonNull(field)) return;
        JsonNode judge=child(artifacts,attempt.path(field).asText(),pipeline,owner,kind);
        String prefix=kind.equals("judge")?"judge":"support";
        if(judge.path("elapsedMs").isNumber() && judge.path("elapsedMs").asLong()>=0) output.put(prefix+"Ms",judge.path("elapsedMs").asLong());
        JsonNode request=event(judge,"request"); output.put(prefix+"PromptVersion",request.path("promptVersion").asText());
        for(JsonNode e:judge.path("events")) if("child".equals(e.path("name").asText())) {
            JsonNode model=child(artifacts,e.path("data").path("runId").asText(),judge.path("runId").asText(),owner,"model-call");
            output.set(prefix+"Usage",usage(json,event(model,"generation")));
            output.put(prefix+"Model",event(model,"request").path("model").asText());
        }
    }
    private static ObjectNode usage(ObjectMapper json,JsonNode generation) {
        ObjectNode output=json.createObjectNode().put("status","UNAVAILABLE"); JsonNode raw=generation.path("usage");
        if(!"REPORTED".equals(generation.path("usageStatus").asText())) return output;
        for(String field:List.of("prompt_tokens","completion_tokens","total_tokens")) if(!raw.path(field).isIntegralNumber() || raw.path(field).asLong()<0) return output;
        return output.put("status","REPORTED").put("promptTokens",raw.path("prompt_tokens").asLong())
            .put("completionTokens",raw.path("completion_tokens").asLong()).put("totalTokens",raw.path("total_tokens").asLong());
    }
    public static ObjectNode summary(ObjectMapper json,List<ObjectNode> rows) {
        ObjectNode output=json.createObjectNode().put("percentileMethod","NEAREST_RANK");
        output.set("pipelineLatencyMs",stats(json,rows,"pipelineMs"));
        output.set("firstAnswerLatencyMs",stats(json,rows,"firstAnswerFromPipelineStartMs"));
        output.set("contextEstimatedTokens",stats(json,rows,"contextEstimatedTokens"));
        output.put("tokenScope","GENERATION_AND_EVALUATION_JUDGES_ONLY; embedding/rerank billing excluded; no monetary cost inferred");
        for(String field:List.of("generationUsage","judgeUsage","supportUsage")) {
            ObjectNode usage=output.putObject(field); long count=0,prompt=0,completion=0,total=0;
            for(JsonNode row:rows) {
                JsonNode value=row.path("performance").path(field);
                if(!"REPORTED".equals(value.path("status").asText())) continue;
                count++; prompt+=value.path("promptTokens").asLong(); completion+=value.path("completionTokens").asLong(); total+=value.path("totalTokens").asLong();
            }
            usage.put("reportedCalls",count).put("missingOrNotCalled",rows.size()-count);
            if(count>0) usage.put("promptTokens",prompt).put("completionTokens",completion).put("totalTokens",total);
        }
        return output;
    }
    private static ObjectNode stats(ObjectMapper json,List<ObjectNode> rows,String field) {
        List<Double> values=rows.stream().map(r -> r.path("performance").path(field)).filter(JsonNode::isNumber).map(JsonNode::asDouble).sorted().toList();
        ObjectNode result=json.createObjectNode().put("count",values.size()).put("unavailable",rows.size()-values.size());
        if(!values.isEmpty()) result.put("mean",values.stream().mapToDouble(Double::doubleValue).average().orElseThrow())
            .put("p50",values.get((int)Math.ceil(values.size()*.5)-1)).put("p95",values.get((int)Math.ceil(values.size()*.95)-1));
        return result;
    }
}
