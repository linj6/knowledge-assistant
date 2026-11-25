package com.lnjecit.knowledge.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.repository.UserRepository;
import com.lnjecit.knowledge.assistant.conversation.dto.HistoryTurn;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.memory.dto.MemoryTrace;
import com.lnjecit.knowledge.assistant.memory.dto.RecalledMemory;
import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import com.lnjecit.knowledge.assistant.memory.entity.UserMemory;
import com.lnjecit.knowledge.assistant.memory.repository.UserMemoryRepository;
import com.lnjecit.knowledge.assistant.memory.service.MemoryRecallProperties;
import com.lnjecit.knowledge.assistant.memory.service.MemoryRecallService;
import com.lnjecit.knowledge.assistant.rag.context.AssembledContext;
import com.lnjecit.knowledge.assistant.rag.context.ContextBuilder;
import com.lnjecit.knowledge.assistant.rag.context.ContextProperties;
import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import com.lnjecit.knowledge.assistant.rag.generation.RawGenerationResult;
import com.lnjecit.knowledge.assistant.rag.validation.CitationValidator;
import com.lnjecit.knowledge.assistant.rag.validation.StructuredOutputParser;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MemoryAbPreflightTests {
    private final JsonMapper json = JsonMapper.builder().build();

    record Case(String id, String question, List<HistoryTurn> history, List<Evidence> evidence) {}

    record Evidence(String evidenceId, String source, int startLine, int endLine, String content, String sha256) {}

    @Test
    void verifiesSixFrozenPairsWithLocalChatStub() throws Exception {
        verifyPairs(null);
    }

    void verifyPairs(OnlineRun online) throws Exception {
        Path directory = Path.of("evaluation/mem-05b");
        String frozen = Files.readString(directory.resolve("cases.json")).replace("\r\n", "\n");
        String manifest = Files.readString(directory.resolve("cases.sha256")).strip();
        assertThat(sha256(frozen)).isEqualTo(manifest);
        Case[] cases = json.readValue(frozen, Case[].class);
        assertThat(cases).extracting(Case::id).containsExactly("AB01", "AB02", "AB03", "AB04", "AB05", "AB06");
        MemoryRecallEvaluationTests.Samples samples = json.readValue(
                Path.of("evaluation/mem-05a/samples.json").toFile(), MemoryRecallEvaluationTests.Samples.class);

        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getOptions())
                .thenReturn(OpenAiChatOptions.builder()
                        .model("qwen-plus")
                        .maxRetries(0)
                        .maxTokens(128)
                        .build());
        List<Prompt> requests = new ArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return new ChatResponse(
                    List.of(
                            new Generation(
                                    new AssistantMessage(
                                            """
                    {"answerable":true,"answer":"stub","citations":["E1"]}
                    """))));
        });
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chat", model);
        AnswerGenerator generator = new AnswerGenerator(beans.getBeanProvider(ChatModel.class));
        for (Case item : cases) {
            List<RankedChunk> chunks = new ArrayList<>();
            for (Evidence evidence : item.evidence()) {
                List<String> source = Files.readAllLines(Path.of(evidence.source()));
                String content = String.join("\n", source.subList(evidence.startLine() - 1, evidence.endLine()));
                assertThat(evidence.content()).isEqualTo(content);
                assertThat(sha256(content)).isEqualTo(evidence.sha256());
                long id = chunks.size() + 1L;
                chunks.add(new RankedChunk(
                        id,
                        1L,
                        1L,
                        1L,
                        content,
                        evidence.startLine() - 1,
                        evidence.endLine(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        (int) id));
            }
            AssembledContext context = new ContextBuilder(new ContextProperties()).build(chunks);
            assertThat(context.selectedEvidence())
                    .extracting(entry -> entry.evidenceId())
                    .containsExactlyElementsOf(
                            item.evidence().stream().map(Evidence::evidenceId).toList());
            assertThat(context.selectedEvidence())
                    .extracting(entry -> entry.content())
                    .containsExactlyElementsOf(
                            item.evidence().stream().map(Evidence::content).toList());

            List<RecalledMemory> recalled = recall(samples.memories(), item);
            requests.clear();
            RawGenerationResult a = generator.generate(item.question(), context, item.history());
            RawGenerationResult b = generator.generate(item.question(), context, item.history(), recalled);
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst().getOptions())
                    .isEqualTo(requests.getLast().getOptions());
            OpenAiChatOptions options = (OpenAiChatOptions) requests.getFirst().getOptions();
            assertThat(options.getModel()).isEqualTo("qwen-plus");
            assertThat(options.getMaxTokens()).isEqualTo(1024);
            assertThat(options.getTemperature()).isEqualTo(0.1);
            assertThat(options.getMaxRetries()).isZero();

            JsonNode aData = verifyPrompt(
                    requests.getFirst(),
                    item,
                    context,
                    a,
                    item.history().isEmpty() ? "rag-answer-v1" : "rag-answer-v2");
            JsonNode bData = verifyPrompt(requests.getLast(), item, context, b, "rag-answer-v3");
            assertThat(aData.has("memory")).isFalse();
            assertThat(a.memoryTrace().memories()).isEmpty();
            int injected = b.memoryTrace().injectedCount();
            assertThat(injected).isBetween(1, recalled.size());
            List<RecalledMemory> transmitted = recalled.subList(0, injected);
            assertThat(b.memoryTrace()).isEqualTo(MemoryTrace.from(transmitted));
            assertThat(bData.path("memory")).isEqualTo(json.valueToTree(transmitted));
            List<Long> ids = transmitted.stream().map(RecalledMemory::id).toList();
            if (item.id().equals("AB04")) assertThat(ids).contains(109L);
            if (item.id().equals("AB06")) assertThat(ids).contains(110L);

            // 冻结证据使用合成候选，只检查解析与引用映射，不模拟线上发布快照证明。
            RetrievalStrategy strategy = RetrievalStrategy.BM25;
            RetrievalResult retrieval = new RetrievalResult(
                    chunks,
                    new RetrievalDiagnostics(
                            strategy,
                            strategy,
                            false,
                            RetrievalStatus.SUCCESS,
                            manifest,
                            RetrievalParameters.defaults(),
                            List.of(),
                            null));
            for (RawGenerationResult raw : List.of(a, b)) {
                assertThat(new CitationValidator()
                                .validate(new StructuredOutputParser().parse(raw), 1L, context, retrieval))
                        .extracting(citation -> citation.evidenceId())
                        .containsExactly("E1");
            }
            if (online != null) {
                online.run(item, context, retrieval, List.of(), requests.getFirst());
                online.run(item, context, retrieval, recalled, requests.getLast());
            }
            System.out.printf(
                    "%s %s/%s recalled=%s injected=%s trimmed=%d memoryTokens=%d%n",
                    item.id(),
                    a.promptVersion(),
                    b.promptVersion(),
                    recalled.stream().map(RecalledMemory::id).toList(),
                    ids,
                    recalled.size() - injected,
                    new TokenEstimator(0)
                            .estimate(json.writeValueAsString(transmitted))
                            .estimatedTokens());
        }
    }

    private JsonNode verifyPrompt(
            Prompt prompt, Case item, AssembledContext context, RawGenerationResult raw, String version) {
        assertThat(raw.promptVersion()).isEqualTo(version);
        assertThat(prompt.getInstructions()).hasSize(2);
        String system = prompt.getInstructions().getFirst().getText();
        String user = prompt.getInstructions().getLast().getText();
        assertThat(system.lines().findFirst()).contains("Prompt-Version: " + version);
        assertThat(new TokenEstimator(64).estimate(system + user).estimatedTokens() + AnswerGenerator.MAX_OUTPUT_TOKENS)
                .isLessThanOrEqualTo(AnswerGenerator.REQUEST_BUDGET);
        JsonNode body = json.readTree(user);
        assertThat(body.path("question").asString()).isEqualTo(item.question());
        assertThat(body.path("evidence")).isEqualTo(json.readTree(context.contextText()));
        JsonNode history = body.has("history") ? body.get("history") : json.createArrayNode();
        assertThat(history).isEqualTo(json.valueToTree(item.history()));
        return body;
    }

    private List<RecalledMemory> recall(List<UserMemory> frozen, Case item) {
        List<UserMemory> fixture = new ArrayList<>(frozen);
        if (item.id().equals("AB06")) {
            UserMemory wrong = new UserMemory();
            wrong.setId(110L);
            wrong.setEnabled(true);
            wrong.setType(MemoryType.OTHER);
            wrong.setKeywords("rabbitmq,prefetch");
            wrong.setUpdatedAt(Instant.parse("2025-01-10T00:00:00Z"));
            wrong.setContent("我记得 RabbitMQ 实验06配置 (4,2) 暂停时 Unacked 是 4。");
            fixture.add(wrong);
        }
        UserRepository users = mock(UserRepository.class);
        UserMemoryRepository memories = mock(UserMemoryRepository.class);
        User user = new User();
        user.setMemoryEnabled(true);
        when(users.find(1L)).thenReturn(user);
        when(memories.listEnabledOwned(1L))
                .thenReturn(fixture.stream()
                        .filter(UserMemory::getEnabled)
                        .sorted(Comparator.comparing(UserMemory::getUpdatedAt)
                                .thenComparing(UserMemory::getId)
                                .reversed())
                        .toList());
        return new MemoryRecallService(memories, users, new MemoryRecallProperties()).recall(1L, item.question());
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
