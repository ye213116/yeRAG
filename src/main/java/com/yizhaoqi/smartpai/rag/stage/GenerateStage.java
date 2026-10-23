package com.yizhaoqi.smartpai.rag.stage;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.*;

@Component
public class GenerateStage implements RagStage {
    private final DeepSeekClient llm;
    public GenerateStage(DeepSeekClient llm) { this.llm=llm; }
    public StageKey key() { return StageKey.GENERATE; }
    public Mono<StageOutcome> execute(RagContext c) {
        return Mono.defer(() -> {
            if (c.state().context().passages().isEmpty()) {
                String answer="当前知识库中没有足够资料回答这个问题。";
                c.state().answer(answer); c.observer().chunk(answer);
                return Mono.just(new StageOutcome(Status.NO_EVIDENCE,"FIXED_ABSTENTION",Map.of("answer",answer)));
            }
            List<AgentMessage> messages=new ArrayList<>();
            messages.add(AgentMessage.system(AssembleStage.RULES));
            messages.addAll(c.request().history());
            messages.add(AgentMessage.user("参考资料：\n"+c.state().context().content()+"\n问题："+c.request().question()));
            StringBuilder answer=new StringBuilder();
            return llm.streamDetailed(List.copyOf(messages)).doOnNext(event -> {
                if (!event.text().isEmpty()) { answer.append(event.text()); c.observer().chunk(event.text()); }
            }).then(Mono.fromSupplier(() -> {
                c.state().answer(answer.toString());
                return new StageOutcome(Status.SUCCEEDED,"GENERATED",Map.of("answer",answer.toString()));
            }));
        });
    }
}
