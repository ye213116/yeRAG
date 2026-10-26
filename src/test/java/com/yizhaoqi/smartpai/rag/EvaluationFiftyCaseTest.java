package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.agent.tool.QueryRewriteService;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.stage.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Pipeline, telemetry and HTTP client; ES/model semantics are simulated, NOT a live quality run. */
class EvaluationFiftyCaseTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final HybridSearchService search=mock(HybridSearchService.class);
    final AtomicInteger generations=new AtomicInteger(),judges=new AtomicInteger();
    HttpServer server;
    RagPipeline pipeline;
    RagProperties rag;
    AiProperties ai;
    LocalEvidenceStore store;
    EvidenceRecorder recorder;
    EvaluationJudge judge;
    List<Candidate> visible=new ArrayList<>(),restricted=new ArrayList<>();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange -> {
            JsonNode request=json.readTree(exchange.getRequestBody()); boolean stream=request.path("stream").asBoolean(); String body;
            if(stream) {
                generations.incrementAndGet();
                body="data: {\"choices\":[{\"delta\":{\"content\":\"SIMULATED_TEST_ANSWER\"}}]}\n\n"
                    +"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            } else {
                int n=judges.incrementAndGet();
                ObjectNode result=json.createObjectNode().put("verdict","correct").put("reviewRequired",false).put("reason","SIMULATED opinion; not real quality evidence");
                result.putArray("missingKeyPoints"); result.putArray("unsupportedClaims"); result.putArray("contradictedClaims");
                ObjectNode response=json.createObjectNode(); response.putArray("choices").addObject().put("finish_reason","stop")
                    .putObject("message").put("role","assistant").put("content",n==1?"invalid JSON":result.toString());
                body=response.toString();
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json"); exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
        rag=new RagProperties(); rag.setEvidenceDirectory(directory.resolve("evidence").toString());
        rag.setRewriteEnabled(false); rag.setRerankEnabled(false); rag.setCodeVersion("SIMULATED-TEST"); rag.setCorpusVersion("yerag-phase1-synthetic-v1");
        ai=new AiProperties(); ai.getGeneration().setTemperature(0.0);
        store=new LocalEvidenceStore(json,rag); recorder=new EvidenceRecorder(store,json,rag);
        var client=new DeepSeekClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-secret","SIMULATED-PROVIDER",ai); client.configureRag(rag,recorder);
        judge=new EvaluationJudge(client,recorder,ai,json); RetrievalProperties retrieval=new RetrievalProperties();
        pipeline=spy(new RagPipeline(List.of(new QueryStage(new QueryRewriteService(client,json,ai)),new RetrieveStage(search),
            new RerankStage(mock(Reranker.class),rag,retrieval),new MergeStage(),new AssembleStage(json,ai),new GenerateStage(client)),rag,recorder,retrieval,ai));
        var dataset=EvaluationCases.load(Path.of("evaluation/phase1"),json);
        for(JsonNode doc:dataset.manifest().path("documents")) {
            String id=doc.path("id").asText(); var c=new Candidate("knowledge_base:"+id,doc.path("fileMd5").asText(),0,"2",id,
                Files.readString(dataset.directory().resolve(doc.path("path").asText())),1,Map.of("bm25",1.0),List.of(),"knowledge_base",id);
            (doc.path("visibility").asText().equals("restricted")?restricted:visible).add(c);
        }
        when(search.scanCorpusWithPermission(eq("id:1"),anyList())).thenReturn(new HybridSearchService.CorpusScan(visible,"permission"));
        when(search.scanCorpusWithPermission(eq("id:2"),anyList())).thenReturn(new HybridSearchService.CorpusScan(restricted,"setup-permission"));
        when(search.searchWithPermissionDetailed(anyString(),eq("id:1"),anyInt(),anyDouble())).thenReturn(Mono.just(new RetrievalResult(Status.SUCCEEDED,
            List.of(new RouteResult("bm25",Status.SUCCEEDED,visible,1,null)),visible)));
    }
    @AfterEach void stop() { server.stop(0); }
    void execute(Path dataset,Path output) throws Exception {
        new RagExperimentRunner(pipeline,new QuestionTelemetry(recorder,store,ai),store,search,judge,json,ai,rag,
            dataset.toString(),output.toString(),"id:1","id:2","run").run(null);
    }
    @Test void fiftyCasesRunExactlyOnceAndJudgeFailureDoesNotDropAnyRows() throws Exception {
        Path output=directory.resolve("run"); execute(Path.of("evaluation/phase1"),output);
        verify(pipeline,times(50)).run(any(),any()); assertEquals(50,generations.get()); assertEquals(50,judges.get());
        var rows=Files.readAllLines(output.resolve("report/results.jsonl")).stream().map(line -> { try { return json.readTree(line); } catch(Exception e) { throw new RuntimeException(e); } }).toList();
        assertEquals(50,rows.size());
        assertEquals(50,rows.stream().filter(r -> r.path("deterministic").path("generationComplete").asBoolean()).count());
        assertEquals(49,rows.stream().filter(r -> "ASSESSED".equals(r.path("semantic").path("status").asText())).count());
        assertEquals(1,rows.stream().filter(r -> "JUDGE_FAILED".equals(r.path("semantic").path("status").asText())).count());
        assertEquals(0,json.readTree(output.resolve("report/summary.json").toFile()).path("retrieval").path("count").asInt()); // DRAFT labels are not approved by tests.
        Path exported=Path.of("target","phase1-simulated-"+UUID.randomUUID());
        try(var files=Files.walk(output)) { for(Path source:files.toList()) { Path dest=exported.resolve(output.relativize(source)); if(Files.isDirectory(source)) Files.createDirectories(dest); else Files.copy(source,dest); } }
        Files.writeString(Path.of("target/phase1-latest-simulation.txt"),exported.toAbsolutePath().toString());
    }
    @Test void pipelineFailureDoesNotStopTheRemainingCases() throws Exception {
        Path dataset=directory.resolve("small-dataset"); Files.createDirectories(dataset);
        try(var files=Files.walk(Path.of("evaluation/phase1"))) { for(Path file:files.toList()) { Path dest=dataset.resolve(Path.of("evaluation/phase1").relativize(file)); if(Files.isDirectory(file)) Files.createDirectories(dest); else Files.copy(file,dest); } }
        ObjectNode manifest=(ObjectNode)json.readTree(dataset.resolve("dataset.json").toFile()); manifest.put("expectedCount",2); json.writeValue(dataset.resolve("dataset.json").toFile(),manifest);
        var cases=EvaluationCases.read(dataset.resolve("cases.jsonl"),json).subList(0,2); Files.write(dataset.resolve("cases.jsonl"),cases.stream().map(JsonNode::toString).toList());
        when(search.searchWithPermissionDetailed(eq(cases.get(0).path("question").asText()),eq("id:1"),anyInt(),anyDouble())).thenReturn(Mono.error(new RagFailure(Status.FAILED,"SIMULATED_RECALL_FAILURE")));
        Path output=directory.resolve("failed-run"); execute(dataset,output);
        verify(pipeline,times(2)).run(any(),any()); assertEquals(1,generations.get());
        assertEquals(2,Files.readAllLines(output.resolve("report/results.jsonl")).size());
        JsonNode first=json.readTree(Files.readAllLines(output.resolve("report/results.jsonl")).get(0));
        assertEquals("UNAVAILABLE",first.path("deterministic").path("status").asText());
    }
}
