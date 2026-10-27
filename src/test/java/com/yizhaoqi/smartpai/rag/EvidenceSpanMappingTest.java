package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceSpanMappingTest {
    final ObjectMapper json=new ObjectMapper();
    final String body="Policy\n\nＡlpha requires a signed request; approval takes two days.";
    final String quote="Alpha requires a signed request; approval takes two days.";
    JsonNode document() { return json.createObjectNode().put("id","policy").put("fileMd5","md5").put("sha256",EvidenceRecorder.hash(body)); }
    Candidate child(String id,String owner,String text) { return new Candidate("knowledge_base:"+id,"md5",id.equals("a")?1:2,owner,"policy",text,1,Map.of(),List.of(),"knowledge_base",id); }
    List<Candidate> children() { return List.of(child("a","1","Alpha requires a signed request;"),child("b","1","approval takes two days.")); }
    ArrayNode units(List<Candidate> children) {
        ArrayNode groups=EvidenceSpanMapping.groups(json,document(),body,quote,children);
        ObjectNode unit=json.createObjectNode().put("id","u1");unit.set("anchorGroups",groups);
        ArrayNode anchors=unit.putArray("anchors");for(JsonNode g:groups)for(JsonNode a:g.path("anchors"))anchors.add(a);
        return json.createArrayNode().add(unit);
    }
    @Test void exactSameSourceFragmentsMustAllBePresentInsteadOfCountingOnePartAsAWholeChildHit() {
        var children=children();var units=units(children);
        assertEquals(1,units.path(0).path("anchorGroups").size());
        assertEquals(1,EvidencePositions.candidates(json,units,json.valueToTree(children)).path("completeHit").asInt());
        assertEquals(0,EvidencePositions.candidates(json,units,json.valueToTree(List.of(children.get(0)))).path("completeHit").asInt());
        assertEquals(0,EvidencePositions.candidates(json,units,json.valueToTree(List.of(children.get(1)))).path("covered").asInt());
        var altered=List.of(children.get(0),child("b","1","fabricated approval"));
        assertEquals(0,EvidencePositions.candidates(json,units,json.valueToTree(altered)).path("covered").asInt());
    }
    @Test void missingTextCrossOwnerPiecesAndAmbiguousOriginalLocationsCannotManufactureACompleteSpan() {
        assertTrue(EvidenceSpanMapping.groups(json,document(),body,quote,List.of(children().get(0),child("b","1","two days."))).isEmpty());
        assertTrue(EvidenceSpanMapping.groups(json,document(),body,quote,List.of(children().get(0),child("b","2","approval takes two days."))).isEmpty());
        String repeated="word word";var doc=json.createObjectNode().put("fileMd5","md5").put("sha256",EvidenceRecorder.hash(repeated));
        assertTrue(EvidenceSpanMapping.groups(json,doc,repeated,repeated,List.of(child("a","1","word"))).isEmpty());
        assertThrows(IllegalArgumentException.class,() -> EvidenceSpanMapping.groups(json,document(),body+"changed",quote,children()));
    }
    @Test void assemblyClippingOfOneFragmentLosesTheNecessaryUnitEvenWhenBothChildIdsAreRetained() {
        var children=children();var units=units(children);ArrayNode passages=json.createArrayNode();
        for(Candidate child:children) {
            int length=child.chunkIndex()==1?child.text().length():8;
            ObjectNode p=passages.addObject().put("text",child.text().substring(0,length));
            p.putArray("sources").addObject().put("sourceId",child.id()).put("sourceHash",EvidenceRecorder.hash(child.text()))
                .put("sourceStart",0).put("sourceEnd",length).put("outputStart",0).put("outputEnd",length);
        }
        assertEquals(0,EvidencePositions.passages(json,units,passages).path("completeHit").asInt());
    }
}
