package com.lnjecit.knowledge.assistant.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 知识库元数据，作为文档隔离边界；仅用于持久化，不作为 API DTO。 */
@Getter
@Setter
@TableName("knowledge_base")
public class KnowledgeBase {
    /** 服务端生成的 Snowflake 主键，数据库不自增。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 知识库展示名称。 */
    private String name;
    /** 知识库用途说明，允许为空。 */
    private String description;
    /** 所有者的应用用户标识，必填。 */
    private Long ownerUserId;
    /** 记录创建时刻，使用 Instant 表达 UTC 时间线上的瞬时。 */
    private Instant createdAt;
}
