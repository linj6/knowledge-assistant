package com.lnjecit.knowledge.assistant.document;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.ingestion.upload.ImportTaskResponse;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentQueryService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentQueryService.class);
    private final DocumentQueryRepository repository;
    private final KnowledgeBaseService knowledgeBases;

    public DocumentQueryService(DocumentQueryRepository repository, KnowledgeBaseService knowledgeBases) {
        this.repository = repository;
        this.knowledgeBases = knowledgeBases;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DocumentListResponse list(Long kbId, int page, int pageSize) {
        if (kbId == null || kbId <= 0 || page < 1 || pageSize < 1 || pageSize > 100)
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        knowledgeBases.get(kbId);
        // 同一只读快照内完成计数、分页和批量关联，避免 N+1 及关联身份漂移。
        long total = repository.count(kbId);
        List<Document> documents = repository.page(kbId, ((long) page - 1) * pageSize, pageSize);
        Map<Long, IndexVersion> versions = repository
                .versions(documents.stream()
                        .map(Document::getActiveVersionId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(IndexVersion::getId, Function.identity()));
        Map<Long, ImportTask> tasks = repository
                .tasks(documents.stream().map(Document::getId).toList())
                .stream()
                .collect(Collectors.toMap(ImportTask::getDocumentId, Function.identity()));
        List<DocumentSummaryResponse> items = documents.stream()
                .map(d -> {
                    IndexVersion version = versions.get(d.getActiveVersionId());
                    // activeVersionId 是发布事实；不能仅凭版本状态或任务成功决定可见性。
                    if (d.getActiveVersionId() != null) {
                        if (version == null || !Objects.equals(d.getId(), version.getDocumentId()))
                            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
                        if (version.getStatus() != IndexVersionStatus.PUBLISHED || version.getPublishedAt() == null)
                            LOG.warn("PUBLICATION_METADATA_INCONSISTENT");
                    }
                    ImportTask task = tasks.get(d.getId());
                    return new DocumentSummaryResponse(
                            d.getId(),
                            d.getFilename(),
                            d.getCreatedAt(),
                            d.getActiveVersionId() == null ? "UNPUBLISHED" : "PUBLISHED",
                            d.getActiveVersionId(),
                            version == null ? null : version.getPublishedAt(),
                            task == null ? null : ImportTaskResponse.from(task));
                })
                .toList();
        return new DocumentListResponse(items, page, pageSize, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CitationEvidenceResponse evidence(Long kbId, Long documentId, Long versionId, Long chunkId) {
        if (kbId == null
                || kbId <= 0
                || documentId == null
                || documentId <= 0
                || versionId == null
                || versionId <= 0
                || chunkId == null
                || chunkId <= 0) throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        knowledgeBases.get(kbId);
        Document d = repository.document(documentId);
        if (d == null || !Objects.equals(kbId, d.getKnowledgeBaseId()))
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        // 权威指针损坏返回内部错误；不能通过状态或未命中分支隐藏。
        if (d.getActiveVersionId() == null) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        List<IndexVersion> versions = repository.versions(List.of(d.getActiveVersionId()));
        if (versions.isEmpty()
                || !Objects.equals(documentId, versions.getFirst().getDocumentId()))
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        IndexVersion active = versions.getFirst();
        if (active.getStatus() != IndexVersionStatus.PUBLISHED || active.getPublishedAt() == null)
            LOG.warn("PUBLICATION_METADATA_INCONSISTENT");
        // 只允许当前活动版本，发布元数据不额外限制 M2 可见性。
        if (!Objects.equals(versionId, active.getId())) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk c = repository.chunk(chunkId);
        // 必须校验 Chunk 的知识库、文档、版本全部归属，防止仅凭 ID 读取正文。
        if (c == null
                || !Objects.equals(kbId, c.getKnowledgeBaseId())
                || !Objects.equals(documentId, c.getDocumentId())
                || !Objects.equals(versionId, c.getIndexVersionId()))
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        if (c.getSourceStart() == null
                || c.getSourceEnd() == null
                || c.getSourceStart() < 0
                || c.getSourceEnd() <= c.getSourceStart()) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        return new CitationEvidenceResponse(
                c.getId(),
                documentId,
                versionId,
                d.getFilename(),
                c.getHeadingPath(),
                c.getContent(),
                c.getSourceStart(),
                c.getSourceEnd());
    }
}
