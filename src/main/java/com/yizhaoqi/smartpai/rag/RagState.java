package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import java.util.*;

/** One subscription owns this state. Each slot is published once, only after its stage completes. */
public final class RagState {
    private String query;
    private RetrievalResult retrieval;
    private Selection rerank;
    private List<Passage> merge;
    private ContextResult context;
    private String answer;
    private final Set<StageKey> published=EnumSet.noneOf(StageKey.class);
    private void claim(StageKey key) { if (!published.add(key)) throw new IllegalStateException("Stage already published: "+key); }
    public void query(String v) { claim(StageKey.QUERY); query=v; }
    public void retrieval(RetrievalResult v) { claim(StageKey.RETRIEVE); retrieval=v; }
    public void rerank(Selection v) { claim(StageKey.RERANK); rerank=v; }
    public void merge(List<Passage> v) { claim(StageKey.MERGE); merge=List.copyOf(v); }
    public void context(ContextResult v) { claim(StageKey.ASSEMBLE); context=v; }
    public void answer(String v) { claim(StageKey.GENERATE); answer=v; }
    public String query() { return Objects.requireNonNull(query); }
    public RetrievalResult retrieval() { return Objects.requireNonNull(retrieval); }
    public Selection rerank() { return Objects.requireNonNull(rerank); }
    public List<Passage> merge() { return Objects.requireNonNull(merge); }
    public ContextResult context() { return Objects.requireNonNull(context); }
    public String answer() { return answer==null?"":answer; }
}
