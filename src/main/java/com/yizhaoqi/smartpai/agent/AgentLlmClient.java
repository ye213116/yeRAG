package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.model.AgentDecision;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface AgentLlmClient {
    Mono<AgentDecision> decide(List<AgentMessage> messages, List<Map<String, Object>> tools);
    Flux<String> streamFinal(List<AgentMessage> messages);
}
