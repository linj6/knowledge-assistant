package com.lnjecit.knowledge.assistant;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelResponseChecks;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

// IT 命名排除在默认 Surefire 执行之外；显式执行时缺少配置必须失败。
class ModelOnlineIT {
    private String stage = "CONFIGURATION";

    @Test
    @Timeout(180)
    void chatAndEmbedding() {
        try {
            runSmoke();
        } catch (RuntimeException ex) {
            throw com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe(stage, ex);
        }
    }

    private void logChat(ChatResponse response, long start) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            ModelResponseChecks.json(null);
        }
        Usage usage = response.getMetadata().getUsage();
        System.out.printf(
                "stage=%s model=%s elapsedMs=%d input=%s output=%s total=%s finish_reason=%s%n",
                stage,
                safeLabel(response.getMetadata().getModel()),
                (System.nanoTime() - start) / 1_000_000,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens(),
                safeLabel(response.getResult().getMetadata().getFinishReason()));
    }

    private String safeLabel(String value) {
        return value != null && value.matches("[a-zA-Z0-9_.:/-]{1,100}") ? value : "unavailable";
    }

    private void runSmoke() {
        if (!"true".equals(System.getenv("ASSISTANT_MODEL_ENABLED"))) {
            throw new IllegalStateException("Explicit ASSISTANT_MODEL_ENABLED=true required");
        }
        ModelProperties p = new ModelProperties();
        p.setEnabled(true);
        p.setApiKey(System.getenv("ASSISTANT_MODEL_API_KEY"));
        p.setChatModel(System.getenv("ASSISTANT_MODEL_CHAT_MODEL"));
        p.setEmbeddingModel(System.getenv("ASSISTANT_MODEL_EMBEDDING_MODEL"));
        if (System.getenv("ASSISTANT_MODEL_BASE_URL") != null) p.setBaseUrl(System.getenv("ASSISTANT_MODEL_BASE_URL"));
        if (System.getenv("ASSISTANT_MODEL_DIMENSIONS") != null)
            p.setDimensions(Integer.valueOf(System.getenv("ASSISTANT_MODEL_DIMENSIONS")));
        p.setTimeoutSeconds(20);
        ModelConfiguration config = new ModelConfiguration();
        OpenAiChatModel chat = config.chatModel(p);
        OpenAiEmbeddingModel embedding = config.embeddingModel(p);
        stage = "CHAT_PLAIN";
        long start = System.nanoTime();
        ChatResponse plain = chat.call(new Prompt("只回答数字：1+1等于多少？"));
        logChat(plain, start);
        ResponseFormat format = new OpenAiChatModel.ResponseFormat();
        format.setType(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        // 三次独立原生 JSON mode 请求；通过不代表可靠性保证，也不等于 JSON Schema 支持。
        for (int i = 0; i < 3; i++) {
            stage = "CHAT_JSON";
            start = System.nanoTime();
            ChatResponse structured = chat.call(new Prompt(
                    "返回 JSON 对象：answer 为字符串测试答案，citations 为字符串数组 [E1]。",
                    chat.getOptions().mutate().responseFormat(format).build()));
            logChat(structured, start);
            ModelResponseChecks.json(structured.getResult().getOutput().getText());
        }
        List<String> texts = List.of("Spring Boot 使用依赖注入管理 Bean。", "Redis 可以用作缓存。", "PostgreSQL 支持 pgvector 扩展。");
        Integer dimensions = p.getDimensions();
        for (List<String> inputs : List.of(texts.subList(0, 1), texts)) {
            stage = inputs.size() == 1 ? "EMBEDDING_SINGLE" : "EMBEDDING_BATCH";
            start = System.nanoTime();
            EmbeddingResponse response = embedding.call(new EmbeddingRequest(inputs, null));
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
