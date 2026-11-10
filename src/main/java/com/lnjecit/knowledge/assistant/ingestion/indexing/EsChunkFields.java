package com.lnjecit.knowledge.assistant.ingestion.indexing;

import java.util.Set;

/** ES 六字段协议的固定字段名，不改变 EsChunkDocument 的 JSON 序列化协议。 */
public final class EsChunkFields {
    public static final String CHUNK_ID = "chunk_id";
    public static final String KNOWLEDGE_BASE_ID = "knowledge_base_id";
    public static final String DOCUMENT_ID = "document_id";
    public static final String INDEX_VERSION_ID = "index_version_id";
    public static final String CHUNK_ORDER = "chunk_order";
    public static final String CONTENT = "content";

    public static final Set<String> ALL_FIELDS =
            Set.of(CHUNK_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, INDEX_VERSION_ID, CHUNK_ORDER, CONTENT);

    private EsChunkFields() {}
}
