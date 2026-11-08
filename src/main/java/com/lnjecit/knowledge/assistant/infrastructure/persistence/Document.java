package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Document 持久化记录；时间统一为 UTC 瞬时，不作为 API DTO。 */
@TableName("document")
public class Document {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        this.id = value;
    }

    /** 所属知识库；无外键，写入事务必须校验归属。 */
    private Long knowledgeBaseId;

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long value) {
        this.knowledgeBaseId = value;
    }

    private String filename;

    public String getFilename() {
        return filename;
    }

    public void setFilename(String value) {
        this.filename = value;
    }

    private String contentHash;

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String value) {
        this.contentHash = value;
    }

    private String objectKey;

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String value) {
        this.objectKey = value;
    }

    /** 首次发布前为空；后续发布事务校验版本归属并仅写入一次。 */
    private Long activeVersionId;

    public Long getActiveVersionId() {
        return activeVersionId;
    }

    public void setActiveVersionId(Long value) {
        this.activeVersionId = value;
    }

    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        this.createdAt = value;
    }
}
