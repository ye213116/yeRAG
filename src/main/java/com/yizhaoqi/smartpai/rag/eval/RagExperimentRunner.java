package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.agent.AgentRunResult;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/** Explicitly enabled, trusted local experiment harness; never exposed as an HTTP identity override. */
@Component
@ConditionalOnProperty(name="ai.rag.eval.enabled",havingValue="true")
public class RagExperimentRunner implements ApplicationRunner {
    private final RagPipeline pipeline;
    private final QuestionTelemetry telemetry;
    private final EvidenceStore store;
    private final ObjectMapper json;
    private final Path cases;
    private final Path output;
    public RagExperimentRunner(RagPipeline pipeline,QuestionTelemetry telemetry,EvidenceStore store,ObjectMapper json,
            @Value("${ai.rag.eval.cases}") String cases,@Value("${ai.rag.eval.output}") String output) {
        this.pipeline=pipeline; this.telemetry=telemetry; this.store=store; this.json=json;
        this.cases=Path.of(cases); this.output=Path.of(output);
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        List<String> labelled=new ArrayList<>();
        Path artifacts=output.resolve("artifacts"); Files.createDirectories(artifacts);
        for(ObjectNode sample:EvaluationCases.read(cases,json)) {
            // A new execution must never inherit run IDs or outcomes from a previous capture.
            sample.remove(List.of("pipelineRunId","modelRunId","questionRunId","executionError","exportError"));
            String owner=sample.path("identity").asText();
            if((!owner.startsWith("name:") && !owner.startsWith("id:")) || sample.path("question").asText().isBlank())
                throw new IllegalArgumentException("Explicit trusted identity (name:/id:) and question required");
            int topK=sample.path("k").asInt(5);
            if(topK<1 || topK>100) throw new IllegalArgumentException("Invalid experiment K");
            Request base=pipeline.request(owner,sample.get("question").asText(),List.of(),Mode.FIXED_QA,topK,0);
            if(base.topK()!=topK) throw new IllegalArgumentException("Experiment K exceeds configured retrieval limit");
            Options p=base.options(); JsonNode flags=sample.path("options");
            Request request=new Request(owner,base.question(),base.history(),base.mode(),topK,0,
                new Options(flags.path("rewrite").asBoolean(p.rewrite()),flags.path("rerank").asBoolean(p.rerank()),
                    flags.path("merge").asBoolean(p.merge()),flags.path("legacyClipping").asBoolean(p.legacyClipping()),
                    p.candidateK(),p.rrfK(),p.evidenceTokens(),p.contextTokens(),p.safetyTokens(),p.rerankThreshold()));
            var rootId=new java.util.concurrent.atomic.AtomicReference<String>();
            try {
                telemetry.observe(owner,request.question(),"eval",sample.path("caseId").asText(),
                    () -> pipeline.run(request,new RagObserver(){}).map(r -> new AgentRunResult(r.answer(),r.context().references())),
                    rootId::set).block();
            } catch(Exception failure) { sample.put("executionError",failure.getClass().getSimpleName()); }
            try {
                JsonNode root=store.read(rootId.get(),owner); export(root,artifacts); sample.put("questionRunId",rootId.get());
                for(JsonNode event:root.path("events")) if("child".equals(event.path("name").asText())) {
                    String id=event.path("data").path("runId").asText(); JsonNode child=store.read(id,owner); export(child,artifacts);
                    if("pipeline".equals(child.path("kind").asText())) sample.put("pipelineRunId",id);
                    if("model-call".equals(child.path("kind").asText())) {
                        for(JsonNode modelEvent:child.path("events")) if("request".equals(modelEvent.path("name").asText())
                            && modelEvent.path("data").path("stream").asBoolean()) sample.put("modelRunId",id);
                    }
                }
            } catch(Exception incomplete) { sample.put("exportError",incomplete.getClass().getSimpleName()); }
            labelled.add(sample.toString());
        }
        Path captured=output.resolve("captured-cases.jsonl"); Files.write(captured,labelled);
        new EvaluationRunner(artifacts).run(captured,output);
    }
    private void export(JsonNode document,Path artifacts) throws Exception {
        json.writeValue(artifacts.resolve(document.path("runId").asText()+".json").toFile(),document);
    }
}
