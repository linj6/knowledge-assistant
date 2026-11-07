package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelResponseChecks;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

// IT 命名排除在默认 Surefire 执行之外；显式执行时缺少配置必须失败。
class ModelOnlineIT {
    @Test
    @Timeout(180)
    void chatAndEmbedding() {
        try {
            runSmoke();
        } catch (RuntimeException ex) {
            throw com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe(ex);
        }
    }

    private void runSmoke() {
        if (!"true".equals(System.getenv("ASSISTANT_MODEL_ENABLED"))) {
            throw new IllegalStateException("Explicit ASSISTANT_MODEL_ENABLED=true required");
        }
        var p = new ModelProperties();
        p.setEnabled(true);
        p.setApiKey(System.getenv("ASSISTANT_MODEL_API_KEY"));
        p.setChatModel(System.getenv("ASSISTANT_MODEL_CHAT_MODEL"));
        p.setEmbeddingModel(System.getenv("ASSISTANT_MODEL_EMBEDDING_MODEL"));
        if (System.getenv("ASSISTANT_MODEL_BASE_URL") != null) p.setBaseUrl(System.getenv("ASSISTANT_MODEL_BASE_URL"));
        if (System.getenv("ASSISTANT_MODEL_DIMENSIONS") != null)
            p.setDimensions(Integer.valueOf(System.getenv("ASSISTANT_MODEL_DIMENSIONS")));
        p.setTimeoutSeconds(20);
        var config = new ModelConfiguration();
        var chat = config.chatModel(p);
        var embedding = config.embeddingModel(p);
        long start = System.nanoTime();
        var plain = chat.call(new Prompt("只回答数字：1+1等于多少？"));
        assertThat(plain.getResult().getOutput().getText()).isNotBlank();
        System.out.printf(
                "chat model=%s elapsedMs=%d usage=%s%n",
                plain.getMetadata().getModel(),
                (System.nanoTime() - start) / 1_000_000,
                plain.getMetadata().getUsage());
        var format = new OpenAiChatModel.ResponseFormat();
        format.setType(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        // 三次独立原生 JSON mode 请求；通过不代表可靠性保证，也不等于 JSON Schema 支持。
        for (int i = 0; i < 3; i++) {
            var structured = chat.call(new Prompt(
                    "返回 JSON 对象：answer 为字符串测试答案，citations 为字符串数组 [E1]。",
                    OpenAiChatOptions.builder().responseFormat(format).build()));
            ModelResponseChecks.json(structured.getResult().getOutput().getText());
        }
        var texts = List.of("Spring Boot 使用依赖注入管理 Bean。", "Redis 可以用作缓存。", "PostgreSQL 支持 pgvector 扩展。");
        Integer dimensions = p.getDimensions();
        for (var inputs : List.of(texts.subList(0, 1), texts)) {
            start = System.nanoTime();
            var response = embedding.call(new EmbeddingRequest(inputs, null));
            dimensions = ModelResponseChecks.embeddings(response, inputs.size(), dimensions);
            System.out.printf(
                    "embedding count=%d dimensions=%d elapsedMs=%d usage=%s indices=%s%n",
                    inputs.size(),
                    dimensions,
                    (System.nanoTime() - start) / 1_000_000,
                    response.getMetadata().getUsage(),
                    response.getResults().stream()
                            .map(result -> result.getIndex())
                            .toList());
        }
    }
}
