package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationJudgeTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;
    AtomicReference<String> response=new AtomicReference<>();
    AtomicReference<JsonNode> request=new AtomicReference<>();
    EvaluationJudge judge;
    LocalEvidenceStore store;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange -> {
            request.set(json.readTree(exchange.getRequestBody()));
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()) { out.write(body); }
        }); server.start();
        RagProperties rag=new RagProperties(); rag.setEvidenceDirectory(directory.toString());
        AiProperties ai=new AiProperties();
        store=new LocalEvidenceStore(json,rag); var recorder=new EvidenceRecorder(store,json,rag);
        var client=new DeepSeekClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-secret","test-judge",ai);
        client.configureRag(rag,recorder); judge=new EvaluationJudge(client,recorder,ai,json);
        org.springframework.test.util.ReflectionTestUtils.setField(judge,"judgeModel","independent-judge");
    }
    @AfterEach void stop() { server.stop(0); }
    ObjectNode sample() { ObjectNode s=json.createObjectNode().put("id","fact-01").put("question","问题").put("referenceAnswer","标注答案"); s.putArray("keyPoints").add("要点"); return s; }
    String assessment(boolean conflict) {
        ObjectNode result=json.createObjectNode().put("verdict","partial").put("reviewRequired",conflict).put("reason","测试判断");
        result.putArray("missingKeyPoints").add("要点"); result.putArray("unsupportedClaims").add("未提供支持的陈述"); result.putArray("contradictedClaims");
        return result.toString();
    }
    void respond(String text,String finish) {
        ObjectNode body=json.createObjectNode(); ObjectNode choice=body.putArray("choices").addObject().put("finish_reason",finish);
        choice.putObject("message").put("role","assistant").put("content",text); response.set(body.toString());
    }
    @Test void independentRequestStoresRawResponseAndUsesZeroTemperatureWithoutTools() throws Exception {
        String raw=assessment(true); respond(raw,"stop");
        JsonNode result=judge.assess("id:1","dataset-hash",sample(),"pipeline-id","实际上文","完整答案").block();
        assertEquals("SUCCEEDED",result.path("status").asText());
        assertEquals(0,request.get().path("temperature").asDouble()); assertFalse(request.get().has("tools"));
        assertFalse(request.get().toString().contains("hitAt5")); assertFalse(request.get().toString().contains("coverageAt5"));
        assertEquals(raw,EvaluationArtifacts.event(result,"rawResponse").path("text").asText());
        assertTrue(EvaluationArtifacts.event(result,"assessment").path("reviewRequired").asBoolean());
        assertFalse(result.toString().contains("test-secret"));
        int models=0;
        for(JsonNode event:result.path("events")) if("child".equals(event.path("name").asText())) {
            JsonNode model=store.read(event.path("data").path("runId").asText(),"id:1");
            assertEquals("independent-judge",EvaluationArtifacts.event(model,"request").path("model").asText()); models++;
        }
        assertEquals(1,models);
    }
    @Test void invalidJsonAndTruncationAreFailuresNotWrongVerdicts() throws Exception {
        respond("not JSON","stop");
        JsonNode bad=judge.assess("id:1","hash",sample(),"pipeline","context","answer").block();
        assertEquals("FAILED",bad.path("status").asText());
        assertEquals("not JSON",EvaluationArtifacts.event(bad,"rawResponse").path("text").asText());
        assertTrue(EvaluationArtifacts.event(bad,"assessment").isMissingNode());
        respond(assessment(false),"length");
        assertEquals("FAILED",judge.assess("id:1","hash",sample(),"pipeline","context","answer").block().path("status").asText());
        assertThrows(Exception.class,() -> EvaluationJudge.parse(json,assessment(false)+" {}"));
    }
}
