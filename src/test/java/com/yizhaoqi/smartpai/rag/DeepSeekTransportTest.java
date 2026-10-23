package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class DeepSeekTransportTest {
    @TempDir Path directory;
    HttpServer server;
    ObjectMapper json=new ObjectMapper(); RagProperties config=new RagProperties();
    AiProperties ai=new AiProperties(); LocalEvidenceStore store; EvidenceRecorder recorder; DeepSeekClient client;
    AtomicReference<String> body=new AtomicReference<>(); AtomicReference<JsonNode> sent=new AtomicReference<>();
    AtomicInteger requests=new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange -> {
            requests.incrementAndGet(); sent.set(json.readTree(exchange.getRequestBody()));
            byte[] data=body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",sent.get().path("stream").asBoolean()?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()) {
                for(int offset=0;offset<data.length;offset+=7) { out.write(data,offset,Math.min(7,data.length-offset)); out.flush(); }
            }
        });
        server.start(); config.setEvidenceDirectory(directory.toString());
        store=new LocalEvidenceStore(json,config); recorder=new EvidenceRecorder(store,json,config);
        client=new DeepSeekClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-secret","fixture-model",ai);
        client.configureRag(config,recorder);
    }
    @AfterEach void stop() { server.stop(0); }
    RunContext context() { return new RunContext("id:1","question","pipeline",System.nanoTime()+Duration.ofSeconds(10).toNanos()); }
    JsonNode onlyTrace() throws Exception {
        try(var files=Files.list(directory)) { var paths=files.filter(p -> p.toString().endsWith(".json")).toList(); assertEquals(1,paths.size()); return json.readTree(paths.get(0).toFile()); }
    }
    JsonNode event(JsonNode trace,String name) {
        for(JsonNode e:trace.path("events")) if(name.equals(e.path("name").asText())) return e.get("data");
        throw new AssertionError("Missing "+name);
    }
    @Test void fragmentedSseRetainsUsageAndSeparatesThinkingFromVisibleAnswer() throws Exception {
        body.set("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"}}]}\n\n"
            +"data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n\n"
            +"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            +"data: {\"choices\":[],\"usage\":{\"total_tokens\":42}}\n\ndata: [DONE]\n\n");
        var events=client.streamDetailed(List.of(AgentMessage.user("问题"))).contextWrite(c -> c.put(RunContext.KEY,context())).collectList().block(Duration.ofSeconds(10));
        assertEquals("答案",events.stream().map(e -> e.text()).collect(java.util.stream.Collectors.joining()));
        JsonNode trace=onlyTrace(),generation=event(trace,"generation");
        assertEquals("SUCCEEDED",trace.path("status").asText()); assertEquals(42,generation.path("usage").path("total_tokens").asInt());
        assertTrue(generation.path("firstModelOutputMs").asLong()<=generation.path("firstAnswerMs").asLong());
        assertFalse(trace.toString().contains("test-secret")); assertFalse(trace.toString().contains("hidden"));
        assertEquals(sent.get(),event(trace,"request"));
        assertEquals(EvidenceRecorder.hash(sent.get().toString()),event(trace,"dispatch").path("requestSha256").asText());
    }
    @Test void eofWithoutProtocolEndAndLengthFinishAreFailures() throws Exception {
        body.set("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        assertThrows(RagFailure.class,() -> client.streamFinal(List.of(AgentMessage.user("q")))
            .contextWrite(c -> c.put(RunContext.KEY,context())).collectList().block(Duration.ofSeconds(10)));
        assertEquals("FAILED",onlyTrace().path("status").asText());
        try(var files=Files.list(directory)) { for(Path file:files.toList()) Files.delete(file); }
        body.set("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n");
        assertEquals(Status.TRUNCATED,assertThrows(RagFailure.class,() -> client.streamFinal(List.of(AgentMessage.user("q")))
            .contextWrite(c -> c.put(RunContext.KEY,context())).collectList().block(Duration.ofSeconds(10))).status());
        assertEquals("TRUNCATED",onlyTrace().path("status").asText());
    }
    @Test void finalWireBudgetIncludesToolDefinitionsAndPreventsHttpDispatch() throws Exception {
        config.setContextTokens(2400);
        var definitions=List.<Map<String,Object>>of(Map.of("description","x".repeat(3000)));
        assertThrows(RagFailure.class,() -> client.decide(List.of(AgentMessage.user("q")),definitions)
            .contextWrite(c -> c.put(RunContext.KEY,context())).block(Duration.ofSeconds(5)));
        assertEquals(0,requests.get()); assertEquals("FAILED",onlyTrace().path("status").asText());
    }
    @Test void nonStreamingAnswerIsAlsoFrozenAndMissingUsageIsNull() throws Exception {
        body.set("{\"choices\":[{\"message\":{\"content\":\"direct answer\"},\"finish_reason\":\"stop\"}]}");
        assertEquals("direct answer",client.decide(List.of(AgentMessage.user("q")),List.of())
            .contextWrite(c -> c.put(RunContext.KEY,context())).block(Duration.ofSeconds(10)).content());
        assertTrue(event(onlyTrace(),"generation").get("usage").isNull());
    }
}
