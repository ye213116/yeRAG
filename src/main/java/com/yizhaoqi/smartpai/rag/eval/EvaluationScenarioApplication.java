package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.SmartPaiApplication;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.agent.tool.QueryRewriteService;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Opt-in CLI for task-specific fixtures. Uses production beans; exposes no new HTTP endpoint. */
public final class EvaluationScenarioApplication {
    private EvaluationScenarioApplication() {}
    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("Usage: INPUT_JSON NEW_OUTPUT [Spring arguments]");
        String owner=System.getenv("AI_RAG_EVAL_IDENTITY");
        if(owner==null || !owner.matches("(?:id|name):.+")) throw new IllegalArgumentException("Trusted AI_RAG_EVAL_IDENTITY required");
        Path input=Path.of(args[0]),output=Path.of(args[1]);
        ObjectMapper json=new ObjectMapper(); JsonNode protocol=json.readTree(Files.readString(input));
        if(!protocol.path("cases").isArray() || protocol.path("cases").isEmpty()) throw new IllegalArgumentException("Nonempty cases required");
        Files.createDirectory(output); Files.createDirectory(output.resolve("artifacts"));
        Files.copy(input,output.resolve("protocol.json"));
        SpringApplication app=new SpringApplication(SmartPaiApplication.class); app.addInitializers(EvaluationApplication::isolate);
        List<String> runtimeArgs=new ArrayList<>(Arrays.asList(args).subList(2,args.length));
        runtimeArgs.add("--ai.rag.eval.enabled=false");
        try(var context=app.run(runtimeArgs.toArray(String[]::new))) {
            var rewriter=context.getBean(QueryRewriteService.class); var agent=context.getBean(KnowledgeAgentService.class);
            var pipeline=context.getBean(RagPipeline.class); var recorder=context.getBean(EvidenceRecorder.class);
            var store=context.getBean(EvidenceStore.class); ArrayNode rows=json.createArrayNode(); boolean all=true;
            Set<String> ids=new HashSet<>();
            for(JsonNode sample:protocol.path("cases")) {
                String id=sample.path("id").asText(),question=sample.path("question").asText(),kind=sample.path("kind").asText();
                if(id.isBlank() || !ids.add(id) || question.isBlank()) throw new IllegalArgumentException("Unique IDs and question required");
                List<AgentMessage> history=new ArrayList<>();
                for(JsonNode turn:sample.path("history")) {
                    String role=turn.path("role").asText();
                    if(!Set.of("user","assistant").contains(role)) throw new IllegalArgumentException("Fixture history roles user/assistant only");
                    history.add(new AgentMessage(role,turn.path("content").asText(),null,List.of()));
                }
                ObjectNode row=rows.addObject().put("id",id).put("kind",kind);
                List<String> tools=Collections.synchronizedList(new ArrayList<>());
                var run=recorder.start(owner,null,"evaluation-scenario",sample);
                var runtime=new RunContext(owner,run.id(),run.id(),System.nanoTime()+Duration.ofSeconds(65).toNanos(),run);
                String answer="";
                try {
                    if(kind.equals("rewrite")) {
                        var result=rewriter.rewriteDetailed(question,history).contextWrite(c -> c.put(RunContext.KEY,runtime)).block(Duration.ofSeconds(65));
                        row.put("status",result.status().name()).put("reason",result.reason()); answer=result.query();
                    } else if(kind.equals("agent")) {
                        var result=agent.run(owner,question,history,new AgentEventListener() {
                            @Override public void onStatus(String status,String message,Map<String,Object> details) {
                                if(details.get("tool") instanceof String tool) tools.add(tool);
                            }
                        }).contextWrite(c -> c.put(RunContext.KEY,runtime)).block(Duration.ofSeconds(65));
                        answer=result.answer(); row.put("status","SUCCEEDED");
                    } else if(Set.of("retrieval","qa").contains(kind)) {
                        var request=pipeline.request(owner,question,history,kind.equals("qa")?Mode.FIXED_QA:Mode.RETRIEVE_ONLY,sample.path("topK").asInt(10),0);
                        List<String> scope=new ArrayList<>(); for(JsonNode md5:(sample.has("corpusMd5s")?sample:protocol).path("corpusMd5s")) scope.add(md5.asText());
                        request=request.forEvaluation(scope,kind.equals("qa"));
                        if(sample.has("recallStrategy")) request=request.forBaseline(RecallStrategy.valueOf(sample.path("recallStrategy").asText()));
                        var result=pipeline.run(request,new RagObserver(){}).contextWrite(c -> c.put(RunContext.KEY,runtime)).block(Duration.ofSeconds(65));
                        answer=result.answer(); row.put("status",result.status().name()).put("pipelineRunId",result.runId());
                        JsonNode trace=store.read(result.runId(),owner);
                        for(JsonNode event:trace.path("events")) if(event.path("name").asText().equals("TOP_K")) row.set("topChunks",event.path("data").path("candidates"));
                    } else throw new IllegalArgumentException("Unknown scenario kind");
                    row.put("answer",answer); row.set("observedTools",json.valueToTree(tools));
                    boolean checked=sample.has("expectedAll") || sample.has("expectedAny") || sample.has("expectedTools");
                    boolean passed=kind.equals("rewrite")?row.path("status").asText().equals("SUCCEEDED"):
                        Set.of("SUCCEEDED","DEGRADED","NO_EVIDENCE").contains(row.path("status").asText());
                    for(JsonNode term:sample.path("expectedAll")) passed &= answer.contains(term.asText());
                    if(sample.path("expectedAny").isArray() && !sample.path("expectedAny").isEmpty()) {
                        boolean any=false; for(JsonNode term:sample.path("expectedAny")) any |= answer.contains(term.asText()); passed &= any;
                    }
                    for(JsonNode term:sample.path("forbidden")) passed &= !answer.contains(term.asText());
                    if(sample.has("expectedTools")) {
                        Set<String> expected=new HashSet<>(); for(JsonNode tool:sample.path("expectedTools")) expected.add(tool.asText());
                        passed &= new HashSet<>(tools).equals(expected);
                    }
                    row.put("assertions",checked?(passed?"PASS":"FAIL"):"NOT_DEFINED_NATIVE_SCORER_REQUIRED");
                    all &= passed; run.event("result",row); run.finish(passed?Status.SUCCEEDED:Status.FAILED);
                } catch(Exception failed) {
                    all=false; row.put("status","FAILED").put("errorType",failed.getClass().getSimpleName()); run.finish(Status.FAILED);
                }
                row.put("elapsedMs",run.elapsed()).put("runId",run.id());
                exportTree(run.snapshot(),owner,store,json,output.resolve("artifacts"),new HashSet<>());
                Files.writeString(output.resolve("results.jsonl"),row+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
            ObjectNode summary=json.createObjectNode().put("executionStatus",all?"PASS":"FAIL").put("count",rows.size())
                    .put("protocolSha256",EvidenceRecorder.hash(Files.readString(input))).put("owner",owner)
                    .put("notice","Synthetic deterministic assertions or task-native scores required. Not a full benchmark; no human approval inferred.");
            summary.set("results",rows); Files.writeString(output.resolve("summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
            if(!all) throw new IllegalStateException("Scenario execution/assertion failure; retained all outputs");
        }
    }
    private static void exportTree(JsonNode trace,String owner,EvidenceStore store,ObjectMapper json,Path output,Set<String> seen) throws Exception {
        String id=trace.path("runId").asText(); if(!seen.add(id)) return;
        Files.writeString(output.resolve(id+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
        for(JsonNode event:trace.path("events")) if(event.path("name").asText().equals("child"))
            exportTree(store.read(event.path("data").path("runId").asText(),owner),owner,store,json,output,seen);
    }
}
