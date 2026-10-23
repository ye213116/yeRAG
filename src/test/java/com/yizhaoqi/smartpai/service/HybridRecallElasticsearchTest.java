package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.yizhaoqi.smartpai.entity.EsDocument;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-server test. Creates/deletes only a random index owned by this test. */
@EnabledIfEnvironmentVariable(named="RAG_ES_TEST_URL",matches=".+")
class HybridRecallElasticsearchTest {
    @Test void inaccessibleHighestSimilarityDocumentCannotConsumeAuthorizedWindow() throws Exception {
        String index="yerag-acl-test-"+UUID.randomUUID().toString().replace("-","");
        var builder=RestClient.builder(HttpHost.create(System.getenv("RAG_ES_TEST_URL")));
        String username=System.getenv("RAG_ES_TEST_USER"),password=System.getenv("RAG_ES_TEST_PASSWORD");
        if(username!=null && password!=null) {
            var credentials=new org.apache.http.impl.client.BasicCredentialsProvider();
            credentials.setCredentials(org.apache.http.auth.AuthScope.ANY,new org.apache.http.auth.UsernamePasswordCredentials(username,password));
            builder.setHttpClientConfigCallback(client -> client.setDefaultCredentialsProvider(credentials));
        }
        try(var transport=new RestClientTransport(builder.build(),new JacksonJsonpMapper())) {
            var es=new ElasticsearchClient(transport); boolean created=false;
            HybridRecallClient recall=new HybridRecallClient(es) { @Override protected String indexName(){return index;} };
            try {
                es.indices().create(c -> c.index(index).mappings(m -> m
                    .properties("textContent",p -> p.text(t -> t))
                    .properties("userId",p -> p.keyword(k -> k)).properties("orgTag",p -> p.keyword(k -> k))
                    .properties("public",p -> p.boolean_(b -> b))
                    .properties("vector",p -> p.denseVector(v -> v.dims(2).index(true).similarity("cosine")))));
                created=true;
                List<EsDocument> documents=List.of(
                    new EsDocument("private","private",0,"policy policy policy",new float[]{1,0},"fixture","2","OTHER",false),
                    new EsDocument("own","own",0,"policy",new float[]{0.99f,0.1f},"fixture","1","OTHER",false),
                    new EsDocument("org","org",0,"policy",new float[]{0.98f,0.2f},"fixture","2","TECH",false),
                    new EsDocument("public","public",0,"policy",new float[]{0.97f,0.3f},"fixture","3","OTHER",true));
                for(EsDocument document:documents) es.index(i -> i.index(index).id(document.getId()).document(document).refresh(Refresh.True));
                var acl=HybridRecallClient.aclQuery("1",List.of("TECH"));
                for(String route:List.of("bm25","vector")) {
                    var result=recall.recallDetailed(route,"policy",List.of(1f,0f),acl,3,Duration.ofSeconds(10),
                        hit -> "1".equals(hit.getUserId()) || Boolean.TRUE.equals(hit.getIsPublic()) || "TECH".equals(hit.getOrgTag())).block();
                    assertEquals(com.yizhaoqi.smartpai.rag.model.RagTypes.Status.SUCCEEDED,result.status());
                    assertEquals(Set.of("own","org","public"),result.candidates().stream().map(c -> c.fileMd5()).collect(java.util.stream.Collectors.toSet()));
                }
            } finally {
                recall.shutdown(); if(created) es.indices().delete(d -> d.index(index));
            }
        }
    }
}
