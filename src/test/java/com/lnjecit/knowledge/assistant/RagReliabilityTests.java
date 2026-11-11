package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.infrastructure.web.RequestIdFilter;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.context.ContextBuilder;
import com.lnjecit.knowledge.assistant.rag.context.ContextProperties;
import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RagReliabilityTests {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "answer",
                "refusal",
                "json",
                "citation",
                "duplicate",
                "empty",
                "timeout",
                "auth",
                "rate",
                "disabled",
                "bug",
                "noResults",
                "retrievalFailed",
                "budget",
                "conflict",
                "serialization",
                "noResponse",
                "backend",
                "truncated",
                "crossKb",
                "single"
            })
    void httpAndStageMatrix(String scenario) throws Exception {
        CitationValidationTests fixture = new CitationValidationTests();
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.search(any(), any(), any(), any())).thenReturn(List.of(fixture.result()));
        ContextBuilder contexts = spy(new ContextBuilder(new ContextProperties()));
        if (scenario.equals("bug"))
            doThrow(new NullPointerException("internal defect")).when(contexts).build(any());
        boolean early = List.of("noResults", "retrievalFailed", "budget", "conflict", "serialization", "bug")
                .contains(scenario);
        if (scenario.equals("noResults") || scenario.equals("retrievalFailed")) {
            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics d =
                    fixture.result().diagnostics();
            boolean failed = scenario.equals("retrievalFailed");
            when(retrieval.search(any(), any(), any(), any()))
                    .thenReturn(List.of(new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult(
                            List.of(),
                            new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics(
                                    d.requestedStrategy(),
                                    failed ? null : d.actualStrategy(),
                                    false,
                                    failed
                                            ? com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus.FAILED
                                            : com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus
                                                    .EMPTY_RESULT,
                                    failed ? null : "snapshot",
                                    d.parameters(),
                                    List.of(),
                                    failed
                                            ? com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics
                                                    .Failure.INTERNAL_ERROR
                                            : null))));
        }
        if (scenario.equals("budget")) {
            ContextProperties properties = new ContextProperties();
            properties.setMaxCharacters(2);
            doReturn(new ContextBuilder(properties).build(fixture.result().chunks()))
                    .when(contexts)
                    .build(any());
        }
        if (scenario.equals("conflict"))
            doThrow(new ContextBuilder.InconsistentEvidenceException())
                    .when(contexts)
                    .build(any());
        if (scenario.equals("serialization"))
            doThrow(new tools.jackson.core.JacksonException("PRIVATE_CONTEXT") {})
                    .when(contexts)
                    .build(any());
        ChatModel model = mock(ChatModel.class);
        String text =
                switch (scenario) {
                    case "single" -> fixture.json(true, "[\"E1\"]");
                    case "refusal" -> fixture.json(false, "[]");
                    case "json" -> "RAW_PRIVATE";
                    case "citation" -> fixture.json(true, "[\"E99\"]");
                    case "duplicate" -> fixture.json(true, "[\"E1\",\"E1\"]");
                    case "empty" -> "";
                    default -> fixture.json(true, "[\"E1\",\"E2\"]");
                };
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(
                                new AssistantMessage(text),
                                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                        .finishReason(scenario.equals("truncated") ? "length" : "stop")
                                        .build())),
                        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(7, 3))
                                .build()));
        if (List.of("timeout", "auth", "rate").contains(scenario)) {
            com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.Kind kind =
                    switch (scenario) {
                        case "auth" -> com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.Kind
                                .AUTHENTICATION;
                        case "rate" -> com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.Kind.RATE_LIMIT;
                        default -> com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.Kind.TIMEOUT;
                    };
            when(model.call(any(Prompt.class)))
                    .thenThrow(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.rerankFailure(kind));
        }
        if (scenario.equals("noResponse")) when(model.call(any(Prompt.class))).thenReturn(null);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (!scenario.equals("disabled")) beans.addBean("chat", model);
        RagService service =
                new RagService(retrieval, contexts, new AnswerGenerator(beans.getBeanProvider(ChatModel.class)));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RagService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        logger.addAppender(events);
        ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> broken =
                new ch.qos.logback.core.AppenderBase<>() {
                    @Override
                    public void doAppend(ch.qos.logback.classic.spi.ILoggingEvent event) {
                        throw new IllegalStateException("backend unavailable");
                    }

                    @Override
                    protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {}
                };
        if (scenario.equals("backend")) {
            broken.start();
            logger.addAppender(broken);
        }
        try {
            org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
                            new com.lnjecit.knowledge.assistant.rag.api.RagController(service))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .addFilters(new RequestIdFilter())
                    .build();
            org.springframework.test.web.servlet.ResultActions response = mvc.perform(post("/v1/chat/answers")
                    .contentType("application/json")
                    .content("{\"knowledgeBaseId\":" + (scenario.equals("crossKb") ? 2 : 1)
                            + ",\"question\":\"QUESTION_PRIVATE\"}"));
            if (scenario.equals("bug")) response.andExpect(status().isInternalServerError());
            else {
                response.andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0))
                        .andExpect(jsonPath("$.requestId").isNotEmpty())
                        .andExpect(jsonPath("$.data.requestedStrategy").value("HYBRID_RRF"))
                        .andExpect(jsonPath("$.data.degraded").value(false));
                if (scenario.equals("retrievalFailed"))
                    response.andExpect(jsonPath("$.data.actualStrategy").isEmpty());
                else response.andExpect(jsonPath("$.data.actualStrategy").value("HYBRID_RRF"));
                if (!early
                        && !List.of("timeout", "auth", "rate", "disabled", "noResponse")
                                .contains(scenario))
                    response.andExpect(
                            jsonPath("$.data.diagnostics.usage.totalTokens").value(10));
                else response.andExpect(jsonPath("$.data.diagnostics.usage").isEmpty());
                String error =
                        switch (scenario) {
                            case "json" -> "OUTPUT_INVALID";
                            case "citation", "duplicate", "crossKb" -> "CITATION_INVALID";
                            case "empty", "noResponse" -> "MODEL_RESPONSE_EMPTY";
                            case "retrievalFailed" -> "RETRIEVAL_FAILED";
                            case "budget" -> "CONTEXT_BUDGET_EXCEEDED";
                            case "conflict" -> "CONTEXT_DATA_INCONSISTENT";
                            case "serialization" -> "CONTEXT_BUILD_FAILED";
                            case "truncated" -> "MODEL_RESPONSE_TRUNCATED";
                            case "timeout" -> "MODEL_TIMEOUT";
                            case "auth" -> "MODEL_AUTHENTICATION_FAILED";
                            case "rate" -> "MODEL_RATE_LIMITED";
                            case "disabled" -> "MODEL_NOT_CONFIGURED";
                            default -> null;
                        };
                response.andExpect(jsonPath("$.data.status")
                        .value(
                                error != null
                                        ? "FAILED"
                                        : List.of("refusal", "noResults").contains(scenario)
                                                ? "INSUFFICIENT_EVIDENCE"
                                                : "ANSWERED"));
                if (error != null)
                    response.andExpect(jsonPath("$.data.error").value(error))
                            .andExpect(jsonPath("$.data.answer").isEmpty())
                            .andExpect(jsonPath("$.data.citations").isEmpty());
            }
            List<String> logs =
                    events.list.stream().map(e -> e.getFormattedMessage()).toList();
            assertThat(logs.stream().filter(s -> s.contains("stage=OVERALL ")).count())
                    .isEqualTo(1);
            assertThat(String.join(" ", logs))
                    .contains("stage=RETRIEVAL", "elapsedMillis=")
                    .doesNotContain("QUESTION_PRIVATE", "RAW_PRIVATE");
            boolean retrievedOnly = List.of("noResults", "retrievalFailed").contains(scenario);
            assertThat(logs.stream()
                            .filter(l -> l.contains("stage=CONTEXT_BUILD "))
                            .count())
                    .isEqualTo(retrievedOnly ? 0 : 1);
            assertThat(logs.stream()
                            .filter(l -> l.contains("stage=GENERATION "))
                            .count())
                    .isEqualTo(early ? 0 : 1);
            boolean parsed = !early
                    && !List.of("timeout", "auth", "rate", "disabled", "empty", "noResponse", "truncated")
                            .contains(scenario);
            assertThat(logs.stream()
                            .filter(l -> l.contains("stage=OUTPUT_PARSE "))
                            .count())
                    .isEqualTo(parsed ? 1 : 0);
            assertThat(logs.stream()
                            .filter(l -> l.contains("stage=CITATION_VALIDATE "))
                            .count())
                    .isEqualTo(parsed && !scenario.equals("json") ? 1 : 0);
            if (scenario.equals("bug")) assertThat(String.join(" ", logs)).doesNotContain("stage=GENERATION");
            else
                verify(model, times(scenario.equals("disabled") || early ? 0 : 1))
                        .call(any(Prompt.class));
            if (List.of("noResults", "retrievalFailed").contains(scenario))
                verify(contexts, times(0)).build(any());
            else verify(contexts).build(any());
        } finally {
            logger.detachAppender(broken);
            logger.detachAppender(events);
            events.stop();
        }
    }
}
