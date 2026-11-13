package com.lnjecit.knowledge.assistant.retrieval.rerank;

import java.net.URI;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("assistant.rerank")
public class RerankProperties implements InitializingBean {
    private boolean enabled;
    private String endpoint;
    private String model = "qwen3-rerank";
    private String apiKey;
    private int timeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String value) {
        endpoint = value;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String value) {
        model = value;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String value) {
        apiKey = value;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int value) {
        timeoutSeconds = value;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) return;
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPath().isBlank()
                    || !"qwen3-rerank".equals(model)
                    || apiKey == null
                    || apiKey.isBlank()
                    || apiKey.chars().anyMatch(c -> c < 32 || c == 127)
                    || timeoutSeconds < 1
                    || timeoutSeconds > 300) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid assistant.rerank configuration", ex);
        }
    }
}
