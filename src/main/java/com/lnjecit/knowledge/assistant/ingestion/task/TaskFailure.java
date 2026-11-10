package com.lnjecit.knowledge.assistant.ingestion.task;

public class TaskFailure extends RuntimeException {
    public enum Code {
        NOT_FOUND,
        NOT_CLAIMABLE,
        OWNERSHIP_INVALID,
        ALREADY_CLAIMED,
        TRANSITION_CONFLICT,
        EXECUTION_FAILED,
        AUTHORITY_EXPIRED
    }

    private final Code code;

    public TaskFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
