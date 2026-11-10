package com.lnjecit.knowledge.assistant.retrieval;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.VectorSchema;
import java.util.Arrays;

/** 已校验的查询向量；防御性复制允许同请求安全复用。 */
public record QueryVector(float[] values) {
    public QueryVector {
        if (values == null || values.length != VectorSchema.DIMENSION)
            throw new IllegalArgumentException("QUERY_VECTOR_DIMENSION");
        boolean nonzero = false;
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("QUERY_VECTOR_INVALID");
            nonzero |= value != 0;
        }
        if (!nonzero) throw new IllegalArgumentException("QUERY_VECTOR_ZERO");
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    public String binding() {
        return Arrays.toString(values);
    }
}
