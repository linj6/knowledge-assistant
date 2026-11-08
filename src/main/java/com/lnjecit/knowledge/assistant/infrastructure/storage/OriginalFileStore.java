package com.lnjecit.knowledge.assistant.infrastructure.storage;

public interface OriginalFileStore {
    void put(String key, byte[] bytes);

    byte[] read(String key);

    void delete(String key);
}
