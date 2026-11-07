package com.lnjecit.knowledge.assistant;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelResponseChecks;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

/** 独立基线实验；默认构建不执行，必须另行获得在线授权。 */
class EmbeddingBaselineOnlineIT {
    static final String MODEL = "text-embedding-v4";
    static final int DIMENSIONS = 1024;
    static final String ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    static final List<String> TEXTS =
            List.of("如何排查 RabbitMQ 消息堆积？", "How does PostgreSQL MVCC work?", "Spring Boot 使用 pgvector 实现 RAG 检索");

    static ModelProperties properties(String endpoint, String key) {
        ModelProperties properties = new ModelProperties();
        properties.setEnabled(true);
        properties.setApiKey(key);
        // 复用配置校验所需占位值，不创建 Chat 客户端或发送 Chat 请求。
        properties.setChatModel("unused-baseline-chat");
        properties.setEmbeddingModel(MODEL);
        properties.setDimensions(DIMENSIONS);
        properties.setBaseUrl(endpoint);
        properties.setTimeoutSeconds(20);
        return properties;
    }

    @Test
    @Timeout(120)
    void verifyBaseline() {
        String stage = "CONFIGURATION";
        int attempted = 0;
        try {
            ModelOnlineIT.requireAuthorization(System.getenv());
            if (!"true".equals(System.getenv("ASSISTANT_EMBEDDING_BASELINE_AUTHORIZED"))) {
                throw new IllegalStateException("Explicit embedding baseline authorization required");
            }
            OpenAiEmbeddingModel model = new ModelConfiguration()
                    .embeddingModel(properties(ENDPOINT, System.getenv("ASSISTANT_MODEL_API_KEY")));
            List<List<String>> requests =
                    List.of(List.of(TEXTS.get(0)), List.of(TEXTS.get(1)), List.of(TEXTS.get(2)), TEXTS);
            // 顺序执行，首次失败立即停止；SDK maxRetries=0，最多四次请求。
            for (List<String> inputs : requests) {
                stage = inputs.size() == 1 ? "EMBEDDING_SINGLE" : "EMBEDDING_BATCH";
                attempted++;
                long start = System.nanoTime();
                EmbeddingResponse response = model.call(new EmbeddingRequest(inputs, null));
                int dimensions = ModelResponseChecks.embeddings(response, inputs.size(), DIMENSIONS);
                System.out.printf(
                        "time=%s sample=E%02d status=PASS requestedModel=%s responseModel=%s dimensions=%d count=%d elapsedMs=%d requestId=unavailable indices=%s%n",
                        Instant.now(),
                        attempted,
                        MODEL,
                        safeLabel(response.getMetadata().getModel()),
                        dimensions,
                        inputs.size(),
                        (System.nanoTime() - start) / 1_000_000,
                        response.getResults().stream()
                                .map(result -> result.getIndex())
                                .toList());
            }
        } catch (RuntimeException failure) {
            System.out.printf("time=%s status=FAIL attempted=%d%n", Instant.now(), attempted);
            throw ModelFailures.safe(stage, failure);
        }
    }

    private static String safeLabel(String value) {
        return value != null && value.matches("[a-zA-Z0-9_.:/-]{1,100}") ? value : "unavailable";
    }
}
