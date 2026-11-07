package com.lnjecit.knowledge.assistant.infrastructure.ai;

import java.util.HashSet;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.json.JsonMapper;

public final class ModelResponseChecks {
    private ModelResponseChecks() {}

    public static void json(String text) {
        try {
            var node = JsonMapper.builder().build().readTree(text);
            if (!node.isObject()
                    || !node.path("answer").isString()
                    || !node.path("citations").isArray()) {
                throw new IllegalArgumentException();
            }
            for (var citation : node.path("citations")) {
                if (!citation.isString()) throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid structured model response");
        }
    }

    public static int embeddings(EmbeddingResponse response, int count, Integer expectedDimensions) {
        if (response.getResults().size() != count || count < 1) throw invalid();
        var indices = new HashSet<Integer>();
        int dimensions = response.getResults().getFirst().getOutput().length;
        if (dimensions == 0 || dimensions > 16000 || (expectedDimensions != null && dimensions != expectedDimensions))
            throw invalid();
        // 按供应商 index 关联输入，不能依赖响应数组排列。
        for (var result : response.getResults()) {
            if (result.getIndex() < 0
                    || result.getIndex() >= count
                    || !indices.add(result.getIndex())
                    || result.getOutput().length != dimensions) throw invalid();
            for (float value : result.getOutput()) if (!Float.isFinite(value)) throw invalid();
        }
        return dimensions;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Invalid embedding response");
    }
}
