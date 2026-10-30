package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentLlmClient;
import com.yizhaoqi.smartpai.agent.model.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.memory.MemoryProperties;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;
import java.util.*;
import java.util.function.Consumer;

@Service
public class DeepSeekClient implements AgentLlmClient {
    private final WebClient webClient;
    private final String model;
    private final AiProperties ai;
    private final ObjectMapper json=new ObjectMapper();
    private RagProperties rag=new RagProperties();
    private EvidenceRecorder recorder;
    @Autowired(required=false) private MemoryProperties memory;

    public DeepSeekClient(@Value("${deepseek.api.url}") String url,@Value("${deepseek.api.key}") String key,
                          @Value("${deepseek.api.model}") String model,AiProperties ai) {
        var builder=WebClient.builder().baseUrl(url)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024));
        if(key!=null && !key.isBlank()) builder.defaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+key);
        webClient=builder.build(); this.model=model; this.ai=ai;
    }
    @Autowired
    public void configureRag(RagProperties properties,EvidenceRecorder recorder) { this.rag=properties; this.recorder=recorder; }

    @Override public Mono<AgentDecision> decide(List<AgentMessage> messages,List<Map<String,Object>> tools) {
        return decideInternal(messages,tools,null,null);
    }
    /** Independent semantic review, sharing transport, completion validation and evidence capture. */
    public Mono<String> judgeCompletion(List<AgentMessage> messages) {
        return judgeCompletion(messages,null);
    }
    public Mono<String> judgeCompletion(List<AgentMessage> messages,String judgeModel) {
        return decideInternal(messages,List.of(),0.0,judgeModel).map(decision -> {
            if(decision.requestsTool()) throw new RagFailure(Status.FAILED,"JUDGE_TOOL_CALL_FORBIDDEN");
            return decision.content();
        });
    }
    private Mono<AgentDecision> decideInternal(List<AgentMessage> messages,List<Map<String,Object>> tools,Double temperature,String modelOverride) {
        return Mono.deferContextual(view -> {
            Map<String,Object> request=request(messages,false);
            if(temperature!=null) request.put("temperature",temperature);
            if(modelOverride!=null && !modelOverride.isBlank()) request.put("model",modelOverride);
            if(!tools.isEmpty()) { request.put("tools",tools); request.put("tool_choice","auto"); }
            var run=prepare(view,request);
            return Mono.fromRunnable(() -> dispatch(request,run)).then(webClient.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve().bodyToMono(String.class))
                .publishOn(Schedulers.boundedElastic()).map(body -> {
                    try {
                        JsonNode response=json.readTree(body);
                        if(run!=null) run.event("response",response);
                        String reason=response.path("choices").path(0).path("finish_reason").asText("UNKNOWN").toLowerCase(Locale.ROOT);
                        if(Set.of("length","max_tokens","max_output_tokens").contains(reason)) throw new RagFailure(Status.TRUNCATED,"MODEL_OUTPUT_TRUNCATED");
                        if(!Set.of("stop","tool_calls","function_call","end_turn","eos_token").contains(reason)) throw new RagFailure(Status.FAILED,"MODEL_FINISH_REASON_INVALID");
                        AgentDecision decision=parseDecision(json,body);
                        if(!decision.requestsTool() && (decision.content()==null || decision.content().isBlank())) throw new RagFailure(Status.FAILED,"MODEL_ANSWER_EMPTY");
                        if(run!=null) {
                            Map<String,Object> timing=new LinkedHashMap<>();
                            timing.put("firstModelOutputMs",run.elapsed()); timing.put("firstAnswerMs",decision.requestsTool()?null:run.elapsed());
                            timing.put("finishReason",reason); timing.put("usage",response.hasNonNull("usage")?response.get("usage"):null);
                            timing.put("usageStatus",response.hasNonNull("usage")?"REPORTED":"UNAVAILABLE");
                            run.event("generation",timing); run.finish(Status.SUCCEEDED);
                        }
                        return decision;
                    } catch(RagFailure failure) { throw failure; }
                    catch(Exception invalid) { throw new RagFailure(Status.FAILED,"MODEL_RESPONSE_INVALID"); }
                }).doOnError(error -> { if(run!=null) run.finish(RagFailure.status(error)); })
                .doFinally(signal -> { if(run!=null && signal==SignalType.CANCEL) run.finish(Status.CANCELLED); });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override public reactor.core.publisher.Flux<String> streamFinal(List<AgentMessage> messages) {
        return streamDetailed(messages).map(GenerationEvent::text).filter(text -> !text.isEmpty());
    }
    public reactor.core.publisher.Flux<GenerationEvent> streamDetailed(List<AgentMessage> messages) {
        return reactor.core.publisher.Flux.deferContextual(view -> {
            Map<String,Object> request=request(messages,true);
            var run=prepare(view,request); var decoder=new GenerationDecoder(json);
            StringBuilder answer=new StringBuilder(); long started=System.nanoTime(); long[] first={-1,-1};
            return Mono.fromRunnable(() -> dispatch(request,run)).thenMany(webClient.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue(request)
                .retrieve().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>(){}))
                .publishOn(Schedulers.boundedElastic()).filter(event -> event.data()!=null)
                .map(event -> {
                    if("error".equals(event.event())) throw new RagFailure(Status.FAILED,"MODEL_SSE_ERROR");
                    GenerationEvent decoded=decoder.accept(event.data()); long elapsed=(System.nanoTime()-started)/1_000_000;
                    if(first[0]<0 && decoder.hasModelOutput()) first[0]=elapsed;
                    if(!decoded.text().isEmpty()) { if(first[1]<0) first[1]=elapsed; answer.append(decoded.text()); }
                    return decoded;
                }).concatWith(Mono.defer(() -> { decoder.verifyComplete(); return Mono.empty(); }))
                .doOnComplete(() -> finish(run,answer.toString(),decoder,first,Status.SUCCEEDED))
                .doOnError(error -> finish(run,answer.toString(),decoder,first,RagFailure.status(error)))
                .doFinally(signal -> { if(signal==SignalType.CANCEL) finish(run,answer.toString(),decoder,first,Status.CANCELLED); });
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private void finish(EvidenceRecorder.Run run,String answer,GenerationDecoder decoder,long[] first,Status status) {
        if(run==null) return;
        Map<String,Object> result=new LinkedHashMap<>(); result.put("model",model); result.put("answer",answer);
        result.put("finishReason",decoder.reason()); result.put("usage",decoder.usage());
        result.put("firstModelOutputMs",first[0]<0?null:first[0]); result.put("firstAnswerMs",first[1]<0?null:first[1]);
        result.put("usageStatus",decoder.usage()==null?"UNAVAILABLE":"REPORTED");
        run.event("generation",result); run.finish(status);
    }
    private EvidenceRecorder.Run start(ContextView view,Map<String,Object> request) {
        RunContext context=view.getOrDefault(RunContext.KEY,null);
        if(context==null || recorder==null) return null;
        context.remaining(); var run=recorder.start(context.owner(),context.parentRunId(),"model-call",request);
        context.child(run.id()); return run;
    }
    private EvidenceRecorder.Run prepare(ContextView view,Map<String,Object> request) {
        int output=ai.getGeneration().getMaxTokens()==null?8192:ai.getGeneration().getMaxTokens();
        int limit=rag.inputTokenLimit(output);
        try {
            var counter=TokenBudget.counter(rag.getTokenizerResource());
            var compacted=ToolContextBudget.fit(json,request,limit,counter);
            var run=start(view,request);
            if(run!=null && compacted.truncatedTools()>0) run.event("contextCompaction",Map.of(
                "originalBytes",compacted.originalBytes(),"finalBytes",compacted.finalBytes(),
                "originalTokens",compacted.originalTokens(),"finalTokens",compacted.finalTokens(),
                "truncatedTools",compacted.truncatedTools(),"inputLimitTokens",limit,"tokenCountKind",counter.kind()));
            return run;
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new RagFailure(Status.FAILED,"MODEL_REQUEST_INVALID");
        }
    }
    private void dispatch(Map<String,Object> request,EvidenceRecorder.Run run) {
        try {
            byte[] encoded=json.writeValueAsBytes(request);
            int output=ai.getGeneration().getMaxTokens()==null?8192:ai.getGeneration().getMaxTokens();
            var counter=TokenBudget.counter(rag.getTokenizerResource());
            int inputTokens=counter.requestTokens(json.valueToTree(request));
            if(output<1 || (long)inputTokens+output+rag.getSafetyTokens()>rag.getContextTokens()) throw new RagFailure(Status.FAILED,"MODEL_INPUT_BUDGET_EXCEEDED");
            if(memory!=null && encoded.length>memory.getMaxInputBytes()) throw new RagFailure(Status.FAILED,"MEMORY_INPUT_BUDGET_EXCEEDED");
            if(run!=null) run.event("dispatch",Map.of("requestSha256",EvidenceRecorder.hash(json.valueToTree(request).toString()),
                "inputTokens",inputTokens,"inputBytes",encoded.length,"reservedOutputTokens",output,
                "safetyTokens",rag.getSafetyTokens(),"modelContextTokens",rag.getContextTokens(),
                "modelProfile",rag.getModelProfile(),"tokenCountKind",counter.kind()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new RagFailure(Status.FAILED,"MODEL_REQUEST_INVALID"); }
    }
    private Map<String,Object> request(List<AgentMessage> messages,boolean stream) {
        Map<String,Object> request=new LinkedHashMap<>(); request.put("model",model);
        request.put("messages",messages.stream().map(this::wire).toList()); request.put("stream",stream);
        var generation=ai.getGeneration();
        if(generation.getTemperature()!=null) request.put("temperature",generation.getTemperature());
        if(generation.getTopP()!=null) request.put("top_p",generation.getTopP());
        if(generation.getMaxTokens()!=null) request.put("max_tokens",generation.getMaxTokens());
        return request;
    }
    private Map<String,Object> wire(AgentMessage message) {
        Map<String,Object> wire=new LinkedHashMap<>(); wire.put("role",message.role());
        if(message.content()!=null) wire.put("content",message.content());
        if(message.toolCallId()!=null) wire.put("tool_call_id",message.toolCallId());
        if(!message.toolCalls().isEmpty()) wire.put("tool_calls",message.toolCalls().stream().map(call -> Map.of(
            "id",call.id(),"type","function","function",Map.of("name",call.name(),"arguments",call.arguments()))).toList());
        return wire;
    }
    static AgentDecision parseDecision(ObjectMapper mapper,String body) throws Exception {
        JsonNode choices=mapper.readTree(body).path("choices");
        if(!choices.isArray() || choices.isEmpty()) throw new IllegalArgumentException("Response has no choices");
        JsonNode message=choices.path(0).path("message");
        String content=message.path("content").isNull()?null:message.path("content").asText(null);
        List<ToolCall> calls=new ArrayList<>();
        for(JsonNode call:message.path("tool_calls")) {
            JsonNode function=call.path("function");
            if(!"function".equals(call.path("type").asText()) || !call.path("id").isTextual() || call.path("id").asText().isBlank()
                    || !function.path("name").isTextual() || function.path("name").asText().isBlank() || !function.path("arguments").isTextual())
                throw new IllegalArgumentException("Malformed tool call");
            calls.add(new ToolCall(call.path("id").asText(),function.path("name").asText(),function.path("arguments").asText()));
        }
        return new AgentDecision(content,calls);
    }

    public void streamResponse(String question,String context,List<Map<String,String>> history,Consumer<String> chunks,Consumer<Throwable> errors) {
        streamResponse(question,context,history,chunks,errors,() -> {});
    }
    public void streamResponse(String question,String context,List<Map<String,String>> history,Consumer<String> chunks,Consumer<Throwable> errors,Runnable done) {
        streamResponseDisposable(question,context,history,chunks,errors,done);
    }
    public Disposable streamResponseDisposable(String question,String context,List<Map<String,String>> history,
            Consumer<String> chunks,Consumer<Throwable> errors,Runnable done) {
        List<AgentMessage> messages=new ArrayList<>();
        messages.add(AgentMessage.system(com.yizhaoqi.smartpai.rag.stage.AssembleStage.RULES));
        if(history!=null) for(var item:history) if(Set.of("user","assistant").contains(item.get("role")))
            messages.add(new AgentMessage(item.get("role"),item.get("content"),null,List.of()));
        messages.add(AgentMessage.user("参考资料：\n"+Objects.toString(context,"")+"\n问题："+question));
        return streamFinal(messages).subscribe(chunks,errors,done);
    }
}
