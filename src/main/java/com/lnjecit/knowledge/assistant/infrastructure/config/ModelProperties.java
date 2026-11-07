package com.lnjecit.knowledge.assistant.infrastructure.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("assistant.model")
public class ModelProperties implements InitializingBean {
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String chatModel;
    private String embeddingModel;
    private Integer dimensions;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String value) {
        baseUrl = value;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String value) {
        chatModel = value;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String value) {
        embeddingModel = value;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer value) {
        dimensions = value;
    }

    private boolean enabled;
    private String apiKey;
    private int timeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void afterPropertiesSet() {
        // 只报告字段规则，避免绑定校验器把被拒绝的密钥值写入启动日志。
        if (enabled && (apiKey == null || apiKey.isBlank()))
            throw new IllegalStateException("assistant.model.api-key is required when enabled");
        if (timeoutSeconds < 1 || timeoutSeconds > 300)
            throw new IllegalStateException("assistant.model.timeout-seconds must be between 1 and 300");
    }
}
