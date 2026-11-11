package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.rag.AnswerGenerator;
import com.lnjecit.knowledge.assistant.rag.AssembledContext;
import com.lnjecit.knowledge.assistant.rag.CitationValidator;
import com.lnjecit.knowledge.assistant.rag.ContextBuilder;
import com.lnjecit.knowledge.assistant.rag.ContextProperties;
import com.lnjecit.knowledge.assistant.rag.RagAnswer;
import com.lnjecit.knowledge.assistant.rag.RagCitation;
import com.lnjecit.knowledge.assistant.rag.RagError;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.RagStatus;
import com.lnjecit.knowledge.assistant.rag.RawGenerationResult;
import com.lnjecit.knowledge.assistant.rag.SelectedEvidence;
import com.lnjecit.knowledge.assistant.rag.StructuredOutputParser;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class CitationValidationTests {
    final StructuredOutputParser parser = new StructuredOutputParser();

    static RawGenerationResult raw(String text) {
        return new RawGenerationResult(text, "fake", "v1", null, "stop", 1);
    }

    static String json(boolean answerable, String citations) {
        return "{\"answerable\":" + answerable + ",\"answer\":\"说明\",\"citations\":" + citations + "}";
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "broken PRIVATE",
                "[]",
                "null",
                "1",
                "\"text\"",
                "```json\n{}\n```",
                "prefix {}",
                "{} suffix",
                "{\"answerable\":true,\"answer\":\"a\",\"citations\":[\"E1\"]} {}",
                "{\"answer\":\"a\",\"citations\":[]}",
                "{\"answerable\":\"false\",\"answer\":\"a\",\"citations\":[]}",
                "{\"answerable\":false,\"answer\":null,\"citations\":[]}",
                "{\"answerable\":false,\"citations\":[]}",
                "{\"answerable\":false,\"answer\":\" \",\"citations\":[]}",
                "{\"answerable\":false,\"answer\":3,\"citations\":[]}",
                "{\"answerable\":false,\"answer\":\"a\"}",
                "{\"answerable\":false,\"answer\":\"a\",\"citations\":null}",
                "{\"answerable\":false,\"answer\":\"a\",\"citations\":\"E1\"}",
                "{\"answerable\":true,\"answer\":\"a\",\"citations\":[1]}",
                "{\"answerable\":true,\"answer\":\"a\",\"citations\":[{}]}",
                "{\"answerable\":true,\"answer\":\"a\",\"citations\":[]}",
                "{\"answerable\":false,\"answer\":\"a\",\"citations\":[\"E1\"]}",
                "{\"answerable\":false,\"answer\":\"a\",\"citations\":[],\"extra\":0}",
                "{\"answerable\":false,\"answerable\":false,\"answer\":\"a\",\"citations\":[]}"
            })
    void rejectsInvalidProtocol(String text) {
        assertThatThrownBy(() -> parser.parse(raw(text)))
                .isInstanceOf(StructuredOutputParser.InvalidOutputException.class)
                .hasMessage("OUTPUT_INVALID")
                .hasNoCause();
    }

    @Test
    void sizeLimit() {
        assertThatThrownBy(() -> parser.parse(raw("x".repeat(StructuredOutputParser.MAX_CHARACTERS + 1))))
                .hasMessage("OUTPUT_INVALID");
    }

    RankedChunk chunk(long id) {
        return new RankedChunk(
                id,
                9007199254740993L,
                9007199254740995L,
                1L,
                "PRIVATE_CONTEXT",
                2,
                17,
                "heading",
                null,
                null,
                null,
                null,
                null,
                null,
                1);
    }

    RetrievalResult result() {
        return new RetrievalResult(
                List.of(chunk(9007199254740997L), chunk(2)),
                new RetrievalDiagnostics(
                        RetrievalStrategy.HYBRID_RRF,
                        RetrievalStrategy.HYBRID_RRF,
                        false,
                        RetrievalStatus.SUCCESS,
                        "query-time-validated-snapshot",
                        RetrievalParameters.defaults(),
                        List.of(),
                        null));
    }

    AssembledContext context() {
        return new ContextBuilder(new ContextProperties()).build(result().chunks());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"[\"E99\"]", "[\"E4\"]", "[\"E1\",\"E1\"]", "[\"E0\"]", "[\"E01\"]", "[\"e1\"]", "[\"E1 \"]"})
    void rejectsReferences(String ids) {
        assertThatThrownBy(() ->
                        new CitationValidator().validate(parser.parse(raw(json(true, ids))), 1L, context(), result()))
                .hasMessage("CITATION_INVALID")
                .hasNoCause();
    }

    @Test
    void mapsServerIdentityInModelOrder() {
        List<RagCitation> citations = new CitationValidator()
                .validate(parser.parse(raw(json(true, "[\"E2\",\"E1\"]"))), 1L, context(), result());
        assertThat(citations).extracting(RagCitation::evidenceId).containsExactly("E2", "E1");
        assertThat(citations.get(1))
                .isEqualTo(new RagCitation("E1", 9007199254740997L, 9007199254740993L, 9007199254740995L, 2, 17));
        tools.jackson.databind.json.JsonMapper.Builder builder = tools.jackson.databind.json.JsonMapper.builder();
        new com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration()
                .longAsString()
                .customize(builder);
        String serialized = builder.build().writeValueAsString(citations);
        assertThat(serialized).contains("\"9007199254740997\"", "\"9007199254740993\"", "\"9007199254740995\"");
    }

    @Test
    void rejectsCrossKbAndTamperedMetadata() {
        SelectedEvidence original = context().evidenceMap().get("E1");
        for (SelectedEvidence e : List.of(
                new SelectedEvidence(
                        "E1",
                        original.chunkId(),
                        2L,
                        original.documentId(),
                        original.indexVersionId(),
                        original.content(),
                        2,
                        17,
                        "heading",
                        1),
                new SelectedEvidence(
                        "E1",
                        0L,
                        1L,
                        original.documentId(),
                        original.indexVersionId(),
                        original.content(),
                        2,
                        17,
                        "heading",
                        1),
                new SelectedEvidence(
                        "E1",
                        original.chunkId(),
                        1L,
                        null,
                        original.indexVersionId(),
                        original.content(),
                        2,
                        17,
                        "heading",
                        1),
                new SelectedEvidence(
                        "E1",
                        original.chunkId(),
                        1L,
                        original.documentId(),
                        99L,
                        original.content(),
                        2,
                        17,
                        "heading",
                        1),
                new SelectedEvidence(
                        "E1",
                        original.chunkId(),
                        1L,
                        original.documentId(),
                        original.indexVersionId(),
                        original.content(),
                        -1,
                        17,
                        "heading",
                        1))) {
            AssembledContext altered = new AssembledContext("[]", List.of(e), Map.of("E1", e), null, 4000, 16000, 0);
            assertThatThrownBy(() -> new CitationValidator()
                            .validate(parser.parse(raw(json(true, "[\"E1\"]"))), 1L, altered, result()))
                    .hasMessage("CITATION_INVALID");
        }
    }

    @Test
    void rejectsCandidateExcludedFromContextAndMapListMismatch() {
        AssembledContext full = context();
        SelectedEvidence first = full.evidenceMap().get("E1");
        AssembledContext selected =
                new AssembledContext("[]", List.of(first), Map.of("E1", first), null, 4000, 16000, 1);
        assertThatThrownBy(() -> new CitationValidator()
                        .validate(parser.parse(raw(json(true, "[\"E2\"]"))), 1L, selected, result()))
                .hasMessage("CITATION_INVALID");
        AssembledContext inconsistent =
                new AssembledContext("[]", List.of(), Map.of("E1", first), null, 4000, 16000, 0);
        assertThatThrownBy(() -> new CitationValidator()
                        .validate(parser.parse(raw(json(true, "[\"E1\"]"))), 1L, inconsistent, result()))
                .hasMessage("CITATION_INVALID");
    }

    @Test
    void usageIsPreservedWithoutRawResponse() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.search(any(), any(), any(), any())).thenReturn(List.of(result()));
        AnswerGenerator generator = mock(AnswerGenerator.class);
        RawGenerationResult.Usage usage = new RawGenerationResult.Usage(11, 7, 18);
        when(generator.generate(any(), any()))
                .thenReturn(new RawGenerationResult(json(true, "[\"E1\"]"), "fake", "v1", usage, "stop", 1));
        RagAnswer answer = new RagService(retrieval, new ContextBuilder(new ContextProperties()), generator)
                .answer(1L, "q", null, null);
        assertThat(answer.diagnostics().usage()).isEqualTo(usage);
        String serialized =
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(answer);
        assertThat(serialized).doesNotContain("rawText", "PRIVATE_CONTEXT", "promptVersion", "contextText");
    }

    static class FakeChat implements ChatModel {
        String text;
        int calls;

        FakeChat(String text) {
            this.text = text;
        }

        public ChatResponse call(Prompt prompt) {
            calls++;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"E1\"]", "[\"E1\",\"E2\"]", "[]"})
    void serviceUsesSingleContextAndChat(String ids) {
        FakeChat chat = new FakeChat(json(!ids.equals("[]"), ids));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chat", chat);
        RetrievalService retrieval = mock(RetrievalService.class);
        RetrievalResult result = result();
        when(retrieval.search(any(), any(), any(), any())).thenReturn(List.of(result));
        ContextBuilder contexts = spy(new ContextBuilder(new ContextProperties()));
        RagAnswer answer = new RagService(
                        retrieval, contexts, new AnswerGenerator(beans.getBeanProvider(ChatModel.class)))
                .answer(1L, "PRIVATE_QUESTION", null, null);
        assertThat(answer.status()).isEqualTo(ids.equals("[]") ? RagStatus.INSUFFICIENT_EVIDENCE : RagStatus.ANSWERED);
        assertThat(answer.answer()).isEqualTo("说明");
        assertThat(answer.error()).isNull();
        assertThat(chat.calls).isOne();
        verify(contexts, times(1)).build(result.chunks());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_RAW", "{\"answerable\":true,\"answer\":\"PRIVATE_RAW\",\"citations\":[\"E99\"]}"})
    void failuresAreSafeAndLoggedOnce(String text) {
        FakeChat chat = new FakeChat(text);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chat", chat);
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.search(any(), any(), any(), any())).thenReturn(List.of(result()));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RagService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RagAnswer answer = new RagService(
                            retrieval,
                            new ContextBuilder(new ContextProperties()),
                            new AnswerGenerator(beans.getBeanProvider(ChatModel.class)))
                    .answer(1L, "PRIVATE_QUESTION", null, null);
            assertThat(answer.status()).isEqualTo(RagStatus.FAILED);
            assertThat(answer.answer()).isNull();
            assertThat(answer.citations()).isEmpty();
            assertThat(answer.error())
                    .isEqualTo(text.startsWith("{") ? RagError.CITATION_INVALID : RagError.OUTPUT_INVALID);
            assertThat(appender.list.stream()
                            .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                            .toList())
                    .hasSize(1);
            assertThat(appender.list.stream()
                            .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                            .findFirst()
                            .orElseThrow()
                            .getFormattedMessage())
                    .contains("requestId=", "RAG_")
                    .doesNotContain("PRIVATE_RAW", "PRIVATE_CONTEXT", "PRIVATE_QUESTION");
            assertThat(appender.list.stream()
                            .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                            .findFirst()
                            .orElseThrow()
                            .getThrowableProxy())
                    .isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void answerStateInvariants() {
        RagCitation citation = new RagCitation("E1", 1L, 2L, 3L, 0, 1);
        for (RagStatus status : RagStatus.values()) {
            assertThatThrownBy(() -> new RagAnswer(
                            status, null, List.of(citation), null, null, false, RagError.OUTPUT_INVALID, null))
                    .hasMessage("RAG_ANSWER_STATE_INVALID");
        }
        assertThatThrownBy(() -> new RagAnswer(RagStatus.FAILED, null, List.of(), null, null, false, null, null))
                .hasMessage("RAG_ANSWER_STATE_INVALID");
        assertThatThrownBy(
                        () -> new RagAnswer(RagStatus.ANSWERED, " ", List.of(citation), null, null, false, null, null))
                .hasMessage("RAG_ANSWER_STATE_INVALID");
    }
}
