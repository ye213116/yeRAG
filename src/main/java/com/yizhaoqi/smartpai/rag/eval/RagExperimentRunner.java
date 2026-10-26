package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.agent.AgentRunResult;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in local harness. Exactly one existing Pipeline.run subscription per ready case. */
@Component
@ConditionalOnProperty(name="ai.rag.eval.enabled",havingValue="true")
public class RagExperimentRunner implements ApplicationRunner {
    private final RagPipeline pipeline;
    private final QuestionTelemetry telemetry;
    private final EvidenceStore store;
    private final HybridSearchService search;
    private final EvaluationJudge judge;
    private final ObjectMapper json;
    private final AiProperties ai;
    private final RagProperties rag;
    private final Path datasetPath,output;
    private final String identity,setupIdentity,action;
    public RagExperimentRunner(RagPipeline pipeline,QuestionTelemetry telemetry,EvidenceStore store,HybridSearchService search,
            EvaluationJudge judge,ObjectMapper json,AiProperties ai,RagProperties rag,
            @Value("${ai.rag.eval.dataset}") String dataset,@Value("${ai.rag.eval.output}") String output,
            @Value("${ai.rag.eval.identity}") String identity,@Value("${ai.rag.eval.setup-identity:}") String setupIdentity,
            @Value("${ai.rag.eval.action:run}") String action) {
        this.pipeline=pipeline; this.telemetry=telemetry; this.store=store; this.search=search; this.judge=judge;
        this.json=json; this.ai=ai; this.rag=rag; datasetPath=Path.of(dataset); this.output=Path.of(output).toAbsolutePath();
        this.identity=identity; this.setupIdentity=setupIdentity; this.action=action;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if(!Set.of("check","run").contains(action) || !identity.matches("(?:id|name):.+")) throw new IllegalArgumentException("Action check/run and trusted id:/name: identity required");
        var dataset=EvaluationCases.load(datasetPath,json);
        if(!dataset.manifest().path("corpusVersion").asText().equals(rag.getCorpusVersion())) throw new IllegalArgumentException("Configure ai.rag.corpus-version to the dataset corpusVersion");
        if(action.equals("run") && !Double.valueOf(0).equals(ai.getGeneration().getTemperature())) throw new IllegalArgumentException("Configure ai.generation.temperature=0 for evaluation");
        if(Files.exists(output) && hasFiles(output)) throw new IllegalArgumentException("Output directory must be new/empty; old runs are immutable");
        Files.createDirectories(output);
        try { Files.setPosixFilePermissions(output,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); } catch(UnsupportedOperationException ignored) {}
        List<String> md5s=new ArrayList<>(),restricted=new ArrayList<>();
        for(JsonNode document:dataset.manifest().path("documents")) {
            md5s.add(document.path("fileMd5").asText());
            if("restricted".equals(document.path("visibility").asText())) restricted.add(document.path("fileMd5").asText());
        }
        var scan=search.scanCorpusWithPermission(identity,md5s);
        List<Candidate> audit=restricted.isEmpty() || setupIdentity.isBlank()?List.of():search.scanCorpusWithPermission(setupIdentity,restricted).chunks();
        ObjectNode preflight=new EvaluationPreflight(json).build(dataset,identity,scan.permissionFingerprint(),scan.chunks(),audit);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("preflight.json").toFile(),preflight);
        archiveDataset(dataset);
        if(action.equals("check")) return;
        Path artifacts=output.resolve("artifacts"); Files.createDirectories(artifacts);
        ObjectNode capture=json.createObjectNode().put("schemaVersion",2).put("datasetHash",dataset.hash()).put("owner",identity)
            .put("preflightSha256",preflight.path("sha256").asText()).put("corpusFingerprint",preflight.path("corpusFingerprint").asText())
            .put("corpusVersion",rag.getCorpusVersion()).put("codeVersion",rag.getCodeVersion()).put("createdAt",java.time.Instant.now().toString());
        capture.set("generationParameters",json.valueToTree(ai.getGeneration()));
        ArrayNode attempts=capture.putArray("cases");
        Map<String,JsonNode> checks=new HashMap<>(); for(JsonNode check:preflight.path("cases")) checks.put(check.path("id").asText(),check);
        for(ObjectNode sample:dataset.cases()) {
            String caseId=sample.path("id").asText(); ObjectNode attempt=attempts.addObject().put("id",caseId).put("corpusVerified",false);
            if(!"READY".equals(checks.get(caseId).path("status").asText())) { attempt.put("executionError","DATASET_INVALID"); checkpoint(capture); continue; }
            AtomicReference<String> rootId=new AtomicReference<>(); AtomicReference<Result> result=new AtomicReference<>();
            try {
                verifyCorpus(scan,search.scanCorpusWithPermission(identity,md5s));
                Request request=pipeline.request(identity,sample.path("question").asText(),List.of(),Mode.FIXED_QA,5,0);
                if(request.topK()!=5) throw new IllegalStateException("Configured retrieval limit is below five");
                telemetry.observe(identity,request.question(),"eval",caseId,
                    () -> pipeline.run(request,new RagObserver(){}).doOnNext(result::set)
                        .map(r -> new AgentRunResult(r.answer(),r.context().references())),rootId::set).block();
            } catch(Exception failure) { attempt.put("executionError",failure.getClass().getSimpleName()); }
            try {
                if(rootId.get()!=null) exportQuestion(rootId.get(),artifacts,attempt);
            } catch(Exception exportFailure) { attempt.put("exportError",exportFailure.getClass().getSimpleName()); }
            try { verifyCorpus(scan,search.scanCorpusWithPermission(identity,md5s)); attempt.put("corpusVerified",true); }
            catch(Exception changed) { attempt.put("corpusError","CORPUS_OR_PERMISSION_UNAVAILABLE_OR_CHANGED"); }
            if(result.get()!=null && attempt.hasNonNull("pipelineRunId")) {
                try {
                    var preview=new EvaluationRunner(artifacts).evaluate(sample,checks.get(caseId),preflight,attempt,new EvaluationReviews(null,json));
                    if(preview.path("deterministic").path("generationComplete").asBoolean()) {
                        JsonNode assessment=judge.assess(identity,dataset.hash(),sample,result.get().runId(),result.get().context().content(),result.get().answer()).block();
                        export(assessment,artifacts); attempt.put("judgeRunId",assessment.path("runId").asText());
                        for(JsonNode event:assessment.path("events")) if("child".equals(event.path("name").asText())) export(store.read(event.path("data").path("runId").asText(),identity),artifacts);
                    }
                } catch(Exception failed) { attempt.put("judgeError",failed.getClass().getSimpleName()); }
            }
            checkpoint(capture);
        }
        EvaluationPreflight.seal(capture);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("capture.json").toFile(),capture);
        new EvaluationRunner(artifacts).run(output.resolve("dataset"),output.resolve("preflight.json"),output.resolve("capture.json"),output.resolve("report"),null);
    }
    private static boolean hasFiles(Path path) throws Exception { try(var files=Files.list(path)) { return files.findAny().isPresent(); } }
    private void verifyCorpus(HybridSearchService.CorpusScan expected,HybridSearchService.CorpusScan actual) {
        if(!expected.permissionFingerprint().equals(actual.permissionFingerprint()) || !EvaluationPreflight.fingerprint(expected.chunks()).equals(EvaluationPreflight.fingerprint(actual.chunks())))
            throw new IllegalStateException("Corpus or permission changed");
    }
    private void exportQuestion(String rootId,Path directory,ObjectNode attempt) throws Exception {
        JsonNode root=store.read(rootId,identity); export(root,directory); attempt.put("questionRunId",rootId);
        List<JsonNode> children=new ArrayList<>();
        for(JsonNode event:root.path("events")) if("child".equals(event.path("name").asText())) {
            JsonNode child=store.read(event.path("data").path("runId").asText(),identity); export(child,directory); children.add(child);
        }
        var pipelines=children.stream().filter(c -> "pipeline".equals(c.path("kind").asText())).toList();
        if(pipelines.size()!=1) throw new IllegalStateException("Expected exactly one Pipeline execution");
        String pipelineId=pipelines.get(0).path("runId").asText(); attempt.put("pipelineRunId",pipelineId);
        var models=children.stream().filter(c -> pipelineId.equals(c.path("parentRunId").asText()) && "model-call".equals(c.path("kind").asText())
            && EvaluationArtifacts.event(c,"request").path("stream").asBoolean()).toList();
        if(models.size()>1) throw new IllegalStateException("Multiple final model calls");
        if(models.size()==1) attempt.put("modelRunId",models.get(0).path("runId").asText());
    }
    private void export(JsonNode document,Path directory) throws Exception {
        Files.writeString(directory.resolve(document.path("runId").asText()+".json"),document.toString(),StandardOpenOption.CREATE_NEW);
    }
    private void checkpoint(ObjectNode capture) throws Exception {
        ObjectNode copy=capture.deepCopy(); EvaluationPreflight.seal(copy);
        Path temporary=output.resolve("capture.partial.tmp"); json.writeValue(temporary.toFile(),copy);
        Files.move(temporary,output.resolve("capture.partial.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private void archiveDataset(EvaluationCases.Dataset dataset) throws Exception {
        Path archive=output.resolve("dataset"); Files.createDirectories(archive);
        Files.copy(dataset.directory().resolve("dataset.json"),archive.resolve("dataset.json"));
        Files.copy(dataset.directory().resolve("cases.jsonl"),archive.resolve("cases.jsonl"));
        for(JsonNode document:dataset.manifest().path("documents")) {
            Path relative=Path.of(document.path("path").asText()),destination=archive.resolve(relative); Files.createDirectories(destination.getParent());
            Files.copy(dataset.directory().resolve(relative),destination);
        }
    }
}
