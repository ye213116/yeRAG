package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;

/** Versioned fact annotations requiring human confirmation; Judge output is never an annotation. */
public final class EvaluationCases {
    public record Dataset(Path directory,ObjectNode manifest,List<ObjectNode> cases,String hash) {}
    private EvaluationCases() {}
    public static Dataset load(Path directory,ObjectMapper json) throws Exception {
        Path root=directory.toAbsolutePath().normalize();
        ObjectNode manifest=(ObjectNode)json.readTree(root.resolve("dataset.json").toFile());
        if(manifest.path("schemaVersion").asInt()!=2) throw new IllegalArgumentException("Dataset schemaVersion must be 2; convert legacy input first");
        requireText(manifest,"datasetVersion"); requireText(manifest,"corpusVersion");
        List<ObjectNode> cases=read(root.resolve("cases.jsonl"),json);
        if(manifest.has("expectedCount") && manifest.path("expectedCount").asInt()!=cases.size()) throw new IllegalArgumentException("Dataset count differs from manifest");
        Set<String> documents=new HashSet<>();
        for(JsonNode document:manifest.path("documents")) {
            String id=requireText(document,"id");
            if(!documents.add(id)) throw new IllegalArgumentException("Duplicate document ID");
            Path file=root.resolve(requireText(document,"path")).normalize();
            if(!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath())) throw new IllegalArgumentException("Corpus file missing or outside dataset");
            String body=Files.readString(file);
            if(!EvidenceRecorder.hash(body).equals(requireText(document,"sha256"))) throw new IllegalArgumentException("Corpus SHA-256 mismatch: "+id);
            String md5=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(Files.readAllBytes(file)));
            if(!md5.equals(requireText(document,"fileMd5"))) throw new IllegalArgumentException("Corpus MD5 mismatch: "+id);
            if(!Set.of("public","restricted").contains(document.path("visibility").asText())) throw new IllegalArgumentException("Unknown document visibility");
        }
        for(ObjectNode sample:cases) {
            for(JsonNode unit:sample.path("evidenceUnits")) for(JsonNode alternative:unit.path("alternatives"))
                if(!documents.contains(requireText(alternative,"document"))) throw new IllegalArgumentException("Unknown evidence document");
            for(JsonNode id:sample.path("forbiddenDocuments")) if(!documents.contains(id.asText())) throw new IllegalArgumentException("Unknown forbidden document");
        }
        String hash=EvidenceRecorder.hash(manifest.toString()+"\n"+String.join("\n",cases.stream().map(JsonNode::toString).toList()));
        return new Dataset(root,manifest,List.copyOf(cases),hash);
    }
    public static List<ObjectNode> read(Path path,ObjectMapper json) throws Exception {
        List<ObjectNode> result=new ArrayList<>(); Set<String> ids=new HashSet<>();
        try(var lines=Files.lines(path)) {
            for(String line:lines.toList()) {
                if(line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if(!(json.readTree(line) instanceof ObjectNode sample)) throw new IllegalArgumentException("Case must be an object");
                validate(sample);
                if(!ids.add(sample.get("id").asText())) throw new IllegalArgumentException("Duplicate case ID");
                result.add(sample);
            }
        }
        if(result.isEmpty()) throw new IllegalArgumentException("Dataset is empty");
        return List.copyOf(result);
    }
    public static void validate(JsonNode sample) {
        requireText(sample,"id"); requireText(sample,"question"); requireText(sample,"referenceAnswer");
        String type=requireText(sample,"type");
        if(!Set.of("answerable","unanswerable","acl").contains(type)) throw new IllegalArgumentException("Unknown case type");
        stringArray(sample,"keyPoints",true);
        if(!sample.path("evidenceUnits").isArray()) throw new IllegalArgumentException("evidenceUnits array required");
        if(type.equals("answerable")==sample.path("evidenceUnits").isEmpty()) throw new IllegalArgumentException("Only answerable cases require nonempty evidence units");
        Set<String> unitIds=new HashSet<>();
        for(JsonNode unit:sample.path("evidenceUnits")) {
            if(!unitIds.add(requireText(unit,"id"))) throw new IllegalArgumentException("Duplicate evidence unit");
            if(!unit.path("alternatives").isArray() || unit.path("alternatives").isEmpty()) throw new IllegalArgumentException("Evidence alternatives required");
            for(JsonNode alternative:unit.path("alternatives")) { requireText(alternative,"document"); requireText(alternative,"quote"); }
        }
        if(type.equals("acl")) stringArray(sample,"forbiddenDocuments",true);
        if(sample.has("forbiddenQuotes")) stringArray(sample,"forbiddenQuotes",true);
    }
    public static String requireText(JsonNode node,String field) {
        if(!node.path(field).isTextual() || node.path(field).asText().isBlank()) throw new IllegalArgumentException("Nonblank text required: "+field);
        return node.get(field).asText();
    }
    public static void stringArray(JsonNode node,String field,boolean nonempty) {
        if(!node.path(field).isArray() || (nonempty && node.path(field).isEmpty())) throw new IllegalArgumentException("Array required: "+field);
        for(JsonNode item:node.path(field)) if(!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("Nonblank array text required: "+field);
    }
}
