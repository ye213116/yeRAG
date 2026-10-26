package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.util.*;

/** Corpus truth comes from ACL-filtered index scans and operator-reviewed labels, never an LLM. */
public final class EvaluationPreflight {
    private final ObjectMapper json;
    public EvaluationPreflight(ObjectMapper json) { this.json=json; }
    public ObjectNode build(EvaluationCases.Dataset dataset,String owner,String permissions,List<Candidate> visible,List<Candidate> audit) {
        ObjectNode result=json.createObjectNode().put("schemaVersion",2).put("datasetHash",dataset.hash()).put("owner",owner)
            .put("corpusVersion",dataset.manifest().path("corpusVersion").asText()).put("permissionFingerprint",permissions)
            .put("corpusFingerprint",fingerprint(visible)).put("createdAt",java.time.Instant.now().toString());
        Map<String,JsonNode> documents=new HashMap<>();
        for(JsonNode document:dataset.manifest().path("documents")) documents.put(document.path("id").asText(),document);
        ArrayNode checked=result.putArray("cases");
        for(ObjectNode sample:dataset.cases()) {
            ObjectNode check=checked.addObject().put("id",sample.path("id").asText()).put("status","READY");
            ArrayNode units=check.putArray("units"); List<String> issues=new ArrayList<>();
            for(JsonNode unit:sample.path("evidenceUnits")) {
                ObjectNode resolved=units.addObject().put("id",unit.path("id").asText()); ArrayNode anchors=resolved.putArray("anchors");
                Set<String> seen=new HashSet<>();
                for(JsonNode alternative:unit.path("alternatives")) {
                    String md5=documents.get(alternative.path("document").asText()).path("fileMd5").asText();
                    for(Candidate candidate:visible) if(md5.equals(candidate.fileMd5())) {
                        for(EvidencePositions.Range position:EvidencePositions.locate(candidate.text(),alternative.path("quote").asText())) {
                            String key=candidate.id()+":"+position.start()+":"+position.end();
                            if(!seen.add(key)) continue;
                            anchors.addObject().put("sourceId",candidate.id()).put("sourceHash",EvidenceRecorder.hash(candidate.text()))
                                .put("sourceText",candidate.text()).put("owner",candidate.owner()).put("fileMd5",md5)
                                .put("chunkId",candidate.chunkIndex()).put("esIndex",candidate.esIndex()).put("esHitId",candidate.esHitId())
                                .put("start",position.start()).put("end",position.end());
                        }
                    }
                }
                if(anchors.isEmpty()) issues.add("EVIDENCE_NOT_IN_VISIBLE_CHILD: "+unit.path("id").asText());
            }
            if("acl".equals(sample.path("type").asText())) for(JsonNode documentId:sample.path("forbiddenDocuments")) {
                String md5=documents.get(documentId.asText()).path("fileMd5").asText();
                if(visible.stream().anyMatch(c -> md5.equals(c.fileMd5()))) issues.add("ACL_ASSUMPTION_INVALID");
                if(audit.stream().noneMatch(c -> md5.equals(c.fileMd5()))) issues.add("RESTRICTED_INGESTION_UNVERIFIED");
            }
            if(!issues.isEmpty()) check.put("status","DATASET_INVALID");
            check.set("issues",json.valueToTree(issues));
        }
        result.put("ready",java.util.stream.StreamSupport.stream(checked.spliterator(),false).filter(c -> "READY".equals(c.path("status").asText())).count());
        seal(result); return result;
    }
    public static String fingerprint(List<Candidate> candidates) {
        return EvidenceRecorder.hash(String.join("\n",candidates.stream().map(c -> c.id()+"|"+c.owner()+"|"+c.fileMd5()+"|"+c.chunkIndex()+"|"+EvidenceRecorder.hash(c.text())).sorted().toList()));
    }
    public static void seal(ObjectNode node) { node.remove("sha256"); node.put("sha256",EvidenceRecorder.hash(node.toString())); }
    public static void verify(JsonNode node) {
        ObjectNode copy=((ObjectNode)node).deepCopy(); String expected=copy.path("sha256").asText(); copy.remove("sha256");
        if(!EvidenceRecorder.hash(copy.toString()).equals(expected)) throw new IllegalStateException("Artifact hash mismatch");
    }
}
