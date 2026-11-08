package com.lnjecit.knowledge.assistant.ingestion.parser;

public final class ParseException extends RuntimeException {
    public enum Kind {
        INVALID_UTF8,
        UNSUPPORTED_FORMAT,
        NO_CONTENT,
        UNRELIABLE_MAPPING
    }

    private final Kind kind;

    public ParseException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
