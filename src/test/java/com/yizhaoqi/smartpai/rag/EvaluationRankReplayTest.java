package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRankReplayTest {
    @TempDir Path root;
    @Test void replayUsesProductionFusionAndRejectsRoutesThatNoLongerReproduceTheCapturedWindow() throws Exception {
        var f=new PhaseOneFixture(root,3);
        List<Candidate> text=new ArrayList<>(f.top),vector=new ArrayList<>(text);Collections.reverse(vector);
        List<RouteResult> routes=List.of(new RouteResult("bm25",Status.SUCCEEDED,text,1,null),new RouteResult("vector",Status.SUCCEEDED,vector,1,null));
        var ranked=RankFusion.fuse(routes,60,Map.of("bm25",1.0,"vector",1.0)).candidates();
        var request=f.json.createObjectNode().put("question",f.sample.path("question").asText()).put("topK",5).put("recallStrategy","HYBRID");
        request.putObject("options").put("rrfK",60).put("rewrite",false).put("rerank",false);
        f.event(f.pipeline,"request",request);
        f.event(f.pipeline,"retrievalConfig",f.json.createObjectNode().put("mode","RRF").put("bm25Weight",1).put("knnWeight",1));
        f.stage(f.pipeline,"RETRIEVE",f.json.createObjectNode().set("routes",f.json.valueToTree(routes)));
        f.stage(f.pipeline,"RERANK",f.json.createObjectNode().set("candidates",f.json.valueToTree(ranked)));
        List<RankedChunk> snapshot=new ArrayList<>();for(int i=0;i<ranked.size();i++)snapshot.add(RankedChunk.of(ranked.get(i),i+1,"RRF"));
        f.event(f.pipeline,"TOP_K",f.json.createObjectNode().put("k",5).set("candidates",f.json.valueToTree(snapshot)));
        f.capture.putObject("experiment").put("baseline",true).put("recallStrategy","HYBRID");f.save();
        Path grid=root.resolve("grid.json");Files.writeString(grid,"[{\"name\":\"baseline\",\"constant\":60,\"bm25Weight\":1,\"vectorWeight\":1}]");
        EvaluationRankReplay.main(new String[]{root.toString(),grid.toString(),root.resolve("replay").toString()});
        var report=f.json.readTree(root.resolve("replay/replay.json").toFile());
        assertTrue(report.path("defaultReplayVerified").asBoolean());assertEquals(1,report.path("configurations").path(0).path("completeHitAt5").asDouble());
        assertEquals(1,report.path("configurations").path(0).path("top5MatchingCapture").asInt());
        f.event(f.pipeline,"retrievalConfig",f.json.createObjectNode().put("mode","RRF").put("bm25Weight",.01).put("knnWeight",1));f.save();
        assertThrows(IllegalStateException.class,() -> EvaluationRankReplay.main(new String[]{root.toString(),grid.toString(),root.resolve("corrupt-replay").toString()}));
        assertFalse(Files.exists(root.resolve("corrupt-replay")));
    }
    @Test void replayCannotTurnAFailedVectorRouteIntoAnApparentlySuccessfulWeightExperiment() {
        assertThrows(IllegalArgumentException.class,() -> EvaluationRankReplay.fuse(List.of(
            new RouteResult("bm25",Status.SUCCEEDED,List.of(),0,null),new RouteResult("vector",Status.FAILED,List.of(),0,"outage")),60,1,1));
    }
}
