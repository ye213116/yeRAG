package com.yizhaoqi.smartpai.rag.trace;

import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.rag.RagFailure;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public class ToolTelemetry {
    private final EvidenceRecorder recorder;
    public ToolTelemetry(EvidenceRecorder recorder) { this.recorder=recorder; }
    public Mono<ToolExecutionResult> observe(String name,String arguments,ToolExecutionContext tool,Supplier<Mono<ToolExecutionResult>> work) {
        return Mono.deferContextual(view -> {
            RunContext parent=view.getOrDefault(RunContext.KEY,null);
            if(parent==null) return Mono.defer(work);
            parent.remaining();
            var run=recorder.start(parent.owner(),parent.parentRunId(),"tool-call",Map.of(
                "name",name,"arguments",arguments,"toolCallId",Objects.toString(tool.toolCallId(),""),"referenceOffset",tool.referenceOffset()));
            var child=parent.child(run.id());
            return Mono.defer(work).publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).doOnNext(result -> { run.event("result",result); run.finish(result.success()?Status.SUCCEEDED:Status.FAILED); })
                .doOnError(error -> run.finish(RagFailure.status(error)))
                .doFinally(signal -> { if(signal==SignalType.CANCEL) run.finish(Status.CANCELLED); })
                .contextWrite(c -> c.put(RunContext.KEY,child));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
