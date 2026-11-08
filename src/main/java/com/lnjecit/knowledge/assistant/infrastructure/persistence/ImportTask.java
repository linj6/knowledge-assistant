package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** ImportTask 持久化记录；时间统一为 UTC 瞬时，不作为 API DTO。 */
@TableName("import_task")
public class ImportTask {
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

    /** 所属索引版本；跨记录归属由后续事务校验。 */
    private Long indexVersionId;

    public Long getIndexVersionId() {
        return indexVersionId;
    }

    public void setIndexVersionId(Long value) {
        this.indexVersionId = value;
    }

    private ImportTaskStatus status;

    public ImportTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ImportTaskStatus value) {
        this.status = value;
    }

    private Integer attemptCount;

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer value) {
        this.attemptCount = value;
    }

    /** 领取代际，由后续 Worker 原子递增并用于 fencing。 */
    private Long claimEpoch;

    public Long getClaimEpoch() {
        return claimEpoch;
    }

    public void setClaimEpoch(Long value) {
        this.claimEpoch = value;
    }

    private String leaseOwner;

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String value) {
        this.leaseOwner = value;
    }

    private Instant leaseUntil;

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant value) {
        this.leaseUntil = value;
    }

    private Instant nextRetryAt;

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant value) {
        this.nextRetryAt = value;
    }

    private String lastErrorCode;

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String value) {
        this.lastErrorCode = value;
    }

    private String lastErrorMessage;

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String value) {
        this.lastErrorMessage = value;
    }

    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        this.createdAt = value;
    }

    private Instant updatedAt;

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant value) {
        this.updatedAt = value;
    }
}
