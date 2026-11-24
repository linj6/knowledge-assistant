package com.lnjecit.knowledge.assistant.memory.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("assistant.memory.recall")
public class MemoryRecallProperties {
    private int maxMemories = 10;
    private int estimatedTokenBudget = 1000;

    public int getMaxMemories() {
        return maxMemories;
    }

    public void setMaxMemories(int maxMemories) {
        if (maxMemories < 1) throw new IllegalArgumentException("MEMORY_RECALL_LIMIT_INVALID");
        this.maxMemories = maxMemories;
    }

    public int getEstimatedTokenBudget() {
        return estimatedTokenBudget;
    }

    public void setEstimatedTokenBudget(int estimatedTokenBudget) {
        if (estimatedTokenBudget < 1) throw new IllegalArgumentException("MEMORY_RECALL_BUDGET_INVALID");
        this.estimatedTokenBudget = estimatedTokenBudget;
    }
}
