package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRecallClientTest {

    private FakeRecallClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    void runsBothRoutesAndKeepsTheHealthyRouteWhenTheOtherFails() {
        client = new FakeRecallClient();
        client.knnFailure = new IllegalStateException("vector circuit open");

        HybridRecallClient.RecallResult recall = client.recallBoth("q", List.of(0.1f), Query.of(q -> q.matchAll(m -> m)),
                150, Duration.ofSeconds(2));

        assertEquals(List.of("bm25#1"), keys(recall.bm25()));
        assertTrue(recall.knn().isEmpty());
        assertTrue(client.calls.containsAll(List.of("bm25", "knn")));
    }

    @Test
    void returnsEmptyOnlyWhenBothRoutesFail() {
        client = new FakeRecallClient();
        client.bm25Failure = new IllegalStateException("es down");
        client.knnFailure = new IllegalStateException("es down");

        HybridRecallClient.RecallResult recall = client.recallBoth("q", List.of(0.1f), Query.of(q -> q.matchAll(m -> m)),
                150, Duration.ofSeconds(2));

        assertTrue(recall.isEmpty());
    }

    @Test
    void doesNotBlockTheFastRouteOnASlowRoute() {
        client = new FakeRecallClient();
        client.knnDelayMillis = 400;

        long start = System.currentTimeMillis();
        HybridRecallClient.RecallResult recall = client.recallBoth("q", List.of(0.1f), Query.of(q -> q.matchAll(m -> m)),
                150, Duration.ofMillis(80));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(List.of("bm25#1"), keys(recall.bm25()));
        assertTrue(recall.knn().isEmpty());
        assertTrue(elapsed < 350, "超时的一路不应拖住另一路，实际耗时: " + elapsed + "ms");
    }

    @Test
    void aclQueryCombinesOwnPublicAndOrganizationDocuments() {
        Query acl = HybridRecallClient.aclQuery("42", List.of("TECH", "FINANCE"));

        assertTrue(acl.isBool());
        BoolQuery bool = acl.bool();
        assertEquals("1", bool.minimumShouldMatch());
        assertEquals(3, bool.should().size());

        assertTrue(bool.should().stream().anyMatch(q -> q.isTerm()
                && "userId".equals(q.term().field()) && "42".equals(q.term().value().stringValue())));
        assertTrue(bool.should().stream().anyMatch(q -> q.isBool()
                && q.bool().should().size() == 2
                && q.bool().should().stream().allMatch(inner -> inner.isTerm()
                && ("public".equals(inner.term().field()) || "isPublic".equals(inner.term().field())))));
        assertTrue(bool.should().stream().anyMatch(q -> q.isBool()
                && q.bool().should().size() == 2
                && q.bool().should().stream().allMatch(inner -> inner.isTerm()
                && "orgTag".equals(inner.term().field()))));
    }

    @Test
    void aclQueryDropsOrganizationClauseWhenUserHasNoTags() {
        Query acl = HybridRecallClient.aclQuery("42", List.of());

        assertEquals(2, acl.bool().should().size());
        assertFalse(acl.bool().should().stream().anyMatch(q -> q.isBool() && q.bool().should().size() == 2
                && q.bool().should().stream().allMatch(inner -> inner.isTerm()
                && "orgTag".equals(inner.term().field()))));
    }

    private static List<String> keys(List<SearchResult> results) {
        return results.stream().map(result -> result.getFileMd5() + '#' + result.getChunkId()).toList();
    }

    private static SearchResult hit(String md5, int chunkId) {
        return new SearchResult(md5, chunkId, "text", 0.5, "user", "TECH", false);
    }

    /** 覆盖真实 ES 调用，验证并行、单路降级与超时语义 */
    private static final class FakeRecallClient extends HybridRecallClient {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private RuntimeException bm25Failure;
        private RuntimeException knnFailure;
        private long knnDelayMillis;

        private FakeRecallClient() {
            super(null);
        }

        @Override
        protected List<SearchResult> bm25Recall(String query, Query acl, int size) {
            calls.add("bm25");
            if (bm25Failure != null) {
                throw bm25Failure;
            }
            return List.of(hit("bm25", 1));
        }

        @Override
        protected List<SearchResult> knnRecall(String query, List<Float> queryVector, Query acl, int size) {
            calls.add("knn");
            if (knnDelayMillis > 0) {
                try {
                    Thread.sleep(knnDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (knnFailure != null) {
                throw knnFailure;
            }
            return List.of(hit("knn", 1));
        }
    }
}
