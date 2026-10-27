package com.yizhaoqi.smartpai;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.yizhaoqi.smartpai.config.EsConfig;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.*;

class EsConfigLifecycleTest {
    @Test void closingApplicationContextStopsRealElasticsearchClientThreads() throws Exception {
        var context = new AnnotationConfigApplicationContext();
        RestClient client = null;
        try {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "elasticsearch.host=127.0.0.1", "elasticsearch.port=1", "elasticsearch.scheme=http", "elasticsearch.username=");
            context.register(EsConfig.class);
            context.refresh();
            client = ((RestClientTransport) context.getBean(ElasticsearchClient.class)._transport()).restClient();
            assertTrue(client.isRunning());
            context.close();
            assertFalse(client.isRunning(), "Spring shutdown must stop ES threads so evaluation CLI can exit");
        } finally {
            context.close();
            if (client != null) client.close();
        }
    }
}
