package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;

/** Verify the import against the retained public records. This is provenance, never human approval. */
public final class PublicDatasetSource {
    private PublicDatasetSource() {}
    public static Path localFile(Path root,String relative) throws Exception {
        Path file=root.resolve(relative).normalize();
        if(!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath()))
            throw new IllegalArgumentException("Source file outside dataset or missing");
        return file;
    }
    public static void verify(Path root,JsonNode manifest,List<ObjectNode> cases,ObjectMapper json) throws Exception {
        JsonNode source=manifest.path("source");
        for(String field:List.of("format","url","revision","path","sha256","inputSha256","license")) EvaluationCases.requireText(source,field);
        String format=source.path("format").asText();
        if(!Set.of("hotpotqa","ultrarag").contains(format)) throw new IllegalArgumentException("Unknown public source format");
        String body=Files.readString(localFile(root,source.path("path").asText()));
        if(!EvidenceRecorder.hash(body).equals(source.path("sha256").asText())) throw new IllegalArgumentException("Source snapshot hash mismatch");
        JsonNode records=json.readTree(body);
        if(!records.isArray() || records.size()!=cases.size()) throw new IllegalArgumentException("Source/case count mismatch");
        JsonNode benchmark=manifest.path("benchmark");
        if(!Set.of("short","default").contains(benchmark.path("answerStyle").asText())) throw new IllegalArgumentException("Unknown answer style");
        if(!"shared-candidate-subset".equals(benchmark.path("setting").asText())) throw new IllegalArgumentException("Explicit subset setting required");
        String scorer=benchmark.path("scorer").asText();
        if(!(format.equals("hotpotqa")?scorer.equals("hotpotqa-answer-v1"):scorer.equals("unavailable")))
            throw new IllegalArgumentException("Scorer must match source task");
        Map<String,JsonNode> documents=new HashMap<>();
        for(JsonNode doc:manifest.path("documents")) documents.put(doc.path("id").asText(),doc);
        Set<Integer> seen=new HashSet<>();
        for(ObjectNode sample:cases) {
            if(!sample.path("sourceRecordIndex").isIntegralNumber()) throw new IllegalArgumentException("Source record index required");
            int index=sample.path("sourceRecordIndex").asInt(-1);
            if(index<0 || index>=records.size() || !seen.add(index)) throw new IllegalArgumentException("Invalid/duplicate source record index");
            JsonNode original=records.get(index);
            String id=original.path(format.equals("hotpotqa")?"_id":"id").asText();
            if(!id.equals(sample.path("sourceId").asText()) || !sample.path("id").asText().equals(format+"-"+id)
                    || !original.path("question").equals(sample.path("question")) || !"answerable".equals(sample.path("type").asText()))
                throw new IllegalArgumentException("Source question/identity mismatch");
            JsonNode answers=format.equals("hotpotqa")?json.createArrayNode().add(original.path("answer").asText()):original.path("golden_answers");
            if(!answers.equals(sample.path("referenceAnswers")) || !answers.path(0).equals(sample.path("referenceAnswer")) || !sample.path("keyPoints").isEmpty())
                throw new IllegalArgumentException("Source answers mismatch");
            if(format.equals("hotpotqa")) verifyHotpot(root,sample,original,documents);
            else if(!sample.path("evidenceUnits").isEmpty() || !"unavailable".equals(sample.path("evidenceAnnotation").asText()))
                throw new IllegalArgumentException("Generic QA import cannot invent supporting evidence");
        }
    }
    private static void verifyHotpot(Path root,JsonNode sample,JsonNode original,Map<String,JsonNode> documents) throws Exception {
        Map<String,JsonNode> contexts=new HashMap<>(); Map<String,String> ids=new HashMap<>();
        for(JsonNode context:original.path("context")) {
            String title=context.path(0).asText();
            if(contexts.put(title,context.path(1))!=null) throw new IllegalArgumentException("Ambiguous source title");
            StringBuilder body=new StringBuilder(title+"\n\n");
            for(JsonNode sentence:context.path(1)) body.append(sentence.asText());
            String id="doc-"+EvidenceRecorder.hash(body.toString()); ids.put(title,id);
            JsonNode document=documents.get(id);
            if(document==null || !body.toString().equals(Files.readString(localFile(root,document.path("path").asText()))))
                throw new IllegalArgumentException("Source context missing/changed (including distractors)");
        }
        Set<String> facts=new HashSet<>();
        for(JsonNode fact:original.path("supporting_facts")) facts.add(fact.toString());
        if(facts.isEmpty() || sample.path("evidenceUnits").size()!=facts.size()) throw new IllegalArgumentException("Supporting fact count mismatch");
        for(JsonNode unit:sample.path("evidenceUnits")) {
            String title=unit.path("sourceTitle").asText(); int sentence=unit.path("sentenceIndex").asInt(-1);
            if(!unit.path("sentenceIndex").isIntegralNumber() || !facts.remove(new ObjectMapper().createArrayNode().add(title).add(sentence).toString())
                    || !contexts.containsKey(title) || sentence<0 || sentence>=contexts.get(title).size())
                throw new IllegalArgumentException("Supporting sentence mapping mismatch");
            JsonNode alternatives=unit.path("alternatives");
            if(alternatives.size()!=1 || !ids.get(title).equals(alternatives.path(0).path("document").asText())
                    || !contexts.get(title).get(sentence).equals(alternatives.path(0).path("quote")))
                throw new IllegalArgumentException("Supporting quote mismatch");
        }
    }
}
