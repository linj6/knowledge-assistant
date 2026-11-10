package com.lnjecit.knowledge.assistant.retrieval;

import org.springframework.stereotype.Service;

@Service
public class PublishedVersionSnapshotService {
    private final PublishedVersionSnapshotRepository repository;

    public PublishedVersionSnapshotService(PublishedVersionSnapshotRepository repository) {
        this.repository = repository;
    }
    // 每个请求仅在入口读取一次；后续 PG 和 ES 共同使用 RetrievalContext 中的快照。
    public PublishedVersionSnapshot read(Long knowledgeBaseId) {
        return repository.read(knowledgeBaseId);
    }
}
