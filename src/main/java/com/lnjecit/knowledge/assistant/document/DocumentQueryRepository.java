package com.lnjecit.knowledge.assistant.document;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentQueryRepository {
    private final DocumentQueryMapper mapper;

    public DocumentQueryRepository(DocumentQueryMapper mapper) {
        this.mapper = mapper;
    }

    public Document document(Long id) {
        return mapper.document(id);
    }

    public DocumentChunk chunk(Long id) {
        return mapper.chunk(id);
    }

    public long count(Long kbId) {
        return mapper.count(kbId);
    }

    public List<Document> page(Long kbId, long offset, int size) {
        return mapper.page(kbId, offset, size);
    }

    public List<IndexVersion> versions(List<Long> ids) {
        return ids.isEmpty() ? List.of() : mapper.versions(ids);
    }

    public List<ImportTask> tasks(List<Long> ids) {
        return ids.isEmpty() ? List.of() : mapper.tasks(ids);
    }
}
