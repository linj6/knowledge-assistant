package com.lnjecit.knowledge.assistant.retrieval;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record PublishedVersionSnapshot(Long knowledgeBaseId, List<PublishedDocument> documents, String snapshotHash) {
    public PublishedVersionSnapshot {
        Objects.requireNonNull(knowledgeBaseId);
        documents = documents.stream()
                .sorted(Comparator.comparing(PublishedDocument::documentId))
                .toList();
        if (documents.stream().map(PublishedDocument::documentId).distinct().count() != documents.size())
            throw new IllegalArgumentException("SNAPSHOT_DUPLICATE_DOCUMENT");
        String computed = hash(knowledgeBaseId, documents);
        if (!computed.equals(snapshotHash)) throw new IllegalArgumentException("SNAPSHOT_HASH_INVALID");
    }

    public static PublishedVersionSnapshot of(Long kb, List<PublishedDocument> documents) {
        List<PublishedDocument> ordered = documents.stream()
                .sorted(Comparator.comparing(PublishedDocument::documentId))
                .toList();
        return new PublishedVersionSnapshot(kb, ordered, hash(kb, ordered));
    }

    private static String hash(Long kb, List<PublishedDocument> documents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 固定大端编码：KB、文档数、按文档 ID 排序的文档和版本，避免字符串拼接歧义。
            digest.update(ByteBuffer.allocate(16)
                    .putLong(kb)
                    .putLong(documents.size())
                    .array());
            for (PublishedDocument d : documents)
                digest.update(ByteBuffer.allocate(16)
                        .putLong(d.documentId())
                        .putLong(d.indexVersionId())
                        .array());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    public List<Long> versionIdsForTerms() {
        return PublishedVersionTerms.validate(
                documents.stream().map(PublishedDocument::indexVersionId).toList());
    }
}
