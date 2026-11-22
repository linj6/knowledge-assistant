package com.lnjecit.knowledge.assistant.rag.rewrite;

import com.lnjecit.knowledge.assistant.conversation.dto.HistoryTurn;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelCallCancellation;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.rag.RagService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class QueryRewriter {
    private static final int MAX_HISTORY_TURNS = 3;
    private static final int MAX_OUTPUT_TOKENS = 256;
    private static final int REQUEST_BUDGET = 12000;
    // 为 JSON 转义（每个 UTF-16 单元最多六个字符）和协议包装留出空间。
    private static final int MAX_RESPONSE_CHARACTERS = RagService.MAX_QUESTION_LENGTH * 6 + 256;

    private final ObjectProvider<ChatModel> models;
    private final String contract;
    private final TokenEstimator estimator = new TokenEstimator(64);
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public QueryRewriter(ObjectProvider<ChatModel> models) {
        this.models = models;
        try {
            contract = new ClassPathResource("prompts/query-rewrite-v1.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("QUERY_REWRITE_PROMPT_RESOURCE_MISSING", failure);
        }
    }

    /** history 按时间从旧到新排列，最多使用最近三轮。 */
    public Result rewrite(String question, List<HistoryTurn> history) {
        ModelCallCancellation.checkpoint();
        if (history.isEmpty()) return new Result(question, Status.SKIPPED);

        Result fallback = new Result(question, Status.FALLBACK);
        ChatModel model = models.getIfAvailable();
        if (model == null) return fallback;

        List<HistoryTurn> recent = history.subList(Math.max(0, history.size() - MAX_HISTORY_TURNS), history.size());
        String user = mapper.writeValueAsString(new RewriteInput(recent, question));
        // 估算完整消息并预留输出配额，超预算时保留原问题。
        if (estimator.estimate(contract + user).estimatedTokens() + MAX_OUTPUT_TOKENS > REQUEST_BUDGET) return fallback;

        ChatOptions options = model instanceof OpenAiChatModel openAi
                ? openAi.getOptions()
                        .mutate()
                        .temperature(0.0)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .maxRetries(0)
                        .build()
                : ChatOptions.builder()
                        .temperature(0.0)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .build();
        Prompt request = new Prompt(List.of(new SystemMessage(contract), new UserMessage(user)), options);

        ChatResponse response;
        try {
            response = ModelCallCancellation.duringModelCall(() -> model.call(request));
        } catch (RuntimeException failure) {
            // HTTP 取消可能先表现为连接异常，先检查本轮取消状态。
            ModelCallCancellation.checkpoint();
            if (failure instanceof CancellationException || ModelFailures.classify(failure) == ModelFailures.Kind.OTHER)
                throw failure;
            return fallback;
        }
        ModelCallCancellation.checkpoint();

        String query = parseQuery(response);
        ModelCallCancellation.checkpoint();
        if (query == null) return fallback;
        return new Result(query, question.equals(query) ? Status.UNCHANGED : Status.REWRITTEN);
    }

    private String parseQuery(ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        if (generation == null
                || generation.getOutput() == null
                || (generation.getMetadata() != null
                        && "length".equalsIgnoreCase(generation.getMetadata().getFinishReason()))) return null;

        String text = generation.getOutput().getText();
        if (text == null || text.length() > MAX_RESPONSE_CHARACTERS) return null;
        try {
            JsonNode root = mapper.readTree(text);
            if (root == null || !root.isObject() || root.size() != 1) return null;
            JsonNode field = root.get("standaloneQuery");
            if (field == null || !field.isString()) return null;
            String query = field.asString();
            return query.isBlank() || query.length() > RagService.MAX_QUESTION_LENGTH ? null : query;
        } catch (JacksonException invalid) {
            return null;
        }
    }

    private record RewriteInput(List<HistoryTurn> history, String question) {}

    public enum Status {
        SKIPPED,
        UNCHANGED,
        REWRITTEN,
        FALLBACK
    }

    public record Result(String retrievalQuery, Status status) {
        @Override
        public String toString() {
            return "Result[status=" + status + "]";
        }
    }
}
