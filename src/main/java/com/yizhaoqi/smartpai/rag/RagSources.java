package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.agent.SourceReference;
import com.yizhaoqi.smartpai.rag.model.RagTypes.ContextResult;
import java.util.*;

public final class RagSources {
    private RagSources() {}
    public static Map<Integer,SourceReference> from(ContextResult context,int offset) {
        Map<Integer,SourceReference> sources=new LinkedHashMap<>();
        for(int i=0;i<context.passages().size();i++) {
            var passage=context.passages().get(i); int number=offset+i+1;
            sources.put(number,SourceReference.knowledge(number,passage.title(),passage.fileMd5(),passage.chunkId()));
        }
        return Collections.unmodifiableMap(sources);
    }
}
