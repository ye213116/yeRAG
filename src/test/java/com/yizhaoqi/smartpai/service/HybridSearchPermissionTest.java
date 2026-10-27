package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.util.ObjectBuilder;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridSearchPermissionTest {
    HybridSearchService service;
    HybridRecallClient recall;
    FileUploadRepository files=mock(FileUploadRepository.class);
    com.yizhaoqi.smartpai.config.RetrievalProperties config=new com.yizhaoqi.smartpai.config.RetrievalProperties();
    ElasticsearchClient es=mock(ElasticsearchClient.class);
    EmbeddingClient embedding=mock(EmbeddingClient.class);
    UserRepository users=mock(UserRepository.class);
    OrgTagCacheService tags=mock(OrgTagCacheService.class);
    List<SearchRequest> requests=Collections.synchronizedList(new ArrayList<>());
    List<EsDocument> documents=List.of();
    boolean vectorFails,allFail;
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        recall=new HybridRecallClient(es);
        service=new HybridSearchService(es,embedding,recall,mock(com.yizhaoqi.smartpai.client.RerankClient.class),users,tags,
            files,config);
        User user=new User(); user.setId(1L); user.setUsername("123");
        when(users.findByUsername("123")).thenReturn(Optional.of(user)); when(users.findById(1L)).thenReturn(Optional.of(user));
        when(tags.getUserEffectiveOrgTags("123")).thenReturn(List.of("department"));
        when(embedding.embed(anyList())).thenReturn(List.of(new float[]{1,0}));
        when(es.search(any(Function.class),eq(EsDocument.class))).thenAnswer(call -> {
            Function<SearchRequest.Builder,ObjectBuilder<SearchRequest>> fn=call.getArgument(0);
            SearchRequest request=fn.apply(new SearchRequest.Builder()).build(); requests.add(request);
            if(allFail || (vectorFails && !request.knn().isEmpty())) throw new java.io.IOException("unavailable");
            return SearchResponse.of(b -> b.took(1).timedOut(false).shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(documents.stream().map(d -> co.elastic.clients.elasticsearch.core.search.Hit.of(
                    v -> v.index("knowledge_base").id(d.getId()).score(1.0).source(d))).toList())));
        });
    }
    @AfterEach void cleanup() { recall.shutdown(); }
    EsDocument doc(String id,String owner,String org,boolean visible) { return new EsDocument(id,id,0,"body",new float[]{1,0},"test",owner,org,visible); }
    @Test void bothRoutesCarryIdenticalAclAndPublicOrgOwnerResultsSurvive() {
        documents=List.of(doc("own","1","other",false),doc("org","2","department",false),doc("public","3","other",true));
        var result=service.searchWithPermissionDetailed("body","name:123",20,60).block();
        assertEquals(Status.SUCCEEDED,result.status()); assertEquals(3,result.candidates().size()); assertEquals(2,requests.size());
        var vector=requests.stream().filter(r -> !r.knn().isEmpty()).findFirst().orElseThrow();
        var text=requests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        assertEquals(vector.knn().get(0).filter().get(0).toString(),text.query().bool().filter().get(0).toString());
        assertEquals("1",text.query().bool().filter().get(0).bool().minimumShouldMatch());
        verify(users,never()).findById(123L);
    }
    @Test void unauthorizedHitFailsClosedAndDoesNotDegradeToAnotherRoute() {
        documents=List.of(doc("private","2","other",false));
        assertThrows(SecurityException.class,() -> service.searchWithPermissionDetailed("body","name:123",5,60).block());
    }
    @Test void bm25BaselineDoesNotCallEmbeddingAndStillPreFiltersAclAndCorpus() {
        String md5="0123456789abcdef0123456789abcdef"; EsDocument own=doc("own","1","other",false); own.setFileMd5(md5); documents=List.of(own);
        var result=service.searchWithPermissionDetailed("body","id:1",150,60,List.of(md5),com.yizhaoqi.smartpai.rag.model.RagTypes.RecallStrategy.BM25).block();
        assertEquals(Status.SUCCEEDED,result.status()); assertEquals(1,requests.size()); assertTrue(requests.get(0).knn().isEmpty());
        assertTrue(requests.get(0).query().toString().contains(md5));
        assertTrue(requests.get(0).query().toString().contains("department")); verify(embedding,never()).embed(anyList());
    }
    @Test void vectorBaselineNeverSilentlyFallsBackToBm25() {
        vectorFails=true;
        var result=service.searchWithPermissionDetailed("body","id:1",150,60,List.of(),com.yizhaoqi.smartpai.rag.model.RagTypes.RecallStrategy.VECTOR).block();
        assertEquals(Status.FAILED,result.status()); assertEquals(1,requests.size()); assertFalse(requests.get(0).knn().isEmpty());
        requests.clear(); when(embedding.embed(anyList())).thenThrow(new RuntimeException("synthetic embedding outage"));
        assertEquals(Status.FAILED,service.searchWithPermissionDetailed("body","id:1",150,60,List.of(),com.yizhaoqi.smartpai.rag.model.RagTypes.RecallStrategy.VECTOR).block().status());
        assertTrue(requests.isEmpty());
    }
    @Test void singleRouteBaselineCannotWidenAcl() {
        documents=List.of(doc("private","2","other",false));
        for(var strategy:List.of(com.yizhaoqi.smartpai.rag.model.RagTypes.RecallStrategy.BM25,com.yizhaoqi.smartpai.rag.model.RagTypes.RecallStrategy.VECTOR))
            assertThrows(SecurityException.class,() -> service.searchWithPermissionDetailed("body","id:1",150,60,List.of(),strategy).block());
    }
    @Test void evaluationScopeIntersectsSameAclBeforeBothRoutesAndRejectsOutOfScopeHits() {
        String md5="0123456789abcdef0123456789abcdef";
        EsDocument own=doc("own","1","other",false); own.setFileMd5(md5); documents=List.of(own);
        assertEquals(Status.SUCCEEDED,service.searchWithPermissionDetailed("body","id:1",5,60,List.of(md5)).block().status());
        var text=requests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        var vector=requests.stream().filter(r -> !r.knn().isEmpty()).findFirst().orElseThrow();
        var filter=text.query().bool().filter().get(0);
        assertEquals(filter.toString(),vector.knn().get(0).filter().get(0).toString());
        assertEquals(HybridRecallClient.aclQuery("1",List.of("department")).toString(),filter.bool().filter().get(0).toString());
        assertTrue(filter.bool().filter().get(1).toString().contains(md5));
        own.setFileMd5("ffffffffffffffffffffffffffffffff");
        assertThrows(SecurityException.class,() -> service.searchWithPermissionDetailed("body","id:1",5,60,List.of(md5)).block());
        own.setFileMd5(md5); own.setUserId("2");
        assertThrows(SecurityException.class,() -> service.searchWithPermissionDetailed("body","id:1",5,60,List.of(md5)).block());
    }
    @Test void singleRouteFailureDegradesAndAllFailuresAreNotEmptySuccess() {
        vectorFails=true;
        assertEquals(Status.DEGRADED,service.searchWithPermissionDetailed("body","id:1",5,60).block().status());
        allFail=true;
        assertEquals(Status.FAILED,service.searchWithPermissionDetailed("body","id:1",5,60).block().status());
    }
    @Test void missingPermissionScopeNeverIssuesSearch() {
        when(tags.getUserEffectiveOrgTags("123")).thenReturn(null);
        assertThrows(SecurityException.class,() -> service.searchWithPermissionDetailed("body","name:123",5,60).block());
        assertTrue(requests.isEmpty());
    }
    @Test void legacyRollbackKeepsAclInKnnAndQuery() {
        config.setMode(com.yizhaoqi.smartpai.config.RetrievalProperties.Mode.LEGACY);
        documents=List.of(doc("own","1","other",false));
        assertEquals(Status.SUCCEEDED,service.searchWithPermissionDetailed("body","id:1",5,60).block().status());
        assertEquals(1,requests.size()); var request=requests.get(0);
        assertEquals(request.knn().get(0).filter().get(0).toString(),request.query().bool().filter().get(0).toString());
    }
    @Test void filenameBelongsToSameUploaderAsAuthorizedCandidate() {
        documents=List.of(doc("own","1","other",false));
        var other=new com.yizhaoqi.smartpai.model.FileUpload(); other.setUserId("2"); other.setFileMd5("own"); other.setFileName("private-title");
        var own=new com.yizhaoqi.smartpai.model.FileUpload(); own.setUserId("1"); own.setFileMd5("own"); own.setFileName("own-title");
        when(files.findByFileMd5In(anyList())).thenReturn(List.of(other,own));
        var result=service.searchWithPermissionDetailed("body","id:1",5,60).block();
        assertEquals("own-title",result.candidates().get(0).title());
        assertTrue(result.routes().stream().flatMap(r -> r.candidates().stream()).allMatch(c -> "own-title".equals(c.title())));
    }
    @Test @SuppressWarnings("unchecked") void corpusScanPaginatesWithSameAclAndPreservesPhysicalHitIdentity() throws Exception {
        String md5="0123456789abcdef0123456789abcdef";
        EsDocument d=doc("physical-id","1","other",false); d.setFileMd5(md5);
        when(es.search(any(Function.class),eq(EsDocument.class))).thenAnswer(call -> {
            Function<SearchRequest.Builder,ObjectBuilder<SearchRequest>> fn=call.getArgument(0); requests.add(fn.apply(new SearchRequest.Builder()).build());
            return SearchResponse.of(b -> b.took(1).timedOut(false).scrollId("test-cursor").shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(co.elastic.clients.elasticsearch.core.search.Hit.of(v -> v.index("knowledge_base").id("physical-id").source(d)))));
        });
        when(es.scroll(any(Function.class),eq(EsDocument.class))).thenReturn(ScrollResponse.of(b -> b.took(1).timedOut(false).scrollId("test-cursor")
            .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(List.of()))));
        var scan=service.scanCorpusWithPermission("id:1",List.of(md5));
        assertEquals(1,scan.chunks().size()); assertEquals("knowledge_base",scan.chunks().get(0).esIndex()); assertEquals("physical-id",scan.chunks().get(0).esHitId());
        assertEquals(HybridRecallClient.aclQuery("1",List.of("department")).toString(),requests.get(0).query().bool().filter().get(0).toString());
        assertTrue(requests.get(0).query().toString().contains(md5));
        verify(es).scroll(any(Function.class),eq(EsDocument.class)); verify(es).clearScroll(any(Function.class));
    }
    @Test @SuppressWarnings("unchecked") void corpusScanRejectsUnauthorizedOrIncompleteResponsesAndClosesCursor() throws Exception {
        String md5="0123456789abcdef0123456789abcdef";
        EsDocument hidden=doc("hidden","2","other",false); hidden.setFileMd5(md5);
        when(es.search(any(Function.class),eq(EsDocument.class))).thenReturn(SearchResponse.of(b -> b.took(1).timedOut(false).scrollId("cursor")
            .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(co.elastic.clients.elasticsearch.core.search.Hit.of(v -> v.index("knowledge_base").id("hidden").source(hidden))))));
        assertThrows(SecurityException.class,() -> service.scanCorpusWithPermission("id:1",List.of(md5)));
        verify(es).clearScroll(any(Function.class));
        when(es.search(any(Function.class),eq(EsDocument.class))).thenReturn(SearchResponse.of(b -> b.took(1).timedOut(true).scrollId("cursor")
            .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(List.of()))));
        assertThrows(IllegalStateException.class,() -> service.scanCorpusWithPermission("id:1",List.of(md5)));
    }
    @Test void corpusScanCannotProceedWithoutPermissionResolution() {
        when(tags.getUserEffectiveOrgTags("123")).thenReturn(null);
        assertThrows(SecurityException.class,() -> service.scanCorpusWithPermission("id:1",List.of("0123456789abcdef0123456789abcdef")));
        assertTrue(requests.isEmpty());
    }
}
