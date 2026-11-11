package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RagService {
    public static final int MAX_QUESTION_LENGTH = 4000;
    private final RetrievalService retrieval;

    public RagService(RetrievalService retrieval) {
        this.retrieval = retrieval;
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
        RetrievalDiagnostics diagnostics = retrieval
                .search(knowledgeBaseId, question, List.of(requested), resolved)
                .getFirst()
                .diagnostics();
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
                // 后续在此依次接入 ContextBuilder、AnswerGenerator、CitationValidator。
                // 只有基于实际上下文完成生成和引用校验后，才允许构造 ANSWERED。
                status = RagStatus.FAILED;
                error = RagError.GENERATION_NOT_IMPLEMENTED;
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
