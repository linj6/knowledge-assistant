package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** IndexVersion 持久化记录；时间统一为 UTC 瞬时，不作为 API DTO。 */
@TableName("index_version")
public class IndexVersion {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        this.id = value;
    }

    /** 所属文档；写入事务必须校验关联。 */
    private Long documentId;

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long value) {
        this.documentId = value;
    }

    private IndexVersionStatus status;

    public IndexVersionStatus getStatus() {
        return status;
    }

    public void setStatus(IndexVersionStatus value) {
        this.status = value;
    }

    private Integer expectedChunkCount;

    public Integer getExpectedChunkCount() {
        return expectedChunkCount;
    }

    public void setExpectedChunkCount(Integer value) {
        this.expectedChunkCount = value;
    }

    private String embeddingModel;

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String value) {
        this.embeddingModel = value;
    }

    private Integer embeddingDimension;

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(Integer value) {
        this.embeddingDimension = value;
    }

    /** 创建时固化的切分配置，后续业务不得修改。 */
    private String chunkingConfig;

    public String getChunkingConfig() {
        return chunkingConfig;
    }

    public void setChunkingConfig(String value) {
        this.chunkingConfig = value;
    }

    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        this.createdAt = value;
    }

    private Instant publishedAt;

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant value) {
        this.publishedAt = value;
    }
}
