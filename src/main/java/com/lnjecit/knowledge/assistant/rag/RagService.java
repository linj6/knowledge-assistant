package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RagService {
    public static final int MAX_QUESTION_LENGTH = 4000;
    private final RetrievalService retrieval;
    private final ContextBuilder contexts;

    public RagService(RetrievalService retrieval, ContextBuilder contexts) {
        this.retrieval = retrieval;
        this.contexts = contexts;
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
        RagStatus status;
        RagError error;
        switch (diagnostics.status()) {
            case EMPTY_RESULT -> {
                status = RagStatus.INSUFFICIENT_EVIDENCE;
                error = null;
            }
            case FAILED -> {
                status = RagStatus.FAILED;
                error = RagError.RETRIEVAL_FAILED;
            }
            case SUCCESS -> {
                // 后续接入 AnswerGenerator、CitationValidator，本次只构建证据。
                // 只有基于实际上下文完成生成和引用校验后，才允许构造 ANSWERED。
                status = RagStatus.FAILED;
                try {
                    AssembledContext context = contexts.build(result.chunks());
                    error = context.selectedCount() == 0
                            ? RagError.CONTEXT_BUDGET_EXCEEDED
                            : RagError.GENERATION_NOT_IMPLEMENTED;
                } catch (ContextBuilder.InconsistentEvidenceException failure) {
                    error = RagError.CONTEXT_DATA_INCONSISTENT;
                } catch (RuntimeException failure) {
                    // 构建故障仅返回固定分类，不回显可能携带正文的异常消息。
                    error = RagError.CONTEXT_BUILD_FAILED;
                }
            }
            default -> throw new IllegalStateException("RAG_RETRIEVAL_STATUS_INVALID");
        }
        // 问答接口仅保留必要诊断，不回传候选正文或模型原始输出。
        return new RagAnswer(
                status,
                null,
                List.of(),
                diagnostics.requestedStrategy(),
                diagnostics.actualStrategy(),
                diagnostics.degraded(),
                error,
                new RagAnswer.Diagnostics(diagnostics.status(), diagnostics.error(), diagnostics.snapshotHash()));
    }
}
