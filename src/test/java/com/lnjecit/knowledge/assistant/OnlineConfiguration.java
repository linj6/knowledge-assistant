package com.lnjecit.knowledge.assistant;

import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.infrastructure.config.RerankProperties;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

/** 仅加载 Boot 配置，不创建应用上下文或模型客户端。 */
final class OnlineConfiguration {
    private OnlineConfiguration() {}

    static StandardEnvironment load() {
        StandardEnvironment environment = new StandardEnvironment();
        return load(environment);
    }

    static StandardEnvironment load(StandardEnvironment environment) {
        try {
            ConfigDataEnvironmentPostProcessor.applyTo(environment);
            return environment;
        } catch (RuntimeException failure) {
            // 不传播可能携带私有配置值的绑定或解析异常。
            throw new IllegalStateException("Unable to load online configuration; check YAML and profile");
        }
    }

    static void authorize(StandardEnvironment environment, String prefix) {
        try {
            if (environment.getProperty(prefix + ".enabled", Boolean.class, false)
                    && environment.getProperty(prefix + ".online-authorized", Boolean.class, false)) return;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid online authorization configuration");
        }
        throw new IllegalStateException("Explicit online authorization required");
    }

    static ModelProperties baseline(StandardEnvironment environment) {
        authorize(environment, "assistant.model");
        if (!"true".equals(environment.getProperty("assistant.embedding.baseline-authorized"))) {
            throw new IllegalStateException("Explicit embedding baseline authorization required");
        }
        ModelProperties properties = model(environment);
        if (!EmbeddingBaselineOnlineIT.MODEL.equals(properties.getEmbeddingModel())
                || !Integer.valueOf(EmbeddingBaselineOnlineIT.DIMENSIONS).equals(properties.getDimensions())
                || !EmbeddingBaselineOnlineIT.ENDPOINT.equals(properties.getBaseUrl())) {
            throw new IllegalStateException("Embedding baseline requires fixed model, dimensions and endpoint");
        }
        properties.setTimeoutSeconds(20);
        return properties;
    }

    static ModelProperties model(StandardEnvironment environment) {
        authorize(environment, "assistant.model");
        ModelProperties properties;
        try {
            properties = Binder.get(environment)
                    .bind("assistant.model", ModelProperties.class)
                    .get();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid assistant.model configuration");
        }
        properties.afterPropertiesSet();
        return properties;
    }

    static RerankProperties rerank(StandardEnvironment environment) {
        authorize(environment, "assistant.rerank");
        RerankProperties properties;
        try {
            properties = Binder.get(environment)
                    .bind("assistant.rerank", RerankProperties.class)
                    .get();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid assistant.rerank configuration");
        }
        properties.afterPropertiesSet();
        return properties;
    }
}
