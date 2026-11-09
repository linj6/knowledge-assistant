package com.lnjecit.knowledge.assistant.ingestion.verification;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private final EsIndexRepository mapping;

    public EsIntegrityService(ElasticsearchClient client, EsIndexRepository mapping) {
        this.client = client;
        this.mapping = mapping;
    }

    public record Result(int expectedChunkCount, int esChunkCount, int searchableSampleCount) {}

    public Result verify(Long kb, Long document, Long version, List<EsChunkDocument> expected) {
        return verifyIndex(EsIndexService.INDEX, kb, document, version, expected);
    }

    // 显式索引入口供随机隔离索引测试使用；业务默认索引复用 M1-07 常量。
    public Result verifyIndex(String index, Long kb, Long document, Long version, List<EsChunkDocument> expected) {
        String scrollId = null;
        try {
            Map<String, EsChunkDocument> wanted = expected(kb, document, version, expected);
            // 缺少索引直接失败，不能因调用现有 ensureIndex 而创建一个空索引。
            if (!client.indices().exists(r -> r.index(index)).value()) fail();
            mapping.validateIndex(index);
            if (client.indices().refresh(r -> r.index(index)).shards().failed().intValue() != 0) fail();
            Query filter = filter(kb, document, version);
            SearchResponse<JsonData> first = client.search(
                    r -> r.index(index)
                            .size(100)
                            .scroll(t -> t.time("1m"))
                            .sort(s -> s.field(f -> f.field("_doc")))
                            .allowPartialSearchResults(false)
                            .query(filter),
                    JsonData.class);
            scrollId = first.scrollId();
            if (first.timedOut() || first.shards().failed().intValue() != 0 || scrollId == null) fail();
            List<Hit<JsonData>> page = first.hits().hits();
            Set<String> seen = new HashSet<>();
            // Scroll 固定读取快照，始终读到空页；硬上限同时限制内存和请求次数。
            for (int pages = 0; ; pages++) {
                if (pages > MAX_CHUNKS / 100 + 1) fail();
                for (Hit<JsonData> hit : page) {
                    if (hit.source() == null
                            || !hit.source()
                                    .toJson()
                                    .asJsonObject()
                                    .keySet()
                                    .containsAll(List.of(
                                            "chunk_id",
                                            "knowledge_base_id",
                                            "document_id",
                                            "index_version_id",
                                            "chunk_order",
                                            "content"))) fail();
                    jakarta.json.JsonObject source = hit.source().toJson().asJsonObject();
                    for (String field :
                            List.of("chunk_id", "knowledge_base_id", "document_id", "index_version_id", "content"))
                        if (source.get(field).getValueType() != jakarta.json.JsonValue.ValueType.STRING) fail();
                    if (!(source.get("chunk_order") instanceof jakarta.json.JsonNumber order) || !order.isIntegral())
                        fail();
                    EsChunkDocument actual = hit.source().to(EsChunkDocument.class);
                    if (actual.chunk_id() == null
                            || !actual.chunk_id().equals(hit.id())
                            || !seen.add(hit.id())
                            || seen.size() > MAX_CHUNKS
                            || !actual.equals(wanted.get(hit.id()))) fail();
                }
                if (page.isEmpty()) break;
                String cursor = scrollId;
                ScrollResponse<JsonData> next =
                        client.scroll(r -> r.scrollId(cursor).scroll(t -> t.time("1m")), JsonData.class);
                if (next.scrollId() != null) scrollId = next.scrollId();
                if (next.timedOut() || next.shards().failed().intValue() != 0) fail();
                page = next.hits().hits();
            }
            if (!seen.equals(wanted.keySet())) fail();
            List<EsChunkDocument> ordered = new ArrayList<>(wanted.values());
            ordered.sort(java.util.Comparator.comparingInt(EsChunkDocument::chunk_order)
                    .thenComparing(EsChunkDocument::chunk_id));
            Set<Integer> samples = new java.util.LinkedHashSet<>(List.of(0, ordered.size() / 2, ordered.size() - 1));
            for (int position : samples) {
                EsChunkDocument sample = ordered.get(position);
                List<co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken> tokens = client.indices()
                        .analyze(r -> r.index(index).analyzer("ik_smart").text(sample.content()))
                        .tokens();
                if (tokens.isEmpty() || tokens.getFirst().token().isBlank()) fail();
                String term = tokens.getFirst().token();
                // 限定样本 ID 只消除排名干扰；命中仍必须满足真实 content match 和三项业务过滤。
                SearchResponse<JsonData> search = client.search(
                        r -> r.index(index)
                                .size(1)
                                .allowPartialSearchResults(false)
                                .query(q -> q.bool(b -> b.filter(filter)
                                        .filter(f -> f.ids(i -> i.values(sample.chunk_id())))
                                        .must(m ->
                                                m.match(t -> t.field("content").query(term))))),
                        JsonData.class);
                if (search.timedOut()
                        || search.shards().failed().intValue() != 0
                        || search.hits().hits().stream()
                                .noneMatch(h -> sample.chunk_id().equals(h.id()))) fail();
            }
            return new Result(wanted.size(), seen.size(), samples.size());
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("ES_VERIFICATION_FAILED");
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

    private static Map<String, EsChunkDocument> expected(
            Long kb, Long document, Long version, List<EsChunkDocument> rows) {
        if (kb == null
                || document == null
                || version == null
                || rows == null
                || rows.isEmpty()
                || rows.size() > MAX_CHUNKS) fail();
        Map<String, EsChunkDocument> result = new HashMap<>();
        for (EsChunkDocument row : rows) {
            if (row == null
                    || row.chunk_id() == null
                    || !row.chunk_id().matches("[1-9][0-9]*")
                    || !kb.toString().equals(row.knowledge_base_id())
                    || !document.toString().equals(row.document_id())
                    || !version.toString().equals(row.index_version_id())
                    || row.chunk_order() < 0
                    || row.content() == null
                    || row.content().isBlank()
                    || result.put(row.chunk_id(), row) != null) fail();
            Long.parseLong(row.chunk_id());
        }
        return result;
    }

    private static Query filter(Long kb, Long document, Long version) {
        return Query.of(q -> q.bool(
                b -> b.filter(f -> f.term(t -> t.field("knowledge_base_id").value(kb.toString())))
                        .filter(f -> f.term(t -> t.field("document_id").value(document.toString())))
                        .filter(f -> f.term(t -> t.field("index_version_id").value(version.toString())))));
    }

    private static void fail() {
        throw new IllegalStateException("ES_VERIFICATION_FAILED");
    }
}
