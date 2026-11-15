package com.lnjecit.knowledge.assistant.auth.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 用户可用状态，数据库同步限制合法取值。 */
public enum UserStatus {
    ACTIVE,
    DISABLED;

    @EnumValue
    private final String value = name();
}
