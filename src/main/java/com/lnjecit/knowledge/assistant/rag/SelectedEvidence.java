package com.lnjecit.knowledge.assistant.rag;

/** 只保留本次实际入选的证据与权威来源，不包含检索内部评分。 */
public record SelectedEvidence(
        String evidenceId,
        Long chunkId,
        Long knowledgeBaseId,
        Long documentId,
        Long indexVersionId,
        String content,
        int sourceStart,
        int sourceEnd,
        String headingPath,
        Integer finalRank) {}
