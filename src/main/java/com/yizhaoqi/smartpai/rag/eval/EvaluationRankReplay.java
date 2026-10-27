package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.RankFusion;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;
import static com.yizhaoqi.smartpai.rag.eval.EvaluationArtifacts.*;

/** Offline replay of frozen route lists through the production fusion algorithm. Never generates answers. */
public final class EvaluationRankReplay {
    private EvaluationRankReplay() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("Usage: RUN GRID_JSON NEW_OUTPUT");
        Path root=Path.of(args[0]); ObjectMapper json=new ObjectMapper();
        var dataset=EvaluationCases.load(root.resolve("dataset"),json);
        JsonNode preflight=json.readTree(root.resolve("preflight.json").toFile()),capture=json.readTree(root.resolve("capture.json").toFile());
        EvaluationPreflight.verify(preflight); EvaluationPreflight.verify(capture);
        if(!dataset.hash().equals(capture.path("datasetHash").asText()) || !capture.path("datasetHash").equals(preflight.path("datasetHash"))
                || !capture.path("preflightSha256").equals(preflight.path("sha256"))
                || !capture.path("corpusFingerprint").equals(preflight.path("corpusFingerprint"))
                || !capture.path("owner").equals(preflight.path("owner")) || !capture.path("experiment").path("baseline").asBoolean()
                || !"HYBRID".equals(capture.path("experiment").path("recallStrategy").asText()))
            throw new IllegalArgumentException("Replay requires a bound HYBRID baseline capture");
        JsonNode grid=json.readTree(Path.of(args[1]).toFile());
        if(!grid.isArray() || grid.isEmpty() || grid.size()>16) throw new IllegalArgumentException("Grid must contain 1..16 configurations");
        Map<String,JsonNode> checks=new HashMap<>(),attempts=new HashMap<>();
        for(JsonNode c:preflight.path("cases")) if(checks.put(c.path("id").asText(),c)!=null) throw new IllegalArgumentException("Duplicate check");
        for(JsonNode c:capture.path("cases")) if(attempts.put(c.path("id").asText(),c)!=null) throw new IllegalArgumentException("Duplicate attempt");
        var artifacts=new EvaluationArtifacts(root.resolve("artifacts"),json); ArrayNode results=json.createArrayNode();
        List<String> rows=new ArrayList<>(); Set<String> names=new HashSet<>();
        for(JsonNode config:grid) {
            String name=EvaluationCases.requireText(config,"name");
            if(!names.add(name) || config.size()!=4 || !config.path("constant").isNumber() || !config.path("bm25Weight").isNumber() || !config.path("vectorWeight").isNumber())
                throw new IllegalArgumentException("Invalid replay configuration");
            double constant=config.path("constant").asDouble(),bm25=config.path("bm25Weight").asDouble(),vector=config.path("vectorWeight").asDouble();
            ObjectNode summary=results.addObject(); summary.set("configuration",config.deepCopy());
            double hit=0,coverage=0,complete=0; int count=0,matches=0;
            for(JsonNode sample:dataset.cases()) {
                String id=sample.path("id").asText(); JsonNode attempt=attempts.get(id),check=checks.get(id);
                if(attempt==null || !attempt.path("corpusVerified").asBoolean() || check==null || !"READY".equals(check.path("status").asText()) || check.path("units").isEmpty())
                    throw new IllegalArgumentException("Incomplete/unverified evidence case: "+id);
                JsonNode pipeline=artifacts.read(attempt.path("pipelineRunId").asText()),request=event(pipeline,"request");
                if(!"HYBRID".equals(request.path("recallStrategy").asText()) || request.path("options").path("rewrite").asBoolean()
                        || request.path("options").path("rerank").asBoolean() || request.path("topK").asInt()!=5
                        || !request.path("question").equals(sample.path("question")) || !capture.path("owner").equals(pipeline.path("owner"))
                        || !preflight.path("corpusVersion").equals(pipeline.path("corpusVersion")) || !"RRF".equals(event(pipeline,"retrievalConfig").path("mode").asText()))
                    throw new IllegalArgumentException("Confounded replay pipeline");
                List<RouteResult> routes=Arrays.asList(json.treeToValue(stage(pipeline,"RETRIEVE").path("routes"),RouteResult[].class));
                List<String> original=ids(topFive(pipeline));
                List<Candidate> top=fuse(routes,constant,bm25,vector);
                // This check ties every sampled route list back to the actual recorded delivery window.
                double originalK=request.path("options").path("rrfK").asDouble(); JsonNode originalConfig=event(pipeline,"retrievalConfig");
                List<Candidate> baseline=fuse(routes,originalK,originalConfig.path("bm25Weight").asDouble(),originalConfig.path("knnWeight").asDouble());
                if(!baseline.stream().map(Candidate::id).toList().equals(original)) throw new IllegalStateException("Default replay differs from actual Top5");
                if(top.stream().map(Candidate::id).toList().equals(original)) matches++;
                JsonNode evidence=EvidencePositions.candidates(json,check.path("units"),json.valueToTree(top));
                hit+=evidence.path("hit").asDouble();coverage+=evidence.path("coverage").asDouble();complete+=evidence.path("completeHit").asDouble();count++;
                ObjectNode row=json.createObjectNode().put("configuration",name).put("id",id); row.set("top5",json.valueToTree(top.stream().map(Candidate::id).toList()));
                row.set("evidence",evidence);rows.add(row.toString());
            }
            summary.put("count",count).put("hitAt5",hit/count).put("coverageAt5",coverage/count).put("completeHitAt5",complete/count).put("top5MatchingCapture",matches);
        }
        Path output=Path.of(args[2]); if(Files.exists(output)) throw new IllegalArgumentException("Fresh replay directory required"); Files.createDirectories(output);
        ObjectNode report=json.createObjectNode().put("method","production-RankFusion-frozen-route-replay-v1").put("datasetHash",dataset.hash())
            .put("captureSha256",EvidenceRecorder.hash(Files.readString(root.resolve("capture.json")))).put("defaultReplayVerified",true);
        report.set("configurations",results);json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("replay.json").toFile(),report);
        Files.write(output.resolve("results.jsonl"),rows);
    }
    public static List<Candidate> fuse(List<RouteResult> routes,double constant,double bm25,double vector) {
        if(routes.size()!=2 || routes.stream().anyMatch(r -> r.status()!=Status.SUCCEEDED)
                || !routes.stream().map(RouteResult::route).collect(java.util.stream.Collectors.toSet()).equals(Set.of("bm25","vector")))
            throw new IllegalArgumentException("Two successful frozen routes required; no hidden degradation");
        return RankFusion.fuse(routes,constant,Map.of("bm25",bm25,"vector",vector)).candidates().stream().limit(5).toList();
    }
    private static List<String> ids(JsonNode candidates) { List<String> result=new ArrayList<>(); for(JsonNode c:candidates) result.add(c.path("id").asText());return result; }
}
