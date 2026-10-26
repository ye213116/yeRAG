package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.nio.file.*;
import java.util.*;

/** Explicitly simulated frozen runs. Reviewers and answers here are test input, not real quality evidence. */
final class PhaseOneFixture {
    final ObjectMapper json=new ObjectMapper();
    final Path root,directory,artifacts;
    EvaluationCases.Dataset dataset;
    ObjectNode sample,preflight,check,attempt,capture,pipeline,model,judge;
    final List<Candidate> originals=new ArrayList<>();
    final String pipelineId=UUID.randomUUID().toString(),modelId=UUID.randomUUID().toString(),judgeId=UUID.randomUUID().toString();
    final String owner="id:1",answer="测试替身的完整答案";
    String context;
    List<Candidate> top;
    PhaseOneFixture(Path root,int matched) throws Exception { this(root,matched,"answerable"); }
    PhaseOneFixture(Path root,int matched,String type) throws Exception {
        this.root=root; directory=root.resolve("dataset"); artifacts=root.resolve("artifacts");
        Files.createDirectories(directory); Files.createDirectories(artifacts);
        String body="条文甲：90天。\n条文乙：15天。\n条文丙：2天。\n";
        Files.writeString(directory.resolve("规则.txt"),body);
        String md5=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ObjectNode manifest=json.createObjectNode().put("schemaVersion",2).put("datasetVersion","test-v1").put("corpusVersion","test-corpus-v1").put("expectedCount",1);
        manifest.putArray("documents").addObject().put("id","policy").put("path","规则.txt").put("fileMd5",md5).put("sha256",EvidenceRecorder.hash(body)).put("visibility","public");
        json.writeValue(directory.resolve("dataset.json").toFile(),manifest);
        sample=json.createObjectNode().put("id","multi-01").put("type",type).put("question","三个规定分别是什么？").put("referenceAnswer","90天、15天和2天。");
        sample.putArray("keyPoints").add("三条规定"); ArrayNode units=sample.putArray("evidenceUnits");
        String[] texts={"条文甲：90天。","条文乙：15天。","条文丙：2天。"};
        for(int i=0;i<3;i++) {
            originals.add(new Candidate("knowledge_base:p"+i,md5,i,"1","规则.txt",texts[i],1.0,Map.of("bm25",1.0),List.of(),"knowledge_base","p"+i));
            if(type.equals("answerable")) units.addObject().put("id","u"+i).putArray("alternatives").addObject().put("document","policy").put("quote",texts[i]);
        }
        if(type.equals("acl")) sample.putArray("forbiddenDocuments").add("policy");
        Files.writeString(directory.resolve("cases.jsonl"),sample+"\n"); dataset=EvaluationCases.load(directory,json); sample=dataset.cases().get(0);
        preflight=new EvaluationPreflight(json).build(dataset,owner,"permissions",originals,List.of());
        check=(ObjectNode)preflight.path("cases").path(0);
        List<Candidate> ranked=new ArrayList<>(originals.subList(0,matched));
        for(int i=0;i<5;i++) ranked.add(new Candidate("knowledge_base:f"+i,md5,10+i,"1","规则.txt","无关文字",.1,Map.of(),List.of(),"knowledge_base","f"+i));
        ranked.addAll(originals.subList(matched,3)); top=ranked.subList(0,5);
        pipeline=run(pipelineId,"pipeline",null);
        event(pipeline,"request",json.createObjectNode().put("question",sample.path("question").asText()).put("mode","FIXED_QA").put("topK",5).set("history",json.createArrayNode()));
        stage(pipeline,"RETRIEVE",json.createObjectNode().set("candidates",json.valueToTree(originals)));
        stage(pipeline,"RERANK",json.createObjectNode().set("candidates",json.valueToTree(ranked)));
        List<RankedChunk> snapshot=new ArrayList<>(); for(int i=0;i<top.size();i++) snapshot.add(RankedChunk.of(top.get(i),i+1,"RRF"));
        event(pipeline,"TOP_K",json.createObjectNode().put("k",5).set("candidates",json.valueToTree(snapshot)));
        stage(pipeline,"MERGE",json.createObjectNode().set("passages",passages(top)));
        setAssembled(top);
        ObjectNode generated=json.createObjectNode().put("answer",answer); stage(pipeline,"GENERATE",generated);
        attempt=json.createObjectNode().put("id",sample.path("id").asText()).put("pipelineRunId",pipelineId).put("modelRunId",modelId).put("judgeRunId",judgeId).put("corpusVerified",true);
        setJudge("wrong",false);
        capture=json.createObjectNode().put("datasetHash",dataset.hash()).put("owner",owner).put("preflightSha256",preflight.path("sha256").asText())
            .put("corpusFingerprint",preflight.path("corpusFingerprint").asText()).put("corpusVersion","test-corpus-v1");
        capture.putArray("cases").add(attempt);
        save();
    }
    ObjectNode run(String id,String kind,String parent) {
        ObjectNode result=json.createObjectNode().put("runId",id).put("kind",kind).put("parentRunId",parent).put("owner",owner)
            .put("evidenceStatus","COMPLETE").put("status","SUCCEEDED").put("corpusVersion","test-corpus-v1");
        result.putArray("events"); return result;
    }
    void event(ObjectNode run,String name,JsonNode data) {
        ArrayNode events=(ArrayNode)run.get("events");
        for(int i=events.size()-1;i>=0;i--) if(name.equals(events.get(i).path("name").asText())) events.remove(i);
        events.addObject().put("name",name).put("sha256",EvidenceRecorder.hash(data.toString())).set("data",data);
    }
    void stage(ObjectNode run,String name,JsonNode data) {
        ObjectNode wrapper=json.createObjectNode(); wrapper.putObject("outcome").put("status","SUCCEEDED").set("evidence",data); event(run,name,wrapper);
    }
    ArrayNode passages(List<Candidate> candidates) {
        ArrayNode result=json.createArrayNode();
        for(Candidate c:candidates) {
            ObjectNode p=result.addObject().put("id",c.id()).put("text",c.text());
            p.putArray("sources").addObject().put("sourceId",c.id()).put("sourceHash",EvidenceRecorder.hash(c.text()))
                .put("sourceStart",0).put("sourceEnd",c.text().length()).put("outputStart",0).put("outputEnd",c.text().length());
        }
        return result;
    }
    void setAssembled(List<Candidate> candidates) {
        context=String.join("\n",candidates.stream().map(Candidate::text).toList());
        ObjectNode assembly=json.createObjectNode().put("content",context); assembly.set("passages",passages(candidates)); assembly.putArray("decisions"); stage(pipeline,"ASSEMBLE",assembly);
        model=run(modelId,"model-call",pipelineId);
        ObjectNode request=json.createObjectNode().put("stream",true).put("model","test-provider");
        request.putArray("messages").addObject().put("role","user").put("content","参考资料：\n"+context+"\n问题："+sample.path("question").asText());
        event(model,"request",request); event(model,"dispatch",json.createObjectNode().put("requestSha256",EvidenceRecorder.hash(request.toString())));
        event(model,"generation",json.createObjectNode().put("answer",answer));
    }
    void setJudge(String verdict,boolean review) {
        judge=run(judgeId,"judge",pipelineId);
        event(judge,"request",json.createObjectNode().put("datasetHash",dataset.hash()).put("caseId",sample.path("id").asText()).put("pipelineRunId",pipelineId)
            .put("answerSha256",EvidenceRecorder.hash(answer)).put("contextSha256",EvidenceRecorder.hash(context)));
        ObjectNode assessment=json.createObjectNode().put("verdict",verdict).put("reviewRequired",review).put("reason","测试输入，不是实际评审");
        assessment.putArray("missingKeyPoints"); assessment.putArray("unsupportedClaims"); assessment.putArray("contradictedClaims");
        event(judge,"rawResponse",json.createObjectNode().put("text",assessment.toString())); event(judge,"assessment",assessment);
    }
    Path approve() throws Exception {
        Path reviews=root.resolve("reviews.jsonl");
        ObjectNode approval=json.createObjectNode().put("reviewId","test-label-review").put("type","LABELS").put("datasetHash",dataset.hash()).put("caseId","*")
            .put("reviewer","simulated-test-reviewer").put("reason","Test fixture input, not a real approval").put("createdAt","2026-10-26T00:00:00Z").put("state","APPROVED");
        Files.writeString(reviews,approval+"\n"); return reviews;
    }
    JsonNode evaluate(Path reviews) throws Exception {
        return new EvaluationRunner(artifacts).evaluate(sample,check,preflight,attempt,new EvaluationReviews(reviews,json));
    }
    void save() throws Exception {
        for(ObjectNode artifact:List.of(pipeline,model,judge)) json.writeValue(artifacts.resolve(artifact.path("runId").asText()+".json").toFile(),artifact);
        EvaluationPreflight.seal(capture); json.writeValue(root.resolve("capture.json").toFile(),capture);
        json.writeValue(root.resolve("preflight.json").toFile(),preflight);
    }
}
