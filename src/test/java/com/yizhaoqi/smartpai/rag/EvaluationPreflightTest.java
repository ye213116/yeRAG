package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationPreflightTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    @Test void publicDatasetHasExactlyFiftyCasesAndEachPositiveQuoteIsGrounded() throws Exception {
        var dataset=EvaluationCases.load(Path.of("evaluation/phase1"),json);
        assertEquals(50,dataset.cases().size());
        Map<String,Long> counts=new HashMap<>();
        for(JsonNode sample:dataset.cases()) {
            String id=sample.path("id").asText(); String prefix=id.startsWith("cross-doc-")?"cross-doc":id.substring(0,id.indexOf('-'));
            counts.merge(prefix,1L,Long::sum);
        }
        assertEquals(Map.of("fact",12L,"para",12L,"multi",8L,"conflict",3L,"cross-doc",5L,"neg",6L,"acl",4L),counts);
        List<Candidate> visible=new ArrayList<>(),audit=new ArrayList<>();
        for(JsonNode doc:dataset.manifest().path("documents")) {
            String id=doc.path("id").asText();
            Candidate c=new Candidate("knowledge_base:"+id,doc.path("fileMd5").asText(),0,"2",id,Files.readString(dataset.directory().resolve(doc.path("path").asText())),1,Map.of(),List.of(),"knowledge_base",id);
            (doc.path("visibility").asText().equals("restricted")?audit:visible).add(c);
        }
        var check=new EvaluationPreflight(json);
        assertEquals(46,check.build(dataset,"id:1","permission",visible,List.of()).path("ready").asInt());
        JsonNode valid=check.build(dataset,"id:1","permission",visible,audit);
        assertEquals(50,valid.path("ready").asInt()); EvaluationPreflight.verify(valid);
        visible.addAll(audit);
        assertEquals(46,check.build(dataset,"id:1","permission",visible,audit).path("ready").asInt());
    }
    @Test void unicodeNormalizationPreservesOriginalCoordinates() {
        String source="前缀：Ａ\u00a0ＢＣ e\u0301😀。";
        var ranges=EvidencePositions.locate(source,"ABCé😀");
        assertEquals(1,ranges.size());
        assertEquals("Ａ\u00a0ＢＣ e\u0301😀",source.substring(ranges.get(0).start(),ranges.get(0).end()));
        assertThrows(IllegalArgumentException.class,() -> EvidencePositions.locate(source,"\u00a0"));
    }
    @Test void equivalentPositionsAndDuplicatesDoNotInflateCoverage() throws Exception {
        var f=new PhaseOneFixture(root,1);
        ObjectNode unit=(ObjectNode)f.sample.path("evidenceUnits").path(0);
        ((ArrayNode)unit.get("alternatives")).add(unit.path("alternatives").path(0).deepCopy());
        ObjectNode alternate=json.createObjectNode().put("document","policy").put("quote",f.originals.get(1).text());
        ((ArrayNode)unit.get("alternatives")).add(alternate);
        var check=new EvaluationPreflight(json).build(f.dataset,f.owner,"permission",f.originals,List.of());
        JsonNode resolved=check.path("cases").path(0).path("units");
        assertEquals(2,resolved.path(0).path("anchors").size());
        JsonNode result=EvidencePositions.candidates(json,resolved,json.valueToTree(List.of(f.originals.get(1),f.originals.get(1))));
        assertEquals(2,result.path("covered").asInt()); // One chunk supports u0's alternative and u1, each counted once.
        assertEquals(2.0/3,result.path("coverage").asDouble());
    }
    @Test void crossChunkQuotesCannotManufactureAChildHit() throws Exception {
        var f=new PhaseOneFixture(root,3);
        ((ObjectNode)f.sample.path("evidenceUnits").path(0).path("alternatives").path(0)).put("quote",f.originals.get(0).text()+f.originals.get(1).text());
        JsonNode result=new EvaluationPreflight(json).build(f.dataset,f.owner,"permission",f.originals,List.of());
        assertEquals("DATASET_INVALID",result.path("cases").path(0).path("status").asText());
    }
    @Test void passageCoordinatesAreValidatedAgainstActualSourceText() throws Exception {
        var f=new PhaseOneFixture(root,3); ArrayNode passages=f.passages(f.originals);
        ((ObjectNode)passages.path(0)).put("text","被替换的内容");
        assertThrows(IllegalStateException.class,() -> EvidencePositions.passages(json,f.check.path("units"),passages));
    }
    @Test void exceptionBeyondThreeHundredCharactersIsLostDespiteKeepingTheSameChunkId() {
        String original="背景".repeat(210)+"试用期不适用";
        ObjectNode unit=json.createObjectNode().put("id","exception");
        unit.putArray("anchors").addObject().put("sourceId","index:policy").put("sourceHash",EvidenceRecorder.hash(original))
            .put("sourceText",original).put("start",420).put("end",original.length());
        ArrayNode units=json.createArrayNode().add(unit),passages=json.createArrayNode();
        ObjectNode passage=passages.addObject().put("id","index:policy").put("text",original.substring(0,300));
        ObjectNode source=passage.putArray("sources").addObject().put("sourceId","index:policy").put("sourceHash",EvidenceRecorder.hash(original))
            .put("sourceStart",0).put("sourceEnd",300).put("outputStart",0).put("outputEnd",300);
        assertEquals("NONE",EvidencePositions.passages(json,units,passages).path("state").asText());
        passage.put("text",original); source.put("sourceEnd",original.length()).put("outputEnd",original.length());
        assertEquals("FULL",EvidencePositions.passages(json,units,passages).path("state").asText());
    }
    @Test void corpusModificationAndDuplicateIdsAreRejectedBeforePaidCalls() throws Exception {
        var f=new PhaseOneFixture(root,3);
        Files.writeString(f.directory.resolve("cases.jsonl"),f.sample+"\n"+f.sample+"\n");
        assertThrows(IllegalArgumentException.class,() -> EvaluationCases.load(f.directory,json));
        Files.writeString(f.directory.resolve("cases.jsonl"),f.sample+"\n");
        Files.writeString(f.directory.resolve("规则.txt"),"changed");
        assertThrows(IllegalArgumentException.class,() -> EvaluationCases.load(f.directory,json));
    }
    @Test void reviewForkOrDuplicateCannotSilentlyOverwriteHistory() throws Exception {
        var f=new PhaseOneFixture(root,1); Path path=f.approve();
        ObjectNode next=(ObjectNode)json.readTree(Files.readString(path)); next.put("reviewId","new");
        Files.writeString(path,next+"\n",StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class,() -> new EvaluationReviews(path,json));
    }
}
