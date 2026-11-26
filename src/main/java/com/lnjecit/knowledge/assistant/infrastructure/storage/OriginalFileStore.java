package com.lnjecit.knowledge.assistant.infrastructure.storage;

public interface OriginalFileStore {
    class MissingObjectException extends IllegalStateException {}

    void copy(String sourceKey, String targetKey);

    void put(String key, byte[] bytes);

    byte[] read(String key);

    void delete(String key);
}
