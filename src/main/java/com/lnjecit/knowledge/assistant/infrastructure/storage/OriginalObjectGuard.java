package com.lnjecit.knowledge.assistant.infrastructure.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/** 仅在本应用内保护上传至PG提交的窗口，不提供跨进程互斥。 */
@Component
public class OriginalObjectGuard {
    private final Map<String, Entry> entries = new HashMap<>();

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    public final class Handle implements AutoCloseable {
        private final String key;
        private final Entry entry;
        private boolean closed;

        private Handle(String key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        public void close() {
            if (!closed) {
                closed = true;
                entry.lock.unlock();
                release(key, entry);
            }
        }
    }

    // 等待者也持有引用，确保解锁与删除锁条目之间不会产生两个同Key的锁。
    private synchronized Entry retain(String key) {
        Entry entry = entries.computeIfAbsent(key, k -> new Entry());
        entry.users++;
        return entry;
    }

    private synchronized void release(String key, Entry entry) {
        if (--entry.users == 0) entries.remove(key, entry);
    }

    public Handle acquire(String key) {
        Entry entry = retain(key);
        entry.lock.lock();
        return new Handle(key, entry);
    }

    public Handle tryAcquire(String key) {
        Entry entry = retain(key);
        if (entry.lock.tryLock()) return new Handle(key, entry);
        release(key, entry);
        return null;
    }
}
