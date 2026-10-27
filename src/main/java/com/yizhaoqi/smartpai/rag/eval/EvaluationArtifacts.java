package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;

/** Hash-checked frozen input, independent of any Judge response. */
public final class EvaluationArtifacts {
    private final Path root;
    private final ObjectMapper json;
    public EvaluationArtifacts(Path root,ObjectMapper json) { this.root=root; this.json=json; }
    public Path directory() { return root; }
    public JsonNode read(String id) throws Exception {
        UUID.fromString(id);
        JsonNode run=json.readTree(root.resolve(id+".json").toFile());
        if(!id.equals(run.path("runId").asText()) || !"COMPLETE".equals(run.path("evidenceStatus").asText())) throw new IllegalStateException("Incomplete run artifact");
        for(JsonNode event:run.path("events")) if(!EvidenceRecorder.hash(event.path("data").toString()).equals(event.path("sha256").asText())) throw new IllegalStateException("Corrupt event");
        return run;
    }
    public static JsonNode event(JsonNode run,String name) {
        JsonNode found=MissingNode.getInstance();
        for(JsonNode event:run.path("events")) if(name.equals(event.path("name").asText())) {
            if(!found.isMissingNode()) throw new IllegalStateException("Ambiguous event: "+name);
            found=event.path("data");
        }
        return found;
    }
    public static JsonNode stage(JsonNode run,String name) { return event(run,name).path("outcome").path("evidence"); }
    public static JsonNode topFive(JsonNode pipeline) {
        JsonNode top=event(pipeline,"TOP_K"),ranked=stage(pipeline,"RERANK").path("candidates");
        if(top.path("k").asInt()!=5 || !top.path("candidates").isArray() || !ranked.isArray()) throw new IllegalStateException("Top5 snapshot unavailable");
        JsonNode candidates=top.get("candidates");
        if(candidates.size()!=Math.min(5,ranked.size())) throw new IllegalStateException("Top5 size mismatch");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<candidates.size();i++) {
            JsonNode c=candidates.get(i),original=ranked.get(i);
            if(!ids.add(c.path("id").asText()) || c.path("rank").asInt()!=i+1 || !c.path("id").equals(original.path("id"))
                    || !c.path("text").equals(original.path("text")) || !c.path("owner").equals(original.path("owner"))
                    || !c.path("esIndex").equals(original.path("esIndex")) || !c.path("esHitId").equals(original.path("esHitId"))
                    || !c.path("fileMd5").equals(original.path("fileMd5")) || c.path("score").asDouble()!=original.path("score").asDouble()
                    || !EvidenceRecorder.hash(c.path("text").asText()).equals(c.path("sourceHash").asText())) throw new IllegalStateException("Top5 snapshot mismatch");
            for(String field:List.of("owner","fileMd5","esIndex","esHitId","scoreKind")) EvaluationCases.requireText(c,field);
            if(!c.path("id").asText().equals(c.path("esIndex").asText()+":"+c.path("esHitId").asText())
                    || !Set.of("RRF","RERANK","ES","BM25","VECTOR").contains(c.path("scoreKind").asText())
                    || !c.path("score").isNumber() || !Double.isFinite(c.path("score").asDouble()))
                throw new IllegalStateException("Invalid Top5 source identity/score");
            if(!c.path("chunkId").isIntegralNumber() || c.path("chunkId").asInt()!=original.path("chunkIndex").asInt(-1)) throw new IllegalStateException("Top5 chunk ID mismatch");
        }
        return candidates;
    }
    public static String answer(JsonNode pipeline) { return stage(pipeline,"GENERATE").path("answer").asText(); }
}
