package com.lnjecit.knowledge.assistant.ingestion.verification;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.json.JsonData;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkFields;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 只验证传入快照，不读取 PG，不修复 ES，也不转换业务状态。 */
@Service
@ConditionalOnProperty(name = "assistant.elasticsearch.enabled", havingValue = "true")
public class EsIntegrityService {
    public static final int MAX_CHUNKS = 10000;
    private final ElasticsearchClient client;
    private final EsIndexRepository indexRepository;

    public EsIntegrityService(ElasticsearchClient client, EsIndexRepository indexRepository) {
        this.client = client;
        this.indexRepository = indexRepository;
    }

    public record Result(int expectedChunkCount, int esChunkCount, int searchableSampleCount) {}

    public Result verify(
            Long knowledgeBaseId, Long documentId, Long indexVersionId, List<EsChunkDocument> expectedChunks) {
        return verifyIndex(EsIndexService.INDEX, knowledgeBaseId, documentId, indexVersionId, expectedChunks);
    }

    // 显式索引入口供随机隔离索引测试使用；业务默认索引复用 M1-07 常量。
    public Result verifyIndex(
            String indexName,
            Long knowledgeBaseId,
            Long documentId,
            Long indexVersionId,
            List<EsChunkDocument> expectedChunks) {
        try {
            Map<String, EsChunkDocument> expectedChunksById =
                    validateExpectedChunks(knowledgeBaseId, documentId, indexVersionId, expectedChunks);
            return refreshAndVerifySnapshot(indexName, knowledgeBaseId, documentId, indexVersionId, expectedChunksById);
        } catch (IOException failure) {
            if (failure instanceof java.net.SocketTimeoutException
                    || failure instanceof java.net.ConnectException
                    || failure instanceof java.net.UnknownHostException
                    || failure instanceof java.net.SocketException)
                throw new IllegalStateException("ES_CONNECTION_OUTCOME_UNKNOWN");
            throw new IllegalStateException("ES_VERIFICATION_FAILED");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("ES_VERIFICATION_FAILED");
        }
    }

    private Result refreshAndVerifySnapshot(
            String indexName,
            Long knowledgeBaseId,
            Long documentId,
            Long indexVersionId,
            Map<String, EsChunkDocument> expectedChunksById)
            throws IOException {
        String scrollId = null;
        try {
            // 缺少索引直接失败，不能因调用现有 ensureIndex 而创建一个空索引。
            if (!client.indices().exists(r -> r.index(indexName)).value()) fail();
            indexRepository.validateIndex(indexName);
            if (client.indices()
                            .refresh(r -> r.index(indexName))
                            .shards()
                            .failed()
                            .intValue()
                    != 0) fail();
            Query ownershipFilter = buildOwnershipFilter(knowledgeBaseId, documentId, indexVersionId);
            SearchResponse<JsonData> first = client.search(
                    r -> r.index(indexName)
                            .size(100)
                            .scroll(t -> t.time("1m"))
                            .sort(s -> s.field(f -> f.field("_doc")))
                            .allowPartialSearchResults(false)
                            .query(ownershipFilter),
                    JsonData.class);
            scrollId = first.scrollId();
            if (first.timedOut() || first.shards().failed().intValue() != 0 || scrollId == null) fail();
            List<Hit<JsonData>> page = first.hits().hits();
            Set<String> actualChunkIds = new HashSet<>();
            // Scroll 固定读取快照，始终读到空页；硬上限同时限制内存和请求次数。
            for (int pages = 0; ; pages++) {
                if (pages > MAX_CHUNKS / 100 + 1) fail();
                comparePage(page, expectedChunksById, actualChunkIds);
                if (page.isEmpty()) break;
                String cursor = scrollId;
                ScrollResponse<JsonData> next =
                        client.scroll(r -> r.scrollId(cursor).scroll(t -> t.time("1m")), JsonData.class);
                if (next.scrollId() != null) scrollId = next.scrollId();
                if (next.timedOut() || next.shards().failed().intValue() != 0) fail();
                page = next.hits().hits();
            }
            if (!actualChunkIds.equals(expectedChunksById.keySet())) fail();
            int searchableSampleCount = verifySearchSamples(indexName, ownershipFilter, expectedChunksById);
            return new Result(expectedChunksById.size(), actualChunkIds.size(), searchableSampleCount);
        } finally {
            if (scrollId != null) {
                String cursor = scrollId;
                try {
                    if (!client.clearScroll(r -> r.scrollId(cursor)).succeeded()) fail();
                } catch (IOException | RuntimeException failure) {
                    throw new IllegalStateException("ES_VERIFICATION_FAILED");
                }
            }
        }
    }

    private int verifySearchSamples(
            String indexName, Query ownershipFilter, Map<String, EsChunkDocument> expectedChunksById)
            throws IOException {
        List<EsChunkDocument> orderedChunks = new ArrayList<>(expectedChunksById.values());
        orderedChunks.sort(
                Comparator.comparingInt(EsChunkDocument::chunk_order).thenComparing(EsChunkDocument::chunk_id));
        Set<Integer> samplePositions =
                new LinkedHashSet<>(List.of(0, orderedChunks.size() / 2, orderedChunks.size() - 1));
        for (int position : samplePositions) {
            EsChunkDocument sample = orderedChunks.get(position);
            List<AnalyzeToken> tokens = client.indices()
                    .analyze(r -> r.index(indexName).analyzer("ik_smart").text(sample.content()))
                    .tokens();
            if (tokens.isEmpty() || tokens.getFirst().token().isBlank()) fail();
            String term = tokens.getFirst().token();
            // 限定样本 ID 只消除排名干扰；命中仍必须满足真实 content match 和三项业务过滤。
            SearchResponse<JsonData> search = client.search(
                    r -> r.index(indexName)
                            .size(1)
                            .allowPartialSearchResults(false)
                            .query(q -> q.bool(b -> b.filter(ownershipFilter)
                                    .filter(f -> f.ids(i -> i.values(sample.chunk_id())))
                                    .must(m -> m.match(
                                            t -> t.field(EsChunkFields.CONTENT).query(term))))),
                    JsonData.class);
            if (search.timedOut()
                    || search.shards().failed().intValue() != 0
                    || search.hits().hits().stream()
                            .noneMatch(h -> sample.chunk_id().equals(h.id()))) fail();
        }
        return samplePositions.size();
    }

    // 同时检查来源字段、稳定 ID 和完整六字段值，禁止仅凭数量判断成功。
    private static void comparePage(
            List<Hit<JsonData>> page, Map<String, EsChunkDocument> expectedChunksById, Set<String> actualChunkIds) {
        for (Hit<JsonData> hit : page) {
            if (hit.source() == null
                    || !hit.source().toJson().asJsonObject().keySet().containsAll(EsChunkFields.ALL_FIELDS)) fail();
            JsonObject source = hit.source().toJson().asJsonObject();
            for (String field : List.of(
                    EsChunkFields.CHUNK_ID,
                    EsChunkFields.KNOWLEDGE_BASE_ID,
                    EsChunkFields.DOCUMENT_ID,
                    EsChunkFields.INDEX_VERSION_ID,
                    EsChunkFields.CONTENT)) if (source.get(field).getValueType() != JsonValue.ValueType.STRING) fail();
            if (!(source.get(EsChunkFields.CHUNK_ORDER) instanceof JsonNumber order) || !order.isIntegral()) fail();
            EsChunkDocument actual = hit.source().to(EsChunkDocument.class);
            if (actual.chunk_id() == null
                    || !actual.chunk_id().equals(hit.id())
                    || !actualChunkIds.add(hit.id())
                    || actualChunkIds.size() > MAX_CHUNKS
                    || !actual.equals(expectedChunksById.get(hit.id()))) fail();
        }
    }

    private static Map<String, EsChunkDocument> validateExpectedChunks(
            Long knowledgeBaseId, Long documentId, Long indexVersionId, List<EsChunkDocument> expectedChunks) {
        if (knowledgeBaseId == null
                || documentId == null
                || indexVersionId == null
                || expectedChunks == null
                || expectedChunks.isEmpty()
                || expectedChunks.size() > MAX_CHUNKS) fail();
        Map<String, EsChunkDocument> expectedChunksById = new HashMap<>();
        for (EsChunkDocument expectedChunk : expectedChunks) {
            if (expectedChunk == null
                    || expectedChunk.chunk_id() == null
                    || !expectedChunk.chunk_id().matches("[1-9][0-9]*")
                    || !knowledgeBaseId.toString().equals(expectedChunk.knowledge_base_id())
                    || !documentId.toString().equals(expectedChunk.document_id())
                    || !indexVersionId.toString().equals(expectedChunk.index_version_id())
                    || expectedChunk.chunk_order() < 0
                    || expectedChunk.content() == null
                    || expectedChunk.content().isBlank()
                    || expectedChunksById.put(expectedChunk.chunk_id(), expectedChunk) != null) fail();
            Long.parseLong(expectedChunk.chunk_id());
        }
        return expectedChunksById;
    }

    private static Query buildOwnershipFilter(Long knowledgeBaseId, Long documentId, Long indexVersionId) {
        return Query.of(q -> q.bool(b -> b.filter(f ->
                        f.term(t -> t.field(EsChunkFields.KNOWLEDGE_BASE_ID).value(knowledgeBaseId.toString())))
                .filter(f -> f.term(t -> t.field(EsChunkFields.DOCUMENT_ID).value(documentId.toString())))
                .filter(f -> f.term(t -> t.field(EsChunkFields.INDEX_VERSION_ID).value(indexVersionId.toString())))));
    }

    private static void fail() {
        throw new IllegalStateException("ES_VERIFICATION_FAILED");
    }
}
