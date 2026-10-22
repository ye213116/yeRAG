package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.ObjectBuilder;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.RetrievalProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridSearchServiceRetrievalTest {

    @Mock ElasticsearchClient esClient;
    @Mock EmbeddingClient embeddingClient;
    @Mock HybridRecallClient recallClient;
    @Mock RerankClient rerankClient;
    @Mock UserRepository userRepository;
    @Mock OrgTagCacheService orgTagCacheService;
    @Mock FileUploadRepository fileUploadRepository;

    private RetrievalProperties retrieval;
    private HybridSearchService service;

    @BeforeEach
    void setUp() {
        retrieval = new RetrievalProperties();
        service = new HybridSearchService(esClient, embeddingClient, recallClient, rerankClient,
                userRepository, orgTagCacheService, fileUploadRepository, retrieval);

        User user = new User();
        user.setId(42L);
        user.setUsername("alice");

        lenient().when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        lenient().when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("TECH"));
        lenient().when(fileUploadRepository.findByFileMd5In(anyList())).thenReturn(List.of());
        lenient().when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        lenient().when(rerankClient.available()).thenReturn(true);
    }

    @Test
    void reranksTopCandidatesAndReturnsRerankOrderAndScores() {
        SearchResult a = hit("md5-a", 1);
        SearchResult b = hit("md5-b", 2);
        SearchResult c = hit("md5-c", 3);
        when(recallClient.recallBoth(eq("policy"), anyList(), any(Query.class), anyInt(), any()))
                .thenReturn(new HybridRecallClient.RecallResult(List.of(a, b), List.of(c)));
        when(rerankClient.rerank(eq("policy"), anyList(), eq(2)))
                .thenReturn(List.of(new RerankClient.RerankHit(1, 0.93), new RerankClient.RerankHit(2, 0.41)));

        List<SearchResult> results = service.searchWithPermission("policy", "42", 2);

        // RRF 顺序为 a, c, b；重排命中下标 1(c)、2(b)
        assertEquals(List.of("md5-c", "md5-b"), md5s(results));
        assertEquals(0.93, results.get(0).getScore(), 1e-12);
        assertEquals(0.41, results.get(1).getScore(), 1e-12);
        verify(recallClient).recallBoth(eq("policy"), anyList(), any(Query.class), eq(60), any());
    }

    @Test
    void fallsBackToRrfOrderWhenRerankFailsOrTimesOut() {
        SearchResult a = hit("md5-a", 1);
        SearchResult b = hit("md5-b", 2);
        SearchResult c = hit("md5-c", 3);
        when(recallClient.recallBoth(eq("policy"), anyList(), any(Query.class), anyInt(), any()))
                .thenReturn(new HybridRecallClient.RecallResult(List.of(a, b), List.of(c)));
        when(rerankClient.rerank(anyString(), anyList(), anyInt())).thenReturn(null);

        List<SearchResult> results = service.searchWithPermission("policy", "42", 2);

        assertEquals(List.of("md5-a", "md5-c"), md5s(results));
        assertEquals(1d / 61d, results.get(0).getScore(), 1e-12);
    }

    @Test
    void skipsRerankEntirelyWhenDisabledAndKeepsRrfOrder() {
        retrieval.getRerank().setEnabled(false);
        SearchResult a = hit("md5-a", 1);
        SearchResult b = hit("md5-b", 2);
        when(recallClient.recallBoth(eq("policy"), anyList(), any(Query.class), anyInt(), any()))
                .thenReturn(new HybridRecallClient.RecallResult(List.of(a), List.of(b)));

        List<SearchResult> results = service.searchWithPermission("policy", "42", 2);

        assertEquals(List.of("md5-a", "md5-b"), md5s(results));
        verify(rerankClient, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void skipsRerankWhenClientIsNotConfigured() {
        when(rerankClient.available()).thenReturn(false);
        SearchResult a = hit("md5-a", 1);
        when(recallClient.recallBoth(eq("policy"), anyList(), any(Query.class), anyInt(), any()))
                .thenReturn(new HybridRecallClient.RecallResult(List.of(a), List.of()));

        List<SearchResult> results = service.searchWithPermission("policy", "42", 2);

        assertEquals(List.of("md5-a"), md5s(results));
        verify(rerankClient, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void degradesToBm25RouteButStillReranksWhenEmbeddingFails() {
        when(embeddingClient.embed(anyList())).thenThrow(new IllegalStateException("embedding down"));
        SearchResult a = hit("md5-a", 1);
        SearchResult b = hit("md5-b", 2);
        when(recallClient.recallBm25(eq("policy"), any(Query.class), anyInt(), any())).thenReturn(List.of(a, b));
        when(rerankClient.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankClient.RerankHit(1, 0.88), new RerankClient.RerankHit(0, 0.11)));

        List<SearchResult> results = service.searchWithPermission("policy", "42", 2);

        assertEquals(List.of("md5-b", "md5-a"), md5s(results));
        verify(recallClient, never()).recallBoth(anyString(), anyList(), any(Query.class), anyInt(), any());
    }

    @Test
    void clampsTopKAndRecallsEnoughCandidatesForRerank() {
        List<SearchResult> hits = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            hits.add(hit("md5-" + i, i));
        }
        when(recallClient.recallBoth(eq("policy"), anyList(), any(Query.class), anyInt(), any()))
                .thenReturn(new HybridRecallClient.RecallResult(hits, List.of()));

        service.searchWithPermission("policy", "42", 999);

        // topK 收敛到 50，窗口 50 * 30 = 1500，送重排 50 条
        verify(recallClient).recallBoth(eq("policy"), anyList(), any(Query.class), eq(1500), any());
        verify(rerankClient).rerank(eq("policy"), org.mockito.ArgumentMatchers.argThat(docs -> docs.size() == 50), eq(50));
    }

    @Test
    void returnsEmptyWithoutCallingRerankWhenBothRoutesMiss() {
        when(recallClient.recallBoth(eq("policy"), anyList(), any(Query.class), anyInt(), any()))
                .thenReturn(new HybridRecallClient.RecallResult(List.of(), List.of()));

        List<SearchResult> results = service.searchWithPermission("policy", "42", 5);

        assertTrue(results.isEmpty());
        verify(rerankClient, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void passesAuthenticatedUserAclToBothRoutes() {
        when(recallClient.recallBoth(eq("policy"), anyList(), any(Query.class), anyInt(), any()))
                .thenReturn(new HybridRecallClient.RecallResult(List.of(), List.of()));

        service.searchWithPermission("policy", "42", 5);

        ArgumentCaptor<Query> aclCaptor = ArgumentCaptor.forClass(Query.class);
        verify(recallClient).recallBoth(eq("policy"), anyList(), aclCaptor.capture(), anyInt(), any());
        Query acl = aclCaptor.getValue();
        assertTrue(acl.isBool());
        assertTrue(acl.bool().should().stream().anyMatch(q -> q.isTerm()
                && "userId".equals(q.term().field()) && "42".equals(q.term().value().stringValue())));
        assertTrue(acl.bool().should().stream().anyMatch(q -> q.isTerm()
                && "orgTag".equals(q.term().field())));
    }

    @Test
    void legacyModeKeepsRollbackPathWorkingWithPrefilteredAclAndRescore() throws Exception {
        retrieval.setMode(RetrievalProperties.Mode.LEGACY);
        EsDocument document = new EsDocument("id-1", "md5-legacy", 1, "legacy text",
                new float[]{0.1f, 0.2f}, "deepseek-embed", "42", "TECH", false);
        SearchResponse<EsDocument> response = SearchResponse.of(builder -> builder
                .took(1)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(List.of(
                        Hit.of(hit -> hit.index(HybridRecallClient.INDEX).id("id-1").score(0.7d).source(document))))));
        when(esClient.search(org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder,
                ObjectBuilder<SearchRequest>>>any(), eq(EsDocument.class))).thenReturn(response);

        List<SearchResult> results = service.searchWithPermission("policy", "42", 5);

        assertEquals(List.of("md5-legacy"), md5s(results));
        assertEquals(0.7d, results.get(0).getScore(), 1e-12);

        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(esClient).search(captor.capture(), eq(EsDocument.class));
        SearchRequest request = captor.getValue().apply(new SearchRequest.Builder()).build();
        assertFalse(request.knn().isEmpty());
        assertNotNull(request.query());
        assertTrue(request.query().isBool());
        assertTrue(request.query().bool().filter().stream().anyMatch(q -> q.isBool()));
        assertNotNull(request.rescore());
        assertEquals(Integer.valueOf(5), request.size());
    }

    private static SearchResult hit(String md5, int chunkId) {
        return new SearchResult(md5, chunkId, "text-" + md5, 0.5, "42", "TECH", false, md5 + ".txt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentReadsUseTheSamePreFilterAclAndBoundedSortedChunkRangeForEachUser() throws Exception {
        User bob = new User(); bob.setId(84L); bob.setUsername("bob");
        when(userRepository.findById(84L)).thenReturn(Optional.of(bob));
        when(orgTagCacheService.getUserEffectiveOrgTags("bob")).thenReturn(List.of("FINANCE"));
        SearchResponse<EsDocument> empty = SearchResponse.of(r -> r.took(1).timedOut(false).shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of())));
        when(esClient.search(any(Function.class), eq(EsDocument.class))).thenReturn(empty);
        String md5 = "a".repeat(32);
        assertTrue(service.readDocumentWithPermission(md5, 7, 3, "42").isEmpty());
        assertTrue(service.readDocumentWithPermission(md5, 7, 3, "84").isEmpty());
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor = ArgumentCaptor.forClass(Function.class);
        org.mockito.Mockito.verify(esClient, org.mockito.Mockito.times(2)).search(captor.capture(), eq(EsDocument.class));
        List<SearchRequest> requests = captor.getAllValues().stream().map(f -> f.apply(new SearchRequest.Builder()).build()).toList();
        assertEquals(HybridRecallClient.aclQuery("42", List.of("TECH")).toString(), requests.get(0).query().bool().filter().get(0).toString());
        assertEquals(HybridRecallClient.aclQuery("84", List.of("FINANCE")).toString(), requests.get(1).query().bool().filter().get(0).toString());
        for (SearchRequest request : requests) {
            assertEquals(3, request.size());
            assertEquals(md5, request.query().bool().filter().get(1).term().value().stringValue());
            var range = request.query().bool().filter().get(2).range();
            assertEquals("chunkId", range.field()); assertEquals("7", range.gte().toString()); assertEquals("10", range.lt().toString());
            assertEquals("chunkId", request.sort().get(0).field().field());
            assertTrue(request.source().filter().excludes().contains("vector"));
        }
    }

    @Test
    void documentReadsRejectUnknownUsersAndOversizedRangesBeforeQueryingElasticsearch() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.readDocumentWithPermission("a".repeat(32), 0, 3, "999"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.readDocumentWithPermission("a".repeat(32), 0, 6, "42"));
        org.mockito.Mockito.verifyNoInteractions(esClient);
    }

    private static List<String> md5s(List<SearchResult> results) {
        return results.stream().map(SearchResult::getFileMd5).toList();
    }
}
