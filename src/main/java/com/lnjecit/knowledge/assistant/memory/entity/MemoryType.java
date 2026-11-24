package com.lnjecit.knowledge.assistant.memory.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum MemoryType {
    /** 用户的职业、专业或经验等背景信息。 */
    BACKGROUND(true),

    /** 用户希望掌握的知识、技能或达成的学习目标。 */
    LEARNING_GOAL(false),

    /** 用户对回答语言、格式或详细程度的偏好。 */
    ANSWER_PREFERENCE(true),

    /** 用户对编程语言、框架或工具的偏好。 */
    TECH_PREFERENCE(false),

    /** 用户当前的学习进度及对相关知识的掌握情况。 */
    LEARNING_STATUS(false),

    /** 无法归入上述类型的其他记忆。 */
    OTHER(false);

    @EnumValue
    private final String value = name();

    private final boolean global;

    MemoryType(boolean global) {
        this.global = global;
    }

    /** 全局记忆无需匹配问题关键词即可进入召回候选。 */
    public boolean isGlobal() {
        return global;
    }
}
