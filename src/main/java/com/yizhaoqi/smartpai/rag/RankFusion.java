package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import java.util.*;

public final class RankFusion {
    private RankFusion() {}
    public static RetrievalResult fuse(List<RouteResult> routes, double constant) {
        return fuse(routes,constant,Map.of());
    }
    public static RetrievalResult fuse(List<RouteResult> routes, double constant,Map<String,Double> weights) {
        if (!Double.isFinite(constant) || constant<1) throw new IllegalArgumentException("RRF constant must be positive");
        for(double weight:weights.values()) if(!Double.isFinite(weight) || weight<0) throw new IllegalArgumentException("Invalid RRF weight");
        var enabled=routes.stream().filter(r -> weights.getOrDefault(r.route(),1.0)>0).toList();
        if(enabled.isEmpty()) throw new IllegalArgumentException("At least one RRF route required");
        long successful=enabled.stream().filter(r -> r.status()==Status.SUCCEEDED).count();
        if (successful==0) return new RetrievalResult(Status.FAILED,routes,List.of());
        Map<String,Candidate> candidates=new HashMap<>();
        Map<String,Double> scores=new HashMap<>();
        Map<String,Map<String,Double>> raw=new HashMap<>();
        for (RouteResult route : enabled) {
            if(route.status()!=Status.SUCCEEDED) continue;
            Set<String> seen=new HashSet<>();
            for (int i=0;i<route.candidates().size();i++) {
                Candidate c=route.candidates().get(i);
                if (!seen.add(c.id())) continue;
                candidates.putIfAbsent(c.id(),c);
                raw.computeIfAbsent(c.id(),key->new HashMap<>()).put(route.route(),c.score());
                scores.merge(c.id(),weights.getOrDefault(route.route(),1.0)/(constant+i+1),Double::sum);
            }
        }
        List<Candidate> fused=candidates.values().stream().map(c->c.scored(scores.get(c.id()),raw.get(c.id())))
            .sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id)).toList();
        Status status=successful<enabled.size()?Status.DEGRADED:fused.isEmpty()?Status.NO_EVIDENCE:Status.SUCCEEDED;
        return new RetrievalResult(status,routes,fused);
    }
}
