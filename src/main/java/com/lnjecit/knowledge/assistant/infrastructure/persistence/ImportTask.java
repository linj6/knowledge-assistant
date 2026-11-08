package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化导入任务，保存重试和租约诊断信息；领取、fencing 与状态流转由后续 Worker 实现。 */
@Getter
@Setter
@TableName("import_task")
public class ImportTask {
    /** 服务端生成的 Snowflake 主键，数据库不自增。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属文档标识；关联存在性与版本归属由业务事务校验。 */
    private Long documentId;
    /** 所属索引版本标识；必须与任务或 Chunk 的文档归属一致，由业务事务校验。 */
    private Long indexVersionId;
    /** 导入编排状态；数据库仅限制合法取值，合法状态转换由业务事务保证。 */
    private ImportTaskStatus status;
    /** 任务执行尝试次数，初始为零；领取与重试时的递增由后续 Worker 实现。 */
    private Integer attemptCount;
    /** 领取代际，初始为零；后续领取事务须单调递增并用于条件更新校验，仅保留字段不代表 fencing 已实现。 */
    private Long claimEpoch;
    /** 当前领取执行者标识，未领取时允许为空。 */
    private String leaseOwner;
    /** 租约到期时刻；到期不意味着旧 Worker 已停止，写入还需互斥和代际校验。 */
    private Instant leaseUntil;
    /** 下次允许重试的时刻，未安排重试时允许为空。 */
    private Instant nextRetryAt;
    /** 最近失败的脱敏错误分类码，未失败时允许为空。 */
    private String lastErrorCode;
    /** 最近失败的脱敏诊断信息，不得保存密钥或供应商敏感原文。 */
    private String lastErrorMessage;
    /** 记录创建时刻，使用 Instant 表达 UTC 时间线上的瞬时。 */
    private Instant createdAt;
    /** 记录最后更新时刻，由后续业务更新显式维护，不由数据库自动刷新。 */
    private Instant updatedAt;
}
