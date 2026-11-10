package com.lnjecit.knowledge.assistant.retrieval;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
public class PublishedVersionSnapshotRepository {
    private final PublishedVersionSnapshotMapper mapper;

    public PublishedVersionSnapshotRepository(PublishedVersionSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    public PublishedVersionSnapshot read(Long kbId) {
        List<PublishedVersionSnapshotMapper.Row> rows = mapper.read(kbId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        List<PublishedDocument> documents = new ArrayList<>();
        // LEFT JOIN 保留损坏指针；不能用内连接静默隐藏异常关系。
        for (PublishedVersionSnapshotMapper.Row row : rows) {
            if (!Objects.equals(kbId, row.knowledgeBaseId()))
                throw new IllegalStateException("SNAPSHOT_OWNERSHIP_INVALID");
            if (row.activeVersionId() == null) continue;
            if (row.documentId() == null
                    || !Objects.equals(row.activeVersionId(), row.versionId())
                    || !Objects.equals(row.documentId(), row.versionDocumentId()))
                throw new IllegalStateException("SNAPSHOT_OWNERSHIP_INVALID");
            documents.add(new PublishedDocument(row.documentId(), row.versionId()));
        }
        return PublishedVersionSnapshot.of(kbId, documents);
    }
}
