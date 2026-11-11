package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.util.List;
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
        RetrievalResult result = retrieval
                .search(knowledgeBaseId, question, List.of(requested), resolved)
                .getFirst();
        RetrievalDiagnostics diagnostics = result.diagnostics();
        if (diagnostics.status() == RetrievalStatus.EMPTY_RESULT)
            return response(result, RagStatus.INSUFFICIENT_EVIDENCE, null, List.of(), null, null);
        if (diagnostics.status() == RetrievalStatus.FAILED)
            return response(result, RagStatus.FAILED, null, List.of(), RagError.RETRIEVAL_FAILED, null);

        // 同次请求只构建一次上下文，后续校验沿用这一份证据映射。
        AssembledContext context;
        try {
            context = contexts.build(result.chunks());
        } catch (ContextBuilder.InconsistentEvidenceException failure) {
            logContextFailure(RagError.CONTEXT_DATA_INCONSISTENT, failure);
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CONTEXT_DATA_INCONSISTENT, null);
        } catch (RuntimeException failure) {
            logContextFailure(RagError.CONTEXT_BUILD_FAILED, failure);
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CONTEXT_BUILD_FAILED, null);
        }
        if (context.selectedCount() == 0)
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CONTEXT_BUDGET_EXCEEDED, null);
        RawGenerationResult raw;
        try {
            raw = generator.generate(question, context);
        } catch (AnswerGenerator.GenerationException failure) {
            return response(result, RagStatus.FAILED, null, List.of(), failure.error(), null);
        }
        try {
            StructuredOutputParser.ParsedOutput parsed = parser.parse(raw);
            List<RagCitation> citations = validator.validate(parsed, knowledgeBaseId, context, result);
            return response(
                    result,
                    parsed.answerable() ? RagStatus.ANSWERED : RagStatus.INSUFFICIENT_EVIDENCE,
                    parsed.answer(),
                    citations,
                    null,
                    raw.usage());
        } catch (StructuredOutputParser.InvalidOutputException failure) {
            FailureLog.rejected(log, "RAG_OUTPUT", "OUTPUT_INVALID");
            return response(
                    result,
                    RagStatus.FAILED,
                    null,
                    List.of(),
                    RagError.OUTPUT_INVALID,
                    raw == null ? null : raw.usage());
        } catch (CitationValidator.InvalidCitationException failure) {
            FailureLog.rejected(log, "RAG_CITATION", "CITATION_INVALID");
            return response(result, RagStatus.FAILED, null, List.of(), RagError.CITATION_INVALID, raw.usage());
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
        // 序列化异常可能在消息或原因链中携带正文，仅保留固定分类和故障位置。
        RuntimeException diagnostic = new RuntimeException(error.name());
        diagnostic.setStackTrace(failure.getStackTrace());
        FailureLog.error(log, "RAG_CONTEXT", diagnostic);
    }
}
