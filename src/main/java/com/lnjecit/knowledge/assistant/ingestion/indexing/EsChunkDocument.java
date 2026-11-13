package com.lnjecit.knowledge.assistant.ingestion.indexing;

import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;

public record EsChunkDocument(
        String chunk_id,
        String knowledge_base_id,
        String document_id,
        String index_version_id,
        int chunk_order,
        String content) {
    public static EsChunkDocument from(DocumentChunk c) {
        return new EsChunkDocument(
                c.getId().toString(),
                c.getKnowledgeBaseId().toString(),
                c.getDocumentId().toString(),
                c.getIndexVersionId().toString(),
                c.getChunkOrder(),
                c.getContent());
    }
}
