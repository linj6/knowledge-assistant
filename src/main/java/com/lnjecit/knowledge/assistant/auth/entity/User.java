package com.lnjecit.knowledge.assistant.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 用户持久化模型；不得作为公开响应或输出到日志。 */
@Getter
@Setter
@TableName("app_user")
public class User {
    /** 沿用项目雪花 ID 策略。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 唯一用户名。 */
    private String username;
    /** 仅保存密码散列。 */
    private String passwordHash;
    /** 启用或禁用状态。 */
    private UserStatus status;

    private Instant createdAt;
}
