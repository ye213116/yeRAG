package com.yizhaoqi.smartpai.rag.eval;

import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate;
import java.util.*;

/** Bounded batches retain the service ACL and reject permission changes between batches. */
public final class EvaluationCorpus {
    private EvaluationCorpus() {}
    public static HybridSearchService.CorpusScan scan(HybridSearchService search,String identity,List<String> md5s) {
        if(md5s.isEmpty() || md5s.size()>1000) throw new IllegalArgumentException("Evaluation corpus must have 1..1000 files");
        List<Candidate> chunks=new ArrayList<>(); String permissions=null; Set<String> ids=new HashSet<>();
        for(int i=0;i<md5s.size();i+=100) {
            var batch=search.scanCorpusWithPermission(identity,md5s.subList(i,Math.min(i+100,md5s.size())));
            if(permissions!=null && !permissions.equals(batch.permissionFingerprint())) throw new SecurityException("Permissions changed during corpus scan");
            permissions=batch.permissionFingerprint();
            for(Candidate c:batch.chunks()) {
                if(!ids.add(c.id())) throw new IllegalStateException("Duplicate physical chunk in corpus scan");
                chunks.add(c);
                if(chunks.size()>100000) throw new IllegalStateException("Evaluation snapshot exceeds 100000 chunks");
            }
        }
        return new HybridSearchService.CorpusScan(List.copyOf(chunks),permissions);
    }
}
