package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

/** Filesystem integrity only; does not impersonate an index/ACL preflight or human approval. */
public final class EvaluationDatasetCheck {
    private EvaluationDatasetCheck() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Dataset directory required");
        var dataset=EvaluationCases.load(Path.of(args[0]),new ObjectMapper());
        System.out.println("FILES_VALID cases="+dataset.cases().size()+" datasetHash="+dataset.hash()+"; index/ACL checks and human review are separate");
    }
}
