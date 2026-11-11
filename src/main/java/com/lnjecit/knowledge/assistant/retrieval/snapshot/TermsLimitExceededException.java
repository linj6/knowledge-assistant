package com.lnjecit.knowledge.assistant.retrieval.snapshot;

public final class TermsLimitExceededException extends IllegalArgumentException {
    public TermsLimitExceededException() {
        super("TERMS_LIMIT_EXCEEDED");
    }
}
