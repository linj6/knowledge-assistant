package com.lnjecit.knowledge.assistant.rag.generation;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.conversation.dto.HistoryTurn;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelCallCancellation;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog;
import com.lnjecit.knowledge.assistant.memory.dto.MemoryTrace;
import com.lnjecit.knowledge.assistant.memory.dto.RecalledMemory;
import com.lnjecit.knowledge.assistant.rag.context.AssembledContext;
import com.lnjecit.knowledge.assistant.rag.model.RagError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AnswerGenerator {
    public static final String PROMPT_VERSION = "rag-answer-v1";
    private static final String HISTORY_PROMPT_VERSION = "rag-answer-v2";
    private static final String MEMORY_PROMPT_VERSION = "rag-answer-v3";
    public static final int MAX_OUTPUT_TOKENS = 1024;
    // 本地保守估算配额，不代表 Qwen 上下文窗口或严格 Token 上界。
    public static final int REQUEST_BUDGET = 12000;
    private static final Logger log = LoggerFactory.getLogger(AnswerGenerator.class);
    private final ObjectProvider<ChatModel> models;
    private final String contract;
    private final String historyContract;
    private final String memoryContract;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TokenEstimator estimator = new TokenEstimator(64);

    public AnswerGenerator(ObjectProvider<ChatModel> models) {
        this.models = models;
        contract = loadContract(PROMPT_VERSION);
        historyContract = loadContract(HISTORY_PROMPT_VERSION);
        memoryContract = loadContract(MEMORY_PROMPT_VERSION);
    }

    private static String loadContract(String version) {
        String contract;
        try {
            contract = new ClassPathResource("prompts/" + version + ".txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("RAG_PROMPT_RESOURCE_MISSING", failure);
        }
        if (!contract.startsWith("Prompt-Version: " + version + "\n")
                && !contract.startsWith("Prompt-Version: " + version + "\r\n"))
            throw new IllegalStateException("RAG_PROMPT_VERSION_MISMATCH");
        return contract;
    }

    public Prompt prompt(String question, AssembledContext context, ChatModel model) {
        // JSON 转义问题；证据沿用 ContextBuilder 已序列化的模型可见数据，不暴露内部来源 ID。
        String user =
                "{\"question\":" + mapper.writeValueAsString(question) + ",\"evidence\":" + context.contextText() + "}";
        if (!fitsBudget(contract, user)) throw new GenerationException(RagError.INPUT_BUDGET_EXCEEDED, null);
        return buildPrompt(contract, user, model);
    }

    /** history 按时间从旧到新排列。 */
    public Prompt prompt(String question, AssembledContext context, ChatModel model, List<HistoryTurn> history) {
        if (!history.isEmpty()) {
            ObjectNode userData = mapper.createObjectNode();
            ArrayNode historyData = userData.putArray("history");
            history.forEach(turn -> historyData.add(mapper.<ObjectNode>valueToTree(turn)));
            userData.put("question", question);
            userData.set("evidence", mapper.readTree(context.contextText()));

            // 每次移除最旧的完整轮次，用实际序列化后的消息重新估算。
            for (int oldest = 0; oldest < history.size(); oldest++) {
                String user = mapper.writeValueAsString(userData);
                if (fitsBudget(historyContract, user)) return buildPrompt(historyContract, user, model);
                historyData.remove(0);
            }
        }
        return prompt(question, context, model);
    }

    private PreparedPrompt preparePrompt(
            String question,
            AssembledContext context,
            ChatModel model,
            List<HistoryTurn> history,
            List<RecalledMemory> memory) {
        if (!memory.isEmpty()) {
            ObjectNode data = mapper.createObjectNode();
            data.set("history", mapper.valueToTree(history));
            data.set("memory", mapper.valueToTree(memory));
            ArrayNode memoryData = (ArrayNode) data.get("memory");
            data.put("question", question);
            data.set("evidence", mapper.readTree(context.contextText()));
            // 以完整序列化请求为准，先移除末尾低优先级记忆，保持证据和历史不变。
            for (int count = memory.size(); count > 0; count--) {
                String user = mapper.writeValueAsString(data);
                if (fitsBudget(memoryContract, user)) {
                    return new PreparedPrompt(
                            buildPrompt(memoryContract, user, model),
                            MEMORY_PROMPT_VERSION,
                            MemoryTrace.from(memory.subList(0, count)));
                }
                memoryData.remove(count - 1);
            }
        }
        Prompt request = prompt(question, context, model, history);
        String version =
                historyContract.equals(request.getInstructions().getFirst().getText())
                        ? HISTORY_PROMPT_VERSION
                        : PROMPT_VERSION;
        return new PreparedPrompt(request, version, MemoryTrace.from(List.of()));
    }

    private record PreparedPrompt(Prompt prompt, String version, MemoryTrace trace) {}

    private boolean fitsBudget(String system, String user) {
        return estimator.estimate(system + user).estimatedTokens() + MAX_OUTPUT_TOKENS <= REQUEST_BUDGET;
    }

    private Prompt buildPrompt(String system, String user, ChatModel model) {
        // 当前 OpenAI SDK 请求 options 有自身默认值，必须从客户端配置派生。
        ChatOptions options = model instanceof OpenAiChatModel openAi
                ? openAi.getOptions()
                        .mutate()
                        .temperature(0.1)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .maxRetries(0)
                        .build()
                : ChatOptions.builder()
                        .temperature(0.1)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .build();
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options);
    }

    public RawGenerationResult generate(String question, AssembledContext context) {
        return generate(question, context, List.of());
    }

    /** history 按时间从旧到新排列。 */
    public RawGenerationResult generate(String question, AssembledContext context, List<HistoryTurn> history) {
        return generate(question, context, history, List.of());
    }

    public RawGenerationResult generate(
            String question, AssembledContext context, List<HistoryTurn> history, List<RecalledMemory> memory) {
        long started = System.nanoTime();
        RagError error = null;
        String modelId = "unavailable";
        String promptVersion = null;
        int injectedCount = 0;
        try {
            ChatModel model = models.getIfAvailable();
            if (model == null) throw new GenerationException(RagError.MODEL_NOT_CONFIGURED, null);
            if (model instanceof OpenAiChatModel openAi)
                modelId = openAi.getOptions().getModel();
            ModelCallCancellation.checkpoint();
            PreparedPrompt prepared = preparePrompt(question, context, model, history, memory);
            Prompt request = prepared.prompt();
            promptVersion = prepared.version();
            injectedCount = prepared.trace().injectedCount();
            ChatResponse response = ModelCallCancellation.duringModelCall(() -> model.call(request));
            RawGenerationResult.Usage usage = null;
            if (response != null && response.getMetadata() != null) {
                if (response.getMetadata().getModel() != null)
                    modelId = response.getMetadata().getModel();
                Usage source = response.getMetadata().getUsage();
                if (source != null && !(source instanceof EmptyUsage))
                    usage = new RawGenerationResult.Usage(
                            source.getPromptTokens(), source.getCompletionTokens(), source.getTotalTokens());
            }
            Generation generation = response == null ? null : response.getResult();
            String finish = generation == null || generation.getMetadata() == null
                    ? null
                    : generation.getMetadata().getFinishReason();
            if ("length".equalsIgnoreCase(finish))
                throw new GenerationException(RagError.MODEL_RESPONSE_TRUNCATED, null, usage);
            String text = generation == null || generation.getOutput() == null
                    ? null
                    : generation.getOutput().getText();
            if (text == null || text.isBlank())
                throw new GenerationException(RagError.MODEL_RESPONSE_EMPTY, null, usage);
            return new RawGenerationResult(
                    text, modelId, promptVersion, usage, finish, elapsed(started), prepared.trace());
        } catch (GenerationException failure) {
            error = failure.error();
            throw failure;
        } catch (RuntimeException failure) {
            ModelCallCancellation.checkpoint();
            error = switch (ModelFailures.classify(failure)) {
                case CONFIGURATION -> RagError.MODEL_NOT_CONFIGURED;
                case TIMEOUT -> RagError.MODEL_TIMEOUT;
                case RATE_LIMIT -> RagError.MODEL_RATE_LIMITED;
                case CONNECTION, SERVER -> RagError.MODEL_UNAVAILABLE;
                case AUTHENTICATION -> RagError.MODEL_AUTHENTICATION_FAILED;
                default -> RagError.GENERATION_FAILED;
            };
            if (memory.isEmpty()) {
                FailureLog.error(log, "RAG_GENERATION", failure);
            } else {
                // 供应商异常可能回显模型请求，只记录错误分类和异常类型。
                log.error(
                        "event=RAG_GENERATION_FAILED requestId={} error={} failureType={}",
                        RequestIdContext.current(),
                        error,
                        failure.getClass().getSimpleName());
            }
            throw new GenerationException(error, failure);
        } finally {
            // 模型名称只用于安全诊断，不打印任何输入和候选文本。
            try {
                log.info(
                        "requestId={} stage=RAG_GENERATION elapsedMillis={} model={} promptVersion={} injectedCount={} recalledCount={} budgetTrimmedCount={} error={}",
                        RequestIdContext.current(),
                        elapsed(started),
                        safeModel(modelId),
                        promptVersion,
                        injectedCount,
                        memory.size(),
                        promptVersion == null ? null : memory.size() - injectedCount,
                        error);
            } catch (RuntimeException loggingFailure) {
                // 诊断失败不改变模型调用结果。
            }
        }
    }

    private static String safeModel(String model) {
        return model != null && model.matches("[a-zA-Z0-9._:/-]{1,120}") ? FailureLog.redact(model) : "unavailable";
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public static final class GenerationException extends RuntimeException {
        private final RagError error;
        private final RawGenerationResult.Usage usage;

        public GenerationException(RagError error, Throwable cause) {
            this(error, cause, null);
        }

        public GenerationException(RagError error, Throwable cause, RawGenerationResult.Usage usage) {
            super(error.name(), cause);
            this.usage = usage;
            this.error = error;
        }

        public RawGenerationResult.Usage usage() {
            return usage;
        }

        public RagError error() {
            return error;
        }
    }
}
