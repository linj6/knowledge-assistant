package com.lnjecit.knowledge.assistant.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.memory.dto.MemoryTrace;
import com.lnjecit.knowledge.assistant.memory.dto.RecalledMemory;
import com.lnjecit.knowledge.assistant.rag.context.AssembledContext;
import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import com.lnjecit.knowledge.assistant.rag.generation.RawGenerationResult;
import com.lnjecit.knowledge.assistant.rag.validation.CitationValidator;
import com.lnjecit.knowledge.assistant.rag.validation.StructuredOutputParser;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class OnlineRun {
    private static final String ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path journal;
    private final String key;
    private final AnswerGenerator generator;
    private final AtomicInteger posted = new AtomicInteger();
    private Prompt sent;
    private Prompt expected;
    private boolean dispatched;

    OnlineRun(Path journal, String key) throws IOException {
        this.journal = journal;
        this.key = key;
        Files.createFile(journal);
        OpenAiChatModel real = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(key)
                        .baseUrl(ENDPOINT)
                        .model("qwen-plus")
                        .timeout(Duration.ofSeconds(30))
                        .maxRetries(0)
                        .maxTokens(128)
                        .build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(chain -> {
                    if (dispatched
                            || posted.get() >= 12
                            || !chain.request().method().equals("POST")
                            || !chain.request().url().toString().equals(ENDPOINT + "/chat/completions")) {
                        throw new IOException("UNEXPECTED_HTTP_REQUEST");
                    }
                    dispatched = true;
                    RequestBody original =
                            Objects.requireNonNull(chain.request().body());
                    RequestBody once = new RequestBody() {
                        private boolean written;

                        @Override
                        public boolean isOneShot() {
                            return true;
                        }

                        @Override
                        public MediaType contentType() {
                            return original.contentType();
                        }

                        @Override
                        public long contentLength() throws IOException {
                            return original.contentLength();
                        }

                        @Override
                        public void writeTo(BufferedSink sink) throws IOException {
                            if (written) throw new IOException("DUPLICATE_CHAT_BODY");
                            written = true;
                            posted.incrementAndGet();
                            original.writeTo(sink);
                        }
                    };
                    return chain.proceed(
                            chain.request().newBuilder().method("POST", once).build());
                }))
                .build();
        OpenAiChatModel capture = mock(OpenAiChatModel.class);
        when(capture.getOptions()).thenReturn(real.getOptions());
        when(capture.call(any(Prompt.class))).thenAnswer(call -> {
            if (sent != null) throw new IllegalStateException("DUPLICATE_MODEL_CALL");
            sent = call.getArgument(0);
            if (!sent.getInstructions().equals(expected.getInstructions())) {
                throw new IllegalStateException("ONLINE_PROMPT_DIFFERS_FROM_PREFLIGHT");
            }
            return real.call(sent);
        });
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chat", capture);
        generator = new AnswerGenerator(beans.getBeanProvider(ChatModel.class));
    }

    void run(
            MemoryAbPreflightTests.Case item,
            AssembledContext context,
            RetrievalResult retrieval,
            List<RecalledMemory> recalled,
            Prompt verified)
            throws IOException {
        expected = verified;
        sent = null;
        dispatched = false;
        int before = posted.get();
        long started = System.nanoTime();
        ObjectNode result = json.createObjectNode();
        result.put("caseId", item.id());
        result.put("group", recalled.isEmpty() ? "A" : "B");
        result.set(
                "recalledIds",
                json.valueToTree(recalled.stream().map(RecalledMemory::id).toList()));
        String stage = "CHAT";
        try {
            RawGenerationResult raw = recalled.isEmpty()
                    ? generator.generate(item.question(), context, item.history())
                    : generator.generate(item.question(), context, item.history(), recalled);
            result.put("model", redact(raw.modelId()));
            result.put("rawResponse", redact(raw.rawText()));
            result.put("finishReason", redact(raw.finishReason()));
            result.set("usage", json.valueToTree(raw.usage()));
            result.put("generationElapsedMillis", raw.generationElapsedMillis());
            stage = "OUTPUT_PARSE";
            StructuredOutputParser.ParsedOutput parsed = new StructuredOutputParser().parse(raw);
            result.put("answerable", parsed.answerable());
            result.put("answer", redact(parsed.answer()));
            result.set(
                    "citationIds",
                    json.valueToTree(
                            parsed.citations().stream().map(this::redact).toList()));
            stage = "CITATION_VALIDATE";
            result.set("citations", json.valueToTree(new CitationValidator().validate(parsed, 1L, context, retrieval)));
            result.put("status", "SUCCESS");
        } catch (RuntimeException failure) {
            result.put("status", "FAILED");
            result.put("failureStage", stage);
            result.put(
                    "failureCode",
                    failure instanceof AnswerGenerator.GenerationException generation
                            ? generation.error().name()
                            : failure.getClass().getSimpleName());
            if (failure instanceof AnswerGenerator.GenerationException generation) {
                result.set("usage", json.valueToTree(generation.usage()));
            }
        }
        if (!result.has("generationElapsedMillis")) {
            result.put("generationElapsedMillis", (System.nanoTime() - started) / 1_000_000);
        }
        result.put("httpDispatches", dispatched ? 1 : 0);
        result.put("requestBodiesSent", posted.get() - before);
        result.put(
                "promptMatchesPreflight", sent != null && sent.getInstructions().equals(expected.getInstructions()));
        if (sent != null) {
            result.put(
                    "promptVersion",
                    sent.getInstructions()
                            .getFirst()
                            .getText()
                            .lines()
                            .findFirst()
                            .orElseThrow()
                            .substring("Prompt-Version: ".length()));
            JsonNode data = json.readTree(sent.getInstructions().getLast().getText());
            JsonNode memory = data.path("memory");
            result.set("memoryTrace", json.valueToTree(MemoryTrace.from(recalled.subList(0, memory.size()))));
            result.put(
                    "memoryEstimatedTokens",
                    memory.isEmpty()
                            ? 0
                            : new TokenEstimator(0)
                                    .estimate(json.writeValueAsString(memory))
                                    .estimatedTokens());
            result.put("generationTrimmedMemories", recalled.size() - memory.size());
            result.set("effectiveHistory", data.has("history") ? data.get("history") : json.createArrayNode());
        }
        Files.writeString(journal, json.writeValueAsString(result) + "\n", StandardOpenOption.APPEND);
    }

    private String redact(String text) {
        return text == null ? null : text.replace(key, "[REDACTED]");
    }
}
