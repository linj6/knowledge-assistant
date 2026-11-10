package com.lnjecit.knowledge.assistant.infrastructure.persistence;

/** 当前 Flyway vector 列的物理维度；修改配置不能改变该约束。 */
public final class VectorSchema {
    public static final int DIMENSION = 1024;

    private VectorSchema() {}

    public static void requireCompatible(Integer dimension) {
        if (!Integer.valueOf(DIMENSION).equals(dimension))
            throw new IllegalStateException("Embedding snapshot incompatible with vector schema");
    }
}
