package com.yizhaoqi.smartpai.agent;

import java.util.Map;

public record AgentRunResult(String answer, Map<Integer, String> references) {
}
