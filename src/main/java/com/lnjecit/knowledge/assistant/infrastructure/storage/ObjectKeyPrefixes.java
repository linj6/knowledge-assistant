package com.lnjecit.knowledge.assistant.infrastructure.storage;

/** 原始文件对象前缀；保持已有 Bucket 和对象 Key 兼容。 */
public final class ObjectKeyPrefixes {
    public static final String STAGING = "staging/";
    public static final String ORIGINALS = "originals/";

    private ObjectKeyPrefixes() {}
}
