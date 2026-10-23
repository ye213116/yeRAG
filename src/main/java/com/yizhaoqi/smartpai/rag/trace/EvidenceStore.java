package com.yizhaoqi.smartpai.rag.trace;

import com.fasterxml.jackson.databind.JsonNode;

public interface EvidenceStore {
    void save(String runId, JsonNode document) throws Exception;
    JsonNode read(String runId, String owner) throws Exception;
    void expire() throws Exception;
}
