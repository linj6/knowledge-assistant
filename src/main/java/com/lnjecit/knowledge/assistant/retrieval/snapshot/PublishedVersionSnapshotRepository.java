package com.lnjecit.knowledge.assistant.retrieval.snapshot;

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
            if (!Objects.equals(kbId, row.knowledgeBaseId())) throw new SnapshotInvalidException();
            if (row.activeVersionId() == null) continue;
            if (row.documentId() == null
                    || !Objects.equals(row.activeVersionId(), row.versionId())
                    || !Objects.equals(row.documentId(), row.versionDocumentId())) throw new SnapshotInvalidException();
            documents.add(new PublishedDocument(row.documentId(), row.versionId()));
        }
        // 仅转换快照构造的数据校验异常，数据库读取异常保留原类型。
        try {
            return PublishedVersionSnapshot.of(kbId, documents);
        } catch (IllegalArgumentException invalid) {
            throw new SnapshotInvalidException();
        }
    }
}
