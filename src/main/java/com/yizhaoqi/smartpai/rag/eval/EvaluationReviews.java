package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** Append-only, operator-supplied review log. Never written by the Judge or scorer. */
public final class EvaluationReviews {
    private final List<JsonNode> entries;
    private final ObjectMapper json;
    public EvaluationReviews(Path file,ObjectMapper json) throws Exception {
        this.json=json; List<JsonNode> records=new ArrayList<>(); Set<String> ids=new HashSet<>(); Map<String,String> previous=new HashMap<>();
        if(file!=null && Files.exists(file)) try(var lines=Files.lines(file)) {
            for(String line:lines.toList()) {
                if(line.isBlank() || line.stripLeading().startsWith("#")) continue;
                JsonNode record=json.readTree(line);
                String id=EvaluationCases.requireText(record,"reviewId"),type=EvaluationCases.requireText(record,"type");
                if(!ids.add(id) || !Set.of("LABELS","ANSWER").contains(type)) throw new IllegalArgumentException("Duplicate or unknown review");
                for(String field:List.of("datasetHash","caseId","reviewer","reason","createdAt")) EvaluationCases.requireText(record,field);
                if(type.equals("LABELS") && !Set.of("APPROVED","DISPUTED").contains(record.path("state").asText())) throw new IllegalArgumentException("Unknown label review state");
                if(type.equals("ANSWER")) {
                    for(String field:List.of("pipelineRunId","answerSha256")) EvaluationCases.requireText(record,field);
                    if(!Set.of("correct","partial","wrong","disputed").contains(record.path("verdict").asText())) throw new IllegalArgumentException("Unknown answer review verdict");
                }
                String key=type+":"+record.path("datasetHash").asText()+":"+record.path("caseId").asText()+":"+record.path("pipelineRunId").asText();
                String predecessor=previous.put(key,id);
                if(!Objects.equals(predecessor,record.path("previousReviewId").asText(null))) throw new IllegalArgumentException("Review history must explicitly reference its predecessor");
                records.add(record.deepCopy());
            }
        }
        entries=List.copyOf(records);
    }
    public ObjectNode forCase(String datasetHash,String caseId,String pipelineRunId,String answerHash) {
        ArrayNode history=json.createArrayNode(); JsonNode label=null,global=null,answer=null;
        for(JsonNode record:entries) {
            if(!datasetHash.equals(record.path("datasetHash").asText())) continue;
            boolean same=caseId.equals(record.path("caseId").asText()),all="*".equals(record.path("caseId").asText());
            if(!same && !all) continue;
            history.add(record.deepCopy());
            if("LABELS".equals(record.path("type").asText())) { if(same) label=record; else global=record; }
            if(same && "ANSWER".equals(record.path("type").asText()) && pipelineRunId.equals(record.path("pipelineRunId").asText()) && answerHash.equals(record.path("answerSha256").asText())) answer=record;
        }
        if(label==null) label=global;
        ObjectNode result=json.createObjectNode().put("labels",label==null?"PENDING":label.path("state").asText());
        result.set("history",history);
        if(answer!=null) result.set("answer",answer.deepCopy());
        return result;
    }
}
