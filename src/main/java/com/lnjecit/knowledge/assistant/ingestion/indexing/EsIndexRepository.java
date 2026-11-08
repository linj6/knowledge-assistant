package com.lnjecit.knowledge.assistant.ingestion.indexing;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import jakarta.json.JsonException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "assistant.elasticsearch.enabled", havingValue = "true")
public class EsIndexRepository {
    private final ElasticsearchClient client;

    public EsIndexRepository(ElasticsearchClient client) {
        this.client = client;
    }

    public void ensureIndex(String index) {
        try {
            if (!client.indices().exists(r -> r.index(index)).value()) {
                try {
                    client.indices().create(r -> r.index(index)
                            .settings(s -> s.numberOfReplicas("0"))
                            .mappings(m -> m.properties("chunk_id", p -> p.keyword(k -> k))
                                    .properties("knowledge_base_id", p -> p.keyword(k -> k))
                                    .properties("document_id", p -> p.keyword(k -> k))
                                    .properties("index_version_id", p -> p.keyword(k -> k))
                                    .properties("chunk_order", p -> p.integer(v -> v))
                                    .properties(
                                            "content",
                                            p -> p.text(t -> t.analyzer("ik_max_word")
                                                    .searchAnalyzer("ik_smart")
                                                    .similarity("BM25")))));
                } catch (ElasticsearchException e) {
                    // 并发创建只容许资源已存在，仍须读取并检查实际映射。
                    if (!"resource_already_exists_exception".equals(e.error().type())) throw e;
                }
            }
            Map<String, Property> fields = client.indices()
                    .getMapping(r -> r.index(index))
                    .result()
                    .get(index)
                    .mappings()
                    .properties();
            for (String name : List.of("chunk_id", "knowledge_base_id", "document_id", "index_version_id"))
                if (fields.get(name) == null
                        || !fields.get(name).isKeyword()
                        || Boolean.FALSE.equals(fields.get(name).keyword().index()))
                    throw failure("ES_MAPPING_INCOMPATIBLE");
            Property order = fields.get("chunk_order"), content = fields.get("content");
            if (order == null
                    || !order.isInteger()
                    || content == null
                    || !content.isText()
                    || Boolean.FALSE.equals(content.text().index())
                    || !"ik_max_word".equals(content.text().analyzer())
                    || !"ik_smart".equals(content.text().searchAnalyzer())
                    || !"BM25".equals(content.text().similarity())) throw failure("ES_MAPPING_INCOMPATIBLE");
        } catch (JsonException e) {
            throw failure("ES_PROTOCOL_INVALID");
        } catch (IOException e) {
            throw transportFailure(e);
        } catch (ElasticsearchException e) {
            throw failure("ES_INDEX_REQUEST_REJECTED");
        }
    }

    public void create(String index, List<EsChunkDocument> documents) {
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (EsChunkDocument document : documents)
            request.operations(
                    o -> o.create(c -> c.index(index).id(document.chunk_id()).document(document)));
        try {
            BulkResponse response = client.bulk(request.build());
            if (response == null || response.items() == null || response.items().size() != documents.size())
                throw failure("ES_BULK_PROTOCOL_INVALID");
            boolean rejected = false;
            for (int i = 0; i < documents.size(); i++) {
                BulkResponseItem item = response.items().get(i);
                EsChunkDocument expected = documents.get(i);
                if (item == null
                        || item.operationType() != OperationType.Create
                        || !expected.chunk_id().equals(item.id())
                        || !index.equals(item.index())) throw failure("ES_BULK_PROTOCOL_INVALID");
                if (item.status() == 409) {
                    GetResponse<EsChunkDocument> existing =
                            client.get(r -> r.index(index).id(expected.chunk_id()), EsChunkDocument.class);
                    // 比较全部索引业务字段，禁止只凭 ID 将冲突视为成功。
                    if (!existing.found() || !expected.equals(existing.source())) rejected = true;
                } else if (item.status() != 201 || item.error() != null) rejected = true;
            }
            if (rejected) throw failure("ES_BULK_ITEM_REJECTED");
        } catch (JsonException e) {
            throw failure("ES_PROTOCOL_INVALID");
        } catch (IOException e) {
            throw transportFailure(e);
        } catch (ElasticsearchException e) {
            throw failure("ES_BULK_REQUEST_REJECTED");
        }
    }

    private static IllegalStateException transportFailure(IOException e) {
        // 超时或断连时结果可能已写入；保留服务端数据，调用方用稳定 ID 重试。
        return failure(
                e instanceof SocketTimeoutException ? "ES_TIMEOUT_OUTCOME_UNKNOWN" : "ES_CONNECTION_OUTCOME_UNKNOWN");
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
