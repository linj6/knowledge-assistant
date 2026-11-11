package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ContextBuilder {
    private final int budget;
    private final int maxCharacters;
    private final TokenEstimator estimator;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    public ContextBuilder(ContextProperties properties) {
        this(properties, new TokenEstimator(0));
    }

    public ContextBuilder(ContextProperties properties, TokenEstimator estimator) {
        budget = properties.getEstimatedTokenBudget();
        maxCharacters = properties.getMaxCharacters();
        this.estimator = Objects.requireNonNull(estimator);
    }

    public static final class InconsistentEvidenceException extends RuntimeException {
        public InconsistentEvidenceException() {
            super("CONTEXT_DATA_INCONSISTENT");
        }
    }

    private SelectedEvidence evidence(String id, RankedChunk c) {
        return new SelectedEvidence(
                id,
                c.chunkId(),
                c.knowledgeBaseId(),
                c.documentId(),
                c.indexVersionId(),
                c.content(),
                c.sourceStart(),
                c.sourceEnd(),
                c.headingPath(),
                c.finalRank());
    }

    public AssembledContext build(List<RankedChunk> candidates) {
        List<RankedChunk> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(RankedChunk::finalRank, Comparator.nullsLast(Comparator.naturalOrder())));
        Map<Long, RankedChunk> unique = new LinkedHashMap<>();
        // 先核验所有重复身份，避免预算排除掩盖数据冲突；排名不属于身份。
        for (RankedChunk c : ordered) {
            RankedChunk old = unique.putIfAbsent(c.chunkId(), c);
            if (old != null
                    && (!old.knowledgeBaseId().equals(c.knowledgeBaseId())
                            || !old.documentId().equals(c.documentId())
                            || !old.indexVersionId().equals(c.indexVersionId())
                            || !old.content().equals(c.content())
                            || old.sourceStart() != c.sourceStart()
                            || old.sourceEnd() != c.sourceEnd()
                            || !Objects.equals(old.headingPath(), c.headingPath())))
                throw new InconsistentEvidenceException();
        }
        List<SelectedEvidence> selected = new ArrayList<>();
        Map<String, SelectedEvidence> mapping = new LinkedHashMap<>();
        String context = "[]";
        int excluded = 0;
        // 正文仅作为 JSON 字符串。完整序列化后计量，超预算继续尝试后续完整 Chunk。
        for (RankedChunk c : unique.values()) {
            SelectedEvidence next = evidence("E" + (selected.size() + 1), c);
            List<SelectedEvidence> trial = new ArrayList<>(selected);
            trial.add(next);
            String serialized = mapper.writeValueAsString(trial);
            if (serialized.length() > maxCharacters
                    || estimator.estimate(serialized).estimatedTokens() > budget) {
                excluded++;
                continue;
            }
            selected.add(next);
            mapping.put(next.evidenceId(), next);
            context = serialized;
        }
        return new AssembledContext(
                context, selected, mapping, estimator.estimate(context), budget, maxCharacters, excluded);
    }
}
