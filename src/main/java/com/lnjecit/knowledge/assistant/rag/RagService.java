package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog;
import com.lnjecit.knowledge.assistant.rag.context.AssembledContext;
import com.lnjecit.knowledge.assistant.rag.context.ContextBuilder;
import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import com.lnjecit.knowledge.assistant.rag.generation.RawGenerationResult;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import com.lnjecit.knowledge.assistant.rag.model.RagCitation;
import com.lnjecit.knowledge.assistant.rag.model.RagError;
import com.lnjecit.knowledge.assistant.rag.model.RagStatus;
import com.lnjecit.knowledge.assistant.rag.validation.CitationValidator;
import com.lnjecit.knowledge.assistant.rag.validation.StructuredOutputParser;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    public static final int MAX_QUESTION_LENGTH = 4000;
    private final RetrievalService retrieval;
    private final ContextBuilder contexts;
    private final AnswerGenerator generator;
    private final StructuredOutputParser parser = new StructuredOutputParser();
    private final CitationValidator validator = new CitationValidator();

    public RagService(RetrievalService retrieval, ContextBuilder contexts, AnswerGenerator generator) {
        this.retrieval = retrieval;
        this.contexts = contexts;
        this.generator = generator;
    }

    public RagAnswer answer(
            Long knowledgeBaseId, String question, RetrievalStrategy strategy, RetrievalParameters parameters) {
        Observation observation = new Observation();
        long started = System.nanoTime();
        RagAnswer outcome = null;
        try {
            outcome = execute(knowledgeBaseId, question, strategy, parameters, observation);
            return outcome;
        } finally {
            // 程序缺陷继续传播，全局处理器负责唯一的 ERROR 堆栈。
            try {
                log.info(
                        "requestId={} stage=OVERALL elapsedMillis={} status={} error={} requestedStrategy={} actualStrategy={} degraded={} selectedEvidenceCount={} citationCount={}",
                        RequestIdContext.current(),
                        (System.nanoTime() - started) / 1_000_000,
                        outcome == null ? null : outcome.status(),
                        outcome == null ? null : outcome.error(),
                        outcome == null ? strategy : outcome.requestedStrategy(),
                        outcome == null ? null : outcome.actualStrategy(),
                        outcome == null ? null : outcome.degraded(),
                        observation.selectedCount,
                        outcome == null ? null : outcome.citations().size());
            } catch (RuntimeException loggingFailure) {
                // 日志后端故障不改变领域返回。
            }
        }
    }

    private static final class Observation {
        private int selectedCount;
    }

    private RagAnswer execute(
            Long knowledgeBaseId,
            String question,
            RetrievalStrategy strategy,
            RetrievalParameters parameters,
            Observation observation) {
        if (knowledgeBaseId == null
                || knowledgeBaseId <= 0
                || question == null
                || question.isBlank()
                || question.length() > MAX_QUESTION_LENGTH)
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        RetrievalStrategy requested = strategy == null ? RetrievalStrategy.HYBRID_RRF : strategy;
        RetrievalParameters resolved = parameters == null ? RetrievalParameters.defaults() : parameters;
        try {
            resolved.validate(requested);
        } catch (IllegalArgumentException failure) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        // 单次问答只请求一个策略，复用 M2 的发布快照、召回和失败隔离。
        RetrievalResult result = timed("RETRIEVAL", () -> retrieval
                .search(knowledgeBaseId, question, List.of(requested), resolved)
                .getFirst());
        RetrievalDiagnostics diagnostics = result.diagnostics();
        if (diagnostics.status() == RetrievalStatus.EMPTY_RESULT)
            return response(result, RagStatus.INSUFFICIENT_EVIDENCE, null, List.of(), null, null);
        if (diagnostics.status() == RetrievalStatus.FAILED)
            return response(result, RagStatus.FAILED, null, List.of(), RagError.RETRIEVAL_FAILED, null);

        // 同次请求只构建一次上下文，后续校验沿用这一份证据映射。
        AssembledContext context;
        try {
            context = timed("CONTEXT_BUILD", () -> contexts.build(result.chunks()));
        } catch (ContextBuilder.InconsistentEvidenceException failure) {
            logContextFailure(RagError.CONTEXT_DATA_INCONSISTENT, failure);
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CONTEXT_DATA_INCONSISTENT, null);
        } catch (tools.jackson.core.JacksonException failure) {
            logContextFailure(RagError.CONTEXT_BUILD_FAILED, failure);
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CONTEXT_BUILD_FAILED, null);
        }
        observation.selectedCount = context.selectedCount();
        if (context.selectedCount() == 0)
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CONTEXT_BUDGET_EXCEEDED, null);
        RawGenerationResult raw;
        try {
            raw = timed("GENERATION", () -> generator.generate(question, context));
        } catch (AnswerGenerator.GenerationException failure) {
            return response(result, RagStatus.FAILED, null, List.of(), failure.error(), failure.usage());
        }
        try {
            StructuredOutputParser.ParsedOutput parsed = timed("OUTPUT_PARSE", () -> parser.parse(raw));
            List<RagCitation> citations =
                    timed("CITATION_VALIDATE", () -> validator.validate(parsed, knowledgeBaseId, context, result));
            return response(
                    result,
                    parsed.answerable() ? RagStatus.ANSWERED : RagStatus.INSUFFICIENT_EVIDENCE,
                    parsed.answer(),
                    citations,
                    null,
                    raw.usage());
        } catch (StructuredOutputParser.InvalidOutputException failure) {
            FailureLog.error(log, "RAG_OUTPUT errorCode=OUTPUT_INVALID", failure);
            return response(
                    result,
                    RagStatus.FAILED,
                    null,
                    List.of(),
                    RagError.OUTPUT_INVALID,
                    raw == null ? null : raw.usage());
        } catch (CitationValidator.InvalidCitationException failure) {
            FailureLog.error(log, "RAG_CITATION errorCode=CITATION_INVALID", failure);
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CITATION_INVALID, raw.usage());
        }
    }

    private <T> T timed(String stage, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            try {
                log.info(
                        "requestId={} stage={} elapsedMillis={}",
                        RequestIdContext.current(),
                        stage,
                        (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException loggingFailure) {
                // 不让观测改变业务语义，也不递归记录日志故障。
            }
        }
    }

    private RagAnswer response(
            RetrievalResult result,
            RagStatus status,
            String answer,
            List<RagCitation> citations,
            RagError error,
            RawGenerationResult.Usage usage) {
        RetrievalDiagnostics d = result.diagnostics();
        return new RagAnswer(
                status,
                answer,
                citations,
                d.requestedStrategy(),
                d.actualStrategy(),
                d.degraded(),
                error,
                new RagAnswer.Diagnostics(d.status(), d.error(), d.snapshotHash(), usage));
    }

    private void logContextFailure(RagError error, RuntimeException failure) {
        FailureLog.error(log, "RAG_CONTEXT errorCode=" + error, failure);
    }
}
