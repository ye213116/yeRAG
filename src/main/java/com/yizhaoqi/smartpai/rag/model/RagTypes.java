package com.yizhaoqi.smartpai.rag.model;

import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import java.util.*;

public final class RagTypes {
    private RagTypes() {}
    public enum StageKey { QUERY, RETRIEVE, RERANK, MERGE, ASSEMBLE, GENERATE }
    public enum Status { SUCCEEDED, SKIPPED, DEGRADED, NO_EVIDENCE, FAILED, CANCELLED, TIMED_OUT, TRUNCATED }
    public enum Mode { RETRIEVE_ONLY, FIXED_QA }
    public record Candidate(String id, String fileMd5, int chunkIndex, String owner, String title,
                            String text, double score, Map<String, Double> routeScores,
                            List<String> sources, String esIndex, String esHitId) {
        public Candidate {
            Objects.requireNonNull(id); text = Objects.requireNonNullElse(text, "");
            title = Objects.requireNonNullElse(title, "unknown"); owner = Objects.requireNonNullElse(owner, "");
            routeScores = Map.copyOf(routeScores); sources = List.copyOf(sources);
        }
        public Candidate(String id,String md5,int chunk,String owner,String title,String text,double score,
                         Map<String,Double> routeScores,List<String> sources) {
            this(id,md5,chunk,owner,title,text,score,routeScores,sources,null,null);
        }
        public Candidate scored(double value, Map<String, Double> scores) {
            return new Candidate(id, fileMd5, chunkIndex, owner, title, text, value, scores, sources,esIndex,esHitId);
        }
    }
    public record RouteResult(String route, Status status, List<Candidate> candidates, long elapsedMs, String reason) {
        public RouteResult { candidates = List.copyOf(candidates); }
    }
    public record RankedChunk(String id,String owner,String fileMd5,int chunkId,String esIndex,String esHitId,
                              String text,String sourceHash,double score,String scoreKind,int rank) {
        public static RankedChunk of(Candidate c,int rank,String kind) {
            return new RankedChunk(c.id(),c.owner(),c.fileMd5(),c.chunkIndex(),c.esIndex(),c.esHitId(),
                c.text(),com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder.hash(c.text()),c.score(),kind,rank);
        }
    }
    public record RetrievalResult(Status status, List<RouteResult> routes, List<Candidate> candidates) {
        public RetrievalResult { routes = List.copyOf(routes); candidates = List.copyOf(candidates); }
    }
    public record Decision(String candidateId, boolean kept, String reason, int beforeRank, int afterRank,
                           Double score) {}
    public record Selection(List<Candidate> candidates, List<Decision> decisions) {
        public Selection { candidates = List.copyOf(candidates); decisions = List.copyOf(decisions); }
    }
    public record SourceRange(String sourceId, String sourceHash, int sourceStart, int sourceEnd,
                              int outputStart, int outputEnd) {}
    public record Passage(String id, String fileMd5, String title, String text, double score, List<SourceRange> sources,Integer chunkId) {
        public Passage { sources = List.copyOf(sources); }
        public Passage(String id,String md5,String title,String text,double score,List<SourceRange> sources) {
            this(id,md5,title,text,score,sources,null);
        }
    }
    public record ContextDecision(String id, String reason, int originalLength, int keptLength,
                                  int originalTokens, int keptTokens) {}
    public record ContextResult(String content, String observation, List<Passage> passages,
                                Map<Integer, String> references, List<ContextDecision> decisions,
                                int estimatedTokens, String tokenCountKind) {
        public ContextResult {
            passages = List.copyOf(passages); references = Map.copyOf(references); decisions = List.copyOf(decisions);
        }
    }
    public record Options(boolean rewrite, boolean rerank, boolean merge, boolean legacyClipping,
                          int candidateK, double rrfK, int evidenceTokens, int contextTokens,
                          int safetyTokens, double rerankThreshold) {}
    public record Request(String userId, String question, List<AgentMessage> history, Mode mode,
                          int topK, int referenceOffset, Options options) {
        public Request {
            if (userId == null || userId.isBlank() || question == null || question.isBlank() || topK < 1)
                throw new IllegalArgumentException("Authenticated user, query and positive topK required");
            history = List.copyOf(history);
        }
    }
    public record StageOutcome(Status status, String reason, Object evidence) {}
    public record Result(String runId, Status status, ContextResult context, String answer) {}
    public record GenerationEvent(String text, String finishReason, Map<String, Long> usage, boolean terminal) {
        public GenerationEvent { text = Objects.requireNonNullElse(text, ""); usage = usage == null ? null : Map.copyOf(usage); }
    }
}
