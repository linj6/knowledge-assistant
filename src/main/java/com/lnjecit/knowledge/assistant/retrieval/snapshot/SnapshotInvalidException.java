package com.lnjecit.knowledge.assistant.retrieval.snapshot;

/** 发布关系损坏，区别于数据库读取故障；不携带原始数据。 */
public class SnapshotInvalidException extends IllegalStateException {
    public SnapshotInvalidException() {
        super("SNAPSHOT_INVALID");
    }
}
