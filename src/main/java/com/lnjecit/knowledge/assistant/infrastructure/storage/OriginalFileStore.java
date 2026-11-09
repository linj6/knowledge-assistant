package com.lnjecit.knowledge.assistant.infrastructure.storage;

public interface OriginalFileStore {
    record StoredObject(String key, java.time.Instant lastModified) {}

    record ObjectPage(java.util.List<StoredObject> objects, String nextToken) {}

    default ObjectPage listOriginals(String token, int limit) {
        throw new UnsupportedOperationException("Listing unavailable");
    }

    void put(String key, byte[] bytes);

    byte[] read(String key);

    void delete(String key);
}
