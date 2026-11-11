package com.lnjecit.knowledge.assistant.rag.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Context 独立预算；问题、系统提示和输出空间由生成阶段另行预留。 */
@Configuration
@ConfigurationProperties("assistant.rag.context")
public class ContextProperties {
    private int estimatedTokenBudget = 4000;
    private int maxCharacters = 16000;

    public int getEstimatedTokenBudget() {
        return estimatedTokenBudget;
    }

    public void setEstimatedTokenBudget(int value) {
        if (value < 3) throw new IllegalArgumentException("CONTEXT_BUDGET_INVALID");
        estimatedTokenBudget = value;
    }

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int value) {
        if (value < 2) throw new IllegalArgumentException("CONTEXT_LENGTH_INVALID");
        maxCharacters = value;
    }
}
