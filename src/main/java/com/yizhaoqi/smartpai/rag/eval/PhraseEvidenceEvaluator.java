package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;

/** Scores original child chunks. Expanded or assembled text can diagnose loss, never improve retrieval hit. */
final class PhraseEvidenceEvaluator {
    private final ObjectMapper json;

    PhraseEvidenceEvaluator(ObjectMapper json) { this.json = json; }

    void validate(JsonNode sample) {
        if (!sample.path("expectedEvidence").isArray() || sample.path("expectedEvidence").isEmpty())
            throw new IllegalArgumentException("Nonempty expectedEvidence required for phrase scoring");
        for (JsonNode phrase : sample.get("expectedEvidence"))
            if (!phrase.isTextual() || normalize(phrase.asText()).isEmpty())
                throw new IllegalArgumentException("Evidence phrases must be nonblank strings");
        if (!Set.of("hit", "miss").contains(sample.path("retrievalExpectation").asText("hit")))
            throw new IllegalArgumentException("retrievalExpectation must be hit or miss");
    }

    ObjectNode metrics(JsonNode sample, JsonNode candidates, int k) {
        if (!candidates.isArray()) throw new IllegalStateException("Child candidate evidence missing");
        var phrases = phrases(sample);
        ObjectNode result = json.createObjectNode().put("surface", "RERANK_CHILD_TEXT").put("k", k);
        var matches = result.putArray("matches");
        Set<String> matched = new HashSet<>();
        Set<String> documents = expectedDocuments(sample);
        Set<String> foundDocuments = new HashSet<>();
        Set<String> seen = new HashSet<>();
        int rank = 0, firstRank = 0;
        boolean md5 = sample.path("expectedFileMd5s").isArray() && !sample.path("expectedFileMd5s").isEmpty();
        for (JsonNode candidate : candidates) {
            String id = candidate.path("id").asText();
            if (id.isBlank() || !candidate.path("text").isTextual())
                throw new IllegalStateException("Missing child identity/text");
            if (!seen.add(id)) continue;
            if (++rank > k) break;
            String document = documentKey(candidate.path(md5 ? "fileMd5" : "title").asText());
            if (documents.contains(document)) foundDocuments.add(document);
            List<String> hits = matching(phrases, candidate.path("text").asText());
            if (hits.isEmpty()) continue;
            if (firstRank == 0) firstRank = rank;
            matched.addAll(hits.stream().map(PhraseEvidenceEvaluator::normalize).toList());
            ObjectNode match = matches.addObject().put("candidateId", id).put("rank", rank);
            match.set("fileMd5", candidate.path("fileMd5"));
            match.set("fileName", candidate.path("title"));
            match.set("chunkId", candidate.path("chunkIndex"));
            match.set("phrases", json.valueToTree(hits));
        }
        result.put("hitAtK", firstRank > 0).put("mrrAtK", firstRank == 0 ? 0 : 1.0 / firstRank)
            .put("top1Evidence", firstRank == 1).put("firstMatchRank", firstRank)
            .put("evidencePhraseCoverage", (double) matched.size() / phrases.size());
        if (!documents.isEmpty()) result.put("docRecallAtK", (double) foundDocuments.size() / documents.size());
        return result;
    }

    String diagnosis(JsonNode sample, JsonNode retrieved, JsonNode ranked, JsonNode merged, JsonNode context, int k) {
        if (sample.path("queryDrift").asBoolean()) return "QUERY_DRIFT_REVIEWED";
        if (!present(sample, retrieved, "text", Integer.MAX_VALUE)) return "RETRIEVAL_MISS";
        if (!present(sample, ranked, "text", k)) {
            if (!present(sample, ranked, "text", Integer.MAX_VALUE)) return "RERANK_DROP";
            return present(sample, retrieved, "text", k) ? "RERANK_TOP_K_LOSS" : "RETRIEVAL_TOP_K_MISS";
        }
        if (!present(sample, merged, "text", Integer.MAX_VALUE)) return "MERGE_LOSS";
        return present(sample, context, "text", Integer.MAX_VALUE) ? "CONTEXT_RETAINED" : "CONTEXT_LOSS";
    }

    void classify(JsonNode sample, ObjectNode row, JsonNode pipelineRequest) {
        row.put("bucketStatus", "PROVISIONAL"); // No corpus/ACL preflight artifact is produced yet.
        if (!"FIXED_QA".equals(pipelineRequest.path("mode").asText())) {
            row.put("bucket", "DIAGNOSTIC_ONLY");
            return;
        }
        boolean hit = row.path("phraseRetrieval").path("hitAtK").asBoolean();
        boolean negative = "miss".equals(sample.path("retrievalExpectation").asText("hit"));
        if (negative && hit) { row.put("bucket", "UNEXPECTED_HIT"); return; }
        if (!row.path("answerCorrect").isBoolean()) { row.put("bucket", "PENDING_JUDGE"); return; }
        boolean correct = row.get("answerCorrect").asBoolean();
        row.put("bucket", negative ? (correct ? "EXPECTED_BEHAVIOR" : "NEGATIVE_ANSWER_FAILURE")
            : hit ? (correct ? "PASS" : "B_DOWNSTREAM") : (correct ? "C_REVIEW" : "A_RETRIEVAL"));
    }

    private boolean present(JsonNode sample, JsonNode candidates, String field, int limit) {
        if (!candidates.isArray()) throw new IllegalStateException("Missing stage text");
        int rank = 0;
        for (JsonNode candidate : candidates) {
            if (++rank > limit) break;
            if (!matching(phrases(sample), candidate.path(field).asText()).isEmpty()) return true;
        }
        return false;
    }

    private static List<String> phrases(JsonNode sample) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (JsonNode phrase : sample.path("expectedEvidence")) unique.putIfAbsent(normalize(phrase.asText()), phrase.asText());
        return List.copyOf(unique.values());
    }

    private static List<String> matching(List<String> phrases, String text) {
        String normalized = normalize(text);
        return phrases.stream().filter(phrase -> normalized.contains(normalize(phrase))).toList();
    }

    private static Set<String> expectedDocuments(JsonNode sample) {
        JsonNode labels = sample.path("expectedFileMd5s").isArray() && !sample.path("expectedFileMd5s").isEmpty()
            ? sample.path("expectedFileMd5s") : sample.path("expectedFileNames");
        Set<String> result = new HashSet<>();
        for (JsonNode label : labels) if (!documentKey(label.asText()).isBlank()) result.add(documentKey(label.asText()));
        return result;
    }

    private static String documentKey(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        StringBuilder result = new StringBuilder();
        Normalizer.normalize(text, Normalizer.Form.NFKC).codePoints()
            .filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).forEach(result::appendCodePoint);
        return result.toString();
    }
}
