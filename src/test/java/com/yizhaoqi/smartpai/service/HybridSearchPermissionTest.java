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
}
