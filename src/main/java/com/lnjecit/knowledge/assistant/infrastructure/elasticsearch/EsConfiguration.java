package com.lnjecit.knowledge.assistant.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import java.net.URI;
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
        RestClient client = RestClient.builder(validatedEndpoint(endpoint))
                .setRequestConfigCallback(b -> b.setConnectTimeout(5000)
                        .setConnectionRequestTimeout(5000)
                        .setSocketTimeout(30000))
                .build();
        // ES 8 客户端独立使用 Jackson 2，不替换 Web 层的 Jackson 3。
        return new RestClientTransport(client, new JacksonJsonpMapper());
    }

    public static HttpHost validatedEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            // Endpoint 只承载地址；禁止把鉴权、路径或查询串混入连接配置与错误消息。
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) throw new IllegalArgumentException();
            return HttpHost.create(uri.toString());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("ES endpoint invalid", failure);
        }
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
