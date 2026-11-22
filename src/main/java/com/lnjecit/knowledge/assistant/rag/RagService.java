package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.conversation.dto.HistoryTurn;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelCallCancellation;
import com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog;
import com.lnjecit.knowledge.assistant.rag.context.AssembledContext;
import com.lnjecit.knowledge.assistant.rag.context.ContextBuilder;
import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import com.lnjecit.knowledge.assistant.rag.generation.RawGenerationResult;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import com.lnjecit.knowledge.assistant.rag.model.RagCitation;
import com.lnjecit.knowledge.assistant.rag.model.RagError;
import com.lnjecit.knowledge.assistant.rag.model.RagStatus;
import com.lnjecit.knowledge.assistant.rag.rewrite.QueryRewriter;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    public static final int MAX_QUESTION_LENGTH = 4000;
    private final RetrievalService retrieval;
    private final ContextBuilder contexts;
    private final AnswerGenerator generator;
    private final QueryRewriter rewriter;
    private final boolean queryRewriteEnabled;
    private final StructuredOutputParser parser = new StructuredOutputParser();
    private final CitationValidator validator = new CitationValidator();

    public RagService(RetrievalService retrieval, ContextBuilder contexts, AnswerGenerator generator) {
        this(retrieval, contexts, generator, null, false);
    }

    @Autowired
    public RagService(
            RetrievalService retrieval,
            ContextBuilder contexts,
            AnswerGenerator generator,
            QueryRewriter rewriter,
            @Value("${assistant.rag.query-rewrite.enabled:false}") boolean queryRewriteEnabled) {
        this.retrieval = retrieval;
        this.contexts = contexts;
        this.generator = generator;
        this.rewriter = rewriter;
        this.queryRewriteEnabled = queryRewriteEnabled;
    }

    public RagAnswer answer(
            Long knowledgeBaseId, String question, RetrievalStrategy strategy, RetrievalParameters parameters) {
        return answer(knowledgeBaseId, question, strategy, parameters, List.of());
    }

    public RagAnswer answer(
            Long knowledgeBaseId,
            String question,
            RetrievalStrategy strategy,
            RetrievalParameters parameters,
            List<HistoryTurn> history) {
        Observation observation = new Observation();
        long started = System.nanoTime();
        RagAnswer outcome = null;
        try {
            outcome = execute(knowledgeBaseId, question, strategy, parameters, history, observation);
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

    /** 评估专用入口：复用同次执行，未到达的阶段保持 null，不补发检索或模型请求。 */
    public RagExecutionResult executeWithTrace(
            Long knowledgeBaseId, String question, RetrievalStrategy strategy, RetrievalParameters parameters) {
        return executeWithTrace(knowledgeBaseId, question, strategy, parameters, result -> {});
    }

    /** 检索完成后、构建上下文及生成前执行局部校验；普通问答不安装校验。 */
    public RagExecutionResult executeWithTrace(
            Long knowledgeBaseId,
            String question,
            RetrievalStrategy strategy,
            RetrievalParameters parameters,
            java.util.function.Consumer<RetrievalResult> retrievalCheck) {
        Observation observation = new Observation();
        observation.retrievalCheck = retrievalCheck;
        try {
            RagAnswer answer = execute(knowledgeBaseId, question, strategy, parameters, List.of(), observation);
            return new RagExecutionResult(observation.retrieval, observation.context, answer, null);
        } catch (RuntimeException failure) {
            // 只记录异常类型，不把供应商异常中的凭据或敏感原文带入评估产物。
            return new RagExecutionResult(
                    observation.retrieval,
                    observation.context,
                    null,
                    failure.getClass().getSimpleName());
        }
    }

    public record RagExecutionResult(
            RetrievalResult retrieval, AssembledContext context, RagAnswer answer, String failureType) {}

    private static final class Observation {
        private java.util.function.Consumer<RetrievalResult> retrievalCheck = result -> {};
        private int selectedCount;
        private RetrievalResult retrieval;
        private AssembledContext context;
    }

    public static void validateQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
    }

    private RagAnswer execute(
            Long knowledgeBaseId,
            String question,
            RetrievalStrategy strategy,
            RetrievalParameters parameters,
            List<HistoryTurn> history,
            Observation observation) {
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        validateQuestion(question);
        RetrievalStrategy requested = strategy == null ? RetrievalStrategy.HYBRID_RRF : strategy;
        RetrievalParameters resolved = parameters == null ? RetrievalParameters.defaults() : parameters;
        try {
            resolved.validate(requested);
        } catch (IllegalArgumentException failure) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        // 改写结果只用于检索，生成仍接收原始问题和会话历史。
        String retrievalQuery = retrievalQuery(question, history);
        // timed 在进入检索前检查取消，包括改写刚完成时收到的取消。
        RetrievalResult result = timed("RETRIEVAL", () -> retrieval
                .search(knowledgeBaseId, retrievalQuery, List.of(requested), resolved)
                .getFirst());
        observation.retrieval = result;
        observation.retrievalCheck.accept(result);
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
        observation.context = context;
        observation.selectedCount = context.selectedCount();
        if (context.selectedCount() == 0)
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CONTEXT_BUDGET_EXCEEDED, null);
        RawGenerationResult raw;
        try {
            raw = timed(
                    "GENERATION",
                    () -> history.isEmpty()
                            ? generator.generate(question, context)
                            : generator.generate(question, context, history));
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

    private String retrievalQuery(String question, List<HistoryTurn> history) {
        QueryRewriter.Status status = QueryRewriter.Status.SKIPPED;
        String query = question;
        long elapsedMillis = 0;
        if (queryRewriteEnabled && !history.isEmpty()) {
            long started = System.nanoTime();
            QueryRewriter.Result result = rewriter.rewrite(question, history);
            elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            status = result.status();
            if (status == QueryRewriter.Status.REWRITTEN) query = result.retrievalQuery();
        }
        try {
            log.info(
                    "stage=QUERY_REWRITE enabled={} status={} elapsedMillis={} originalLength={} retrievalLength={}",
                    queryRewriteEnabled,
                    status,
                    elapsedMillis,
                    question.length(),
                    query.length());
        } catch (RuntimeException loggingFailure) {
            // 日志后端故障不影响检索。
        }
        return query;
    }

    private <T> T timed(String stage, Supplier<T> action) {
        ModelCallCancellation.checkpoint();
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
