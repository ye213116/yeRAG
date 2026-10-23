package com.yizhaoqi.smartpai.rag;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate;
import reactor.core.publisher.Mono;
import java.util.*;
public interface Reranker { Mono<Map<String,Double>> rerank(String query,List<Candidate> candidates); }
