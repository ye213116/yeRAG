package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RankFusionEvidenceTest {
    Candidate candidate(String id,double score) { return new Candidate(id,"same-md5",0,"1","file","text",score,Map.of(),List.of(id)); }
    RouteResult route(String name,Status status,Candidate... candidates) { return new RouteResult(name,status,List.of(candidates),1,null); }
    @Test void fusesRanksAndKeepsRawScoresAndDistinctEsIdentities() {
        var result=RankFusion.fuse(List.of(route("bm25",Status.SUCCEEDED,candidate("i:a",100),candidate("i:b",50)),
            route("vector",Status.SUCCEEDED,candidate("i:b",0.9),candidate("i:c",0.8))),60);
        assertEquals("i:b",result.candidates().get(0).id()); assertEquals(3,result.candidates().size());
        assertEquals(Map.of("bm25",50.0,"vector",0.9),result.candidates().get(0).routeScores());
        assertEquals(1.0/61+1.0/62,result.candidates().get(0).score(),1e-12);
    }
    @Test void disabledRouteDoesNotContributeCandidatesAndAllEnabledFailuresStayFailed() {
        var routes=List.of(route("bm25",Status.SUCCEEDED,candidate("i:a",100)),route("vector",Status.FAILED));
        assertEquals(Status.FAILED,RankFusion.fuse(routes,60,Map.of("bm25",0.0,"vector",1.0)).status());
        assertThrows(IllegalArgumentException.class,() -> RankFusion.fuse(routes,Double.NaN));
    }
}
