package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 信任同次 M2 查询已验证的发布快照候选，不将普通 ID 检查当成发布证明。 */
public final class CitationValidator {
    public List<RagCitation> validate(
            StructuredOutputParser.ParsedOutput output, Long kb, AssembledContext context, RetrievalResult retrieval) {
        List<RagCitation> citations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (retrieval.diagnostics().status() != RetrievalStatus.SUCCESS) throw new InvalidCitationException();
        // RetrievalResult 本身不是发布证明：调用者必须传入正式 M2 服务在同次查询中验证的结果。
        // 同时比对完整候选身份及来源，拒绝 Context 中混入其他请求或未入选的证据。
        for (String id : output.citations()) {
            SelectedEvidence e = context.evidenceMap().get(id);
            if (!id.matches("E[1-9][0-9]*")
                    || !seen.add(id)
                    || e == null
                    || !id.equals(e.evidenceId())
                    || !context.selectedEvidence().contains(e)
                    || !Objects.equals(kb, e.knowledgeBaseId())
                    || !positive(e.chunkId())
                    || !positive(e.documentId())
                    || !positive(e.indexVersionId())
                    || e.sourceStart() < 0
                    || e.sourceEnd() <= e.sourceStart()
                    || retrieval.chunks().stream()
                            .noneMatch(c -> Objects.equals(c.chunkId(), e.chunkId())
                                    && Objects.equals(c.knowledgeBaseId(), e.knowledgeBaseId())
                                    && Objects.equals(c.documentId(), e.documentId())
                                    && Objects.equals(c.indexVersionId(), e.indexVersionId())
                                    && c.sourceStart() == e.sourceStart()
                                    && c.sourceEnd() == e.sourceEnd()
                                    && Objects.equals(c.content(), e.content())
                                    && Objects.equals(c.headingPath(), e.headingPath())))
                throw new InvalidCitationException();
            citations.add(new RagCitation(
                    id, e.chunkId(), e.documentId(), e.indexVersionId(), e.sourceStart(), e.sourceEnd()));
        }
        return List.copyOf(citations);
    }

    private boolean positive(Long id) {
        return id != null && id > 0;
    }

    public static final class InvalidCitationException extends RuntimeException {
        public InvalidCitationException() {
            super("CITATION_INVALID");
        }
    }
}
