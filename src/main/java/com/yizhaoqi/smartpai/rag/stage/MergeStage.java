package com.yizhaoqi.smartpai.rag.stage;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.*;

@Component
public class MergeStage implements RagStage {
    public StageKey key() { return StageKey.MERGE; }
    public Mono<StageOutcome> execute(RagContext c) {
        return Mono.fromSupplier(() -> {
            List<Candidate> input=c.state().rerank().candidates();
            List<Passage> passages=new ArrayList<>();
            List<Decision> decisions=new ArrayList<>();
            Set<String> used=new HashSet<>();
            Set<String> texts=new HashSet<>();
            for (Candidate seed : input) {
                if (used.contains(seed.id())) continue;
                String text=seed.text();
                String hash=EvidenceRecorder.hash(text);
                if (c.request().options().merge() && !texts.add(hash)) {
                    decisions.add(new Decision(seed.id(),false,"DUPLICATE",0,0,null)); continue;
                }
                List<Candidate> group=c.request().options().merge() && seed.fileMd5()!=null && !seed.fileMd5().isBlank()?input.stream()
                    .filter(v -> Objects.equals(v.fileMd5(),seed.fileMd5()) && v.owner().equals(seed.owner()) && !used.contains(v.id()))
                    .sorted(Comparator.comparingInt(Candidate::chunkIndex).thenComparing(Candidate::id)).toList():List.of(seed);
                int start=group.indexOf(seed),end=start;
                while (start>0 && group.get(start-1).chunkIndex()+1==group.get(start).chunkIndex()) start--;
                while (end+1<group.size() && group.get(end).chunkIndex()+1==group.get(end+1).chunkIndex()) end++;
                StringBuilder content=new StringBuilder(); List<SourceRange> sources=new ArrayList<>();
                double score=seed.score();
                for (Candidate part : group.subList(start,end+1)) {
                    used.add(part.id());
                    if (!content.isEmpty()) content.append("\n\n");
                    int offset=content.length(); content.append(part.text()); score=Math.max(score,part.score());
                    sources.add(new SourceRange(part.id(),EvidenceRecorder.hash(part.text()),0,part.text().length(),offset,content.length()));
                    decisions.add(new Decision(part.id(),true,end>start?"ADJACENT_RETRIEVED_CHUNKS":"KEPT",0,passages.size()+1,part.score()));
                }
                passages.add(new Passage(seed.id(),seed.fileMd5(),seed.title(),content.toString(),score,sources,group.get(start).chunkIndex()));
            }
            c.state().merge(passages);
            return new StageOutcome(c.request().options().merge()?Status.SUCCEEDED:Status.SKIPPED,
                c.request().options().merge()?"MERGED_RETRIEVED_CHUNKS":"DISABLED",Map.of("passages",passages,"decisions",decisions));
        });
    }
}
