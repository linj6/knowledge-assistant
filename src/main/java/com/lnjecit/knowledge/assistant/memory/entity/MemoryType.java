package com.lnjecit.knowledge.assistant.memory.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum MemoryType {
    BACKGROUND,
    LEARNING_GOAL,
    ANSWER_PREFERENCE,
    TECH_PREFERENCE,
    LEARNING_STATUS,
    OTHER;

    @EnumValue
    private final String value = name();
}
