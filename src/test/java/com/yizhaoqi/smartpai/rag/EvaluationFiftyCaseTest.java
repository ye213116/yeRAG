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
    final AtomicInteger supports=new AtomicInteger();
    DeepSeekClient client;
    boolean measureSupport;
    RecallStrategy baselineStrategy;
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
            } else if(EvaluationSupportJudge.RULES.equals(request.path("messages").path(0).path("content").asText())) {
                supports.incrementAndGet();
                ObjectNode result=json.createObjectNode().put("extractionComplete",true).put("reason","synthetic test support");
                ObjectNode claim=result.putArray("claims").addObject().put("claim","A synthetic fixture answer").put("answerQuote","SIMULATED_TEST_ANSWER").put("verdict","supported").put("reason","synthetic test quote");
                claim.putArray("contextQuotes").add("SIMULATED_TEST_ANSWER is a synthetic test fixture."); result.putArray("keyPoints");
                ObjectNode response=json.createObjectNode(); response.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("content",result.toString()); body=response.toString();
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
        client=new DeepSeekClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-secret","SIMULATED-PROVIDER",ai); client.configureRag(rag,recorder);
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
        when(search.searchWithPermissionDetailed(anyString(),eq("id:1"),anyInt(),anyDouble(),anyList())).thenReturn(Mono.just(new RetrievalResult(Status.SUCCEEDED,
            List.of(new RouteResult("bm25",Status.SUCCEEDED,visible,1,null)),visible)));
    }
    @AfterEach void stop() { server.stop(0); }
    void execute(Path dataset,Path output) throws Exception {
        var runner=new RagExperimentRunner(pipeline,new QuestionTelemetry(recorder,store,ai),store,search,judge,json,ai,rag,
            dataset.toString(),output.toString(),"id:1","id:2","run");
        if(measureSupport) {
            org.springframework.test.util.ReflectionTestUtils.setField(runner,"supportEnabled",true);
            org.springframework.test.util.ReflectionTestUtils.setField(runner,"supportJudge",new EvaluationSupportJudge(client,recorder,ai,json));
        }
        if(baselineStrategy!=null) {
            org.springframework.test.util.ReflectionTestUtils.setField(runner,"baseline",true);
            org.springframework.test.util.ReflectionTestUtils.setField(runner,"recallStrategy",baselineStrategy.name());
        }
        runner.run(null);
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
    @Test void enterpriseQuestionsUseOnlyManifestCorpusWithoutShortAnswerOverride() throws Exception {
        var data=EvaluationCases.load(Path.of("evaluation/phase1"),json);
        List<String> md5s=new ArrayList<>();
        for(JsonNode document:data.manifest().path("documents")) md5s.add(document.path("fileMd5").asText());
        md5s=md5s.stream().distinct().sorted().toList();
        final List<String> expected=md5s;
        execute(Path.of("evaluation/phase1"),directory.resolve("enterprise-scoped"));
        verify(pipeline,times(50)).run(argThat(r -> r.corpusMd5s().equals(expected) && !r.conciseAnswer()),any());
        verify(search,never()).searchWithPermissionDetailed(anyString(),anyString(),anyInt(),anyDouble());
    }
    @Test void pipelineFailureDoesNotStopTheRemainingCases() throws Exception {
        Path dataset=directory.resolve("small-dataset"); Files.createDirectories(dataset);
        try(var files=Files.walk(Path.of("evaluation/phase1"))) { for(Path file:files.toList()) { Path dest=dataset.resolve(Path.of("evaluation/phase1").relativize(file)); if(Files.isDirectory(file)) Files.createDirectories(dest); else Files.copy(file,dest); } }
        ObjectNode manifest=(ObjectNode)json.readTree(dataset.resolve("dataset.json").toFile()); manifest.put("expectedCount",2); json.writeValue(dataset.resolve("dataset.json").toFile(),manifest);
        var cases=EvaluationCases.read(dataset.resolve("cases.jsonl"),json).subList(0,2); Files.write(dataset.resolve("cases.jsonl"),cases.stream().map(JsonNode::toString).toList());
        when(search.searchWithPermissionDetailed(eq(cases.get(0).path("question").asText()),eq("id:1"),anyInt(),anyDouble(),anyList())).thenReturn(Mono.error(new RagFailure(Status.FAILED,"SIMULATED_RECALL_FAILURE")));
        Path output=directory.resolve("failed-run"); execute(dataset,output);
        verify(pipeline,times(2)).run(any(),any()); assertEquals(1,generations.get());
        assertEquals(2,Files.readAllLines(output.resolve("report/results.jsonl")).size());
        JsonNode first=json.readTree(Files.readAllLines(output.resolve("report/results.jsonl")).get(0));
        assertEquals("UNAVAILABLE",first.path("deterministic").path("status").asText());
    }
    @Test void publicLabelsScoreIndependentlyOfHumanApprovalAndJudgeFailure() throws Exception {
        measureSupport=true;
        Path source=PublicDatasetEvaluationTest.hotpotSource(directory,json),input=directory.resolve("public-input");
        assertEquals(0,PublicDatasetEvaluationTest.importSource(source,input,"hotpotqa"));
        var dataset=EvaluationCases.load(input,json); rag.setCorpusVersion(dataset.manifest().path("corpusVersion").asText());
        visible.clear();
        for(JsonNode doc:dataset.manifest().path("documents")) {
            String id=doc.path("id").asText();
            visible.add(new Candidate("knowledge_base:"+id,doc.path("fileMd5").asText(),0,"2",id,
                Files.readString(input.resolve(doc.path("path").asText())),1,Map.of("bm25",1.0),List.of(),"knowledge_base",id));
        }
        when(search.searchWithPermissionDetailed(anyString(),eq("id:1"),anyInt(),anyDouble(),anyList()))
            .thenReturn(Mono.just(new RetrievalResult(Status.SUCCEEDED,List.of(new RouteResult("bm25",Status.SUCCEEDED,visible,1,null)),visible)));
        Path output=directory.resolve("public-run"); execute(input,output);
        var summary=json.readTree(output.resolve("report/summary.json").toFile());
        assertEquals(2,summary.path("publicBenchmark").path("answerMetricCount").asInt());
        assertEquals(1,summary.path("publicBenchmark").path("em").asDouble());
        assertEquals(1,summary.path("publicBenchmark").path("observedSupportingEvidence").path("coverageAt5").asDouble());
        assertEquals(0,summary.path("answers").path("count").asInt());
        assertEquals(1,summary.path("publicBenchmark").path("judgeFailures").asInt());
        assertEquals(2,summary.path("answerSupport").path("assessed").asInt());
        assertEquals(1,summary.path("answerSupport").path("judgeClaimSupportRate").path("mean").asDouble());
        assertEquals(0,summary.path("answerSupport").path("judgeAnswerCompleteness").path("count").asInt());
        assertEquals(2,Files.readAllLines(output.resolve("report/ragas.jsonl")).size());
        verify(pipeline,times(2)).run(argThat(r -> r.conciseAnswer() && !r.corpusMd5s().isEmpty()),any());
        verify(search,times(2)).scanCorpusWithPermission(eq("id:1"),anyList()); // initial/final snapshots, not per question.
        Path offline=directory.resolve("public-offline");
        new EvaluationRunner(output.resolve("artifacts")).run(output.resolve("dataset"),output.resolve("preflight.json"),output.resolve("capture.json"),offline,null);
        assertEquals(Files.readString(output.resolve("report/results.jsonl")),Files.readString(offline.resolve("results.jsonl")));
        when(search.scanCorpusWithPermission(eq("id:1"),anyList())).thenReturn(
            new HybridSearchService.CorpusScan(visible,"permission"),new HybridSearchService.CorpusScan(visible,"changed-permission"));
        Path changed=directory.resolve("public-changed-run"); execute(input,changed);
        var rejected=json.readTree(changed.resolve("report/summary.json").toFile()).path("publicBenchmark");
        assertEquals(0,rejected.path("completed").asInt()); assertEquals(0,rejected.path("em").asDouble());
        assertEquals(0,rejected.path("observedSupportingEvidence").path("count").asInt());
        when(search.scanCorpusWithPermission(eq("id:1"),anyList())).thenReturn(new HybridSearchService.CorpusScan(visible,"permission"));
        when(search.searchWithPermissionDetailed(anyString(),eq("id:1"),anyInt(),anyDouble(),anyList(),eq(RecallStrategy.VECTOR)))
            .thenReturn(Mono.just(new RetrievalResult(Status.SUCCEEDED,List.of(new RouteResult("vector",Status.SUCCEEDED,visible,1,null)),visible)));
        baselineStrategy=RecallStrategy.VECTOR;
        Path vector=directory.resolve("vector-baseline"); execute(input,vector);
        var baseline=json.readTree(vector.resolve("report/summary.json").toFile());
        assertEquals("VECTOR",baseline.path("experiment").path("recallStrategy").asText());
        assertEquals(1,baseline.path("publicBenchmark").path("observedSupportingEvidence").path("completeHitAt5").asDouble());
        verify(pipeline,times(2)).run(argThat(r -> r.recallStrategy()==RecallStrategy.VECTOR && !r.options().rewrite() && !r.options().rerank()),any());
    }
}
