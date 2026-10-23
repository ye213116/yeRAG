package com.yizhaoqi.smartpai.agent;

import java.util.Map;

public record AgentRunResult(String answer,Map<Integer,String> references,Map<Integer,SourceReference> sources,String runId) {
    public AgentRunResult { references=Map.copyOf(references); sources=Map.copyOf(sources); }
    public AgentRunResult(String answer,Map<Integer,String> references) { this(answer,references,Map.of(),null); }
    public AgentRunResult(String answer,Map<Integer,String> references,Map<Integer,SourceReference> sources) { this(answer,references,sources,null); }
}
