package com.lnjecit.knowledge.assistant.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "assistant.elasticsearch.enabled", havingValue = "true")
public class EsConfiguration {
    @Bean(destroyMethod = "close")
    public RestClientTransport esTransport(
            @Value("${assistant.elasticsearch.endpoint:http://localhost:9200}") String endpoint) {
        RestClient client = RestClient.builder(HttpHost.create(endpoint))
                .setRequestConfigCallback(b -> b.setConnectTimeout(5000).setSocketTimeout(30000))
                .build();
        // ES 8 客户端独立使用 Jackson 2，不替换 Web 层的 Jackson 3。
        return new RestClientTransport(client, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
