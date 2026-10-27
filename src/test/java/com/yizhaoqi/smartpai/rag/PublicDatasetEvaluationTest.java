package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublicDatasetEvaluationTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    static Path hotpotSource(Path root,ObjectMapper json) throws Exception {
        ArrayNode rows=json.createArrayNode();
        for(String kind:List.of("bridge","comparison")) {
            ObjectNode row=rows.addObject().put("_id",kind).put("type",kind).put("level","easy")
                .put("question",kind+" synthetic question?").put("answer","SIMULATED_TEST_ANSWER");
            row.putArray("context").addArray().add("Synthetic title").addArray().add("SIMULATED_TEST_ANSWER is a synthetic test fixture.");
            row.putArray("supporting_facts").addArray().add("Synthetic title").add(0);
        }
        Path path=root.resolve("input.json"); json.writeValue(path.toFile(),rows); return path;
    }
    static int importSource(Path input,Path output,String format) throws Exception {
        var process=new ProcessBuilder("python3","scripts/rag-eval-import.py",format,input.toString(),output.toString(),
            "--count","2","--seed","7","--source-url","https://example.org/synthetic-test-only","--revision","SYNTHETIC-TEST")
            .redirectErrorStream(true).start();
        String log=new String(process.getInputStream().readAllBytes()); int code=process.waitFor();
        if(code!=0) System.err.println(log); return code;
    }
    @Test void publicImportIsReproducibleAndRejectsAlteredAnswersAndQuotes() throws Exception {
        Path source=hotpotSource(root,json),one=root.resolve("one"),two=root.resolve("two");
        assertEquals(0,importSource(source,one,"hotpotqa")); assertEquals(0,importSource(source,two,"hotpotqa"));
        var dataset=EvaluationCases.load(one,json); assertEquals(dataset.hash(),EvaluationCases.load(two,json).hash());
        assertEquals(2,dataset.cases().size()); assertEquals(1,dataset.manifest().path("documents").size());
        var sample=dataset.cases().get(0); sample.putArray("referenceAnswers").add("invented");
        Files.write(one.resolve("cases.jsonl"),dataset.cases().stream().map(JsonNode::toString).toList());
        assertThrows(IllegalArgumentException.class,() -> EvaluationCases.load(one,json));
        sample.putArray("referenceAnswers").add("SIMULATED_TEST_ANSWER");
        ((ObjectNode)sample.path("evidenceUnits").path(0).path("alternatives").path(0)).put("quote","invented evidence");
        Files.write(one.resolve("cases.jsonl"),dataset.cases().stream().map(JsonNode::toString).toList());
        assertThrows(IllegalArgumentException.class,() -> EvaluationCases.load(one,json));
    }
    @Test void genericUltraRagPreservesAliasesAndMissingEvidenceIsNotUnanswerable() throws Exception {
        Path input=root.resolve("ultra.jsonl");
        Files.writeString(input,"{\"id\":1,\"question\":\"Where?\",\"golden_answers\":[\"China\",\"Beijing\"],\"meta_data\":{}}\n"
            +"{\"id\":2,\"question\":\"Who?\",\"golden_answers\":[\"Alice\"],\"meta_data\":{}}\n");
        Path out=root.resolve("ultra"); assertEquals(0,importSource(input,out,"ultrarag"));
        var dataset=EvaluationCases.load(out,json);
        var sample=dataset.cases().stream().filter(c -> c.path("sourceId").asText().equals("1")).findFirst().orElseThrow();
        assertEquals(2,sample.path("referenceAnswers").size()); assertTrue(sample.path("keyPoints").isEmpty());
        assertEquals("answerable",sample.path("type").asText()); assertTrue(sample.path("evidenceUnits").isEmpty());
        assertEquals("NOT_APPLICABLE",BenchmarkAnswerMetrics.score(json,"China",sample.path("referenceAnswers"),true,"unavailable").path("status").asText());
        Files.writeString(input,Files.readString(input).replace("\"meta_data\":{}","\"meta_data\":{},\"choices\":[\"A\",\"B\"]"));
        Path unsupported=root.resolve("unsupported");
        assertNotEquals(0,importSource(input,unsupported,"ultrarag"));
        assertFalse(Files.exists(unsupported));
    }
    @Test void nativeAnswerRulesHandleAlternativesMultiplicityAndYesNoAndIncompleteAnswers() {
        var answers=json.createArrayNode().add("Beijing, China").add("China");
        assertEquals(1,BenchmarkAnswerMetrics.score(json,"The China!",answers,true,BenchmarkAnswerMetrics.SCORER).path("em").asDouble());
        assertEquals(2.0/3,BenchmarkAnswerMetrics.score(json,"red red",json.createArrayNode().add("red"),true,BenchmarkAnswerMetrics.SCORER).path("f1").asDouble(),1e-9);
        assertEquals(0,BenchmarkAnswerMetrics.score(json,"yes indeed",json.createArrayNode().add("yes"),true,BenchmarkAnswerMetrics.SCORER).path("f1").asDouble());
        assertEquals(0,BenchmarkAnswerMetrics.score(json,"China",answers,false,BenchmarkAnswerMetrics.SCORER).path("em").asDouble());
    }
    @Test void snapshotScanBatchesAndRejectsChangingPermissions() {
        HybridSearchService search=mock(HybridSearchService.class);
        List<String> ids=new ArrayList<>(); for(int i=0;i<205;i++) ids.add(String.format("%032x",i));
        when(search.scanCorpusWithPermission(eq("id:1"),anyList())).thenAnswer(call -> {
            List<String> batch=call.getArgument(1); assertTrue(batch.size()<=100);
            return new HybridSearchService.CorpusScan(List.of(),"stable");
        });
        assertEquals("stable",EvaluationCorpus.scan(search,"id:1",ids).permissionFingerprint());
        verify(search,times(3)).scanCorpusWithPermission(eq("id:1"),anyList());
        when(search.scanCorpusWithPermission(eq("id:1"),anyList()))
            .thenReturn(new HybridSearchService.CorpusScan(List.of(),"first"),new HybridSearchService.CorpusScan(List.of(),"changed"));
        assertThrows(SecurityException.class,() -> EvaluationCorpus.scan(search,"id:1",ids));
    }
}
