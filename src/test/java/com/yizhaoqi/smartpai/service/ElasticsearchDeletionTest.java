package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ElasticsearchDeletionTest {
    @Test @SuppressWarnings("unchecked") void ownerAndMd5AreBothRequiredAndPartialFailurePropagates() throws Exception {
        var client=mock(ElasticsearchClient.class); var response=mock(DeleteByQueryResponse.class);
        when(response.failures()).thenReturn(List.of());
        when(client.deleteByQuery(any(Function.class))).thenAnswer(call -> {
            Function<DeleteByQueryRequest.Builder,ObjectBuilder<DeleteByQueryRequest>> factory=call.getArgument(0);
            var request=factory.apply(new DeleteByQueryRequest.Builder()).build();
            assertTrue(request.refresh());
            assertEquals(2,request.query().bool().filter().size());
            assertEquals("fileMd5",request.query().bool().filter().get(0).term().field());
            assertEquals("fixture-md5",request.query().bool().filter().get(0).term().value().stringValue());
            assertEquals("userId",request.query().bool().filter().get(1).term().field());
            assertEquals("17",request.query().bool().filter().get(1).term().value().stringValue());
            return response;
        });
        var service=new ElasticsearchService(); ReflectionTestUtils.setField(service,"esClient",client);
        service.deleteByFileMd5AndUserId("fixture-md5","17");
        when(response.timedOut()).thenReturn(true);
        assertThrows(RuntimeException.class,() -> service.deleteByFileMd5AndUserId("fixture-md5","17"));
    }
}
