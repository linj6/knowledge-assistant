package com.lnjecit.knowledge.assistant.document.repository;

import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.mapper.DocumentQueryMapper;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentQueryRepository {
    private final DocumentQueryMapper mapper;

    public DocumentQueryRepository(DocumentQueryMapper mapper) {
        this.mapper = mapper;
    }

    public List<DocumentChunk> chunks(Long kbId, Long id, Long versionId, int size, long offset) {
        return mapper.chunks(kbId, id, versionId, size, offset);
    }

    public Document document(Long id) {
        return mapper.document(id);
    }

    public DocumentChunk chunk(Long id) {
        return mapper.chunk(id);
    }

    public long count(Long kbId, String pattern, String status) {
        return mapper.count(kbId, pattern, status);
    }

    public List<Document> page(Long kbId, long offset, int size, String pattern, String status) {
        return mapper.page(kbId, offset, size, pattern, status);
    }

    public boolean lockActive(Long kbId, Long id) {
        return mapper.lockActive(kbId, id) != null;
    }

    public boolean hasActiveImport(Long id) {
        return mapper.hasActiveImport(id);
    }

    public boolean markDeleted(Long kbId, Long id) {
        return mapper.markDeleted(kbId, id) == 1;
    }

    public List<IndexVersion> versions(List<Long> ids) {
        return ids.isEmpty() ? List.of() : mapper.versions(ids);
    }

    public List<ImportTask> tasks(List<Long> ids) {
        return ids.isEmpty() ? List.of() : mapper.tasks(ids);
    }
}
