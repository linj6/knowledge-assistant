package com.lnjecit.knowledge.assistant.ingestion;

/** 原文 Unicode 码点坐标，end 为独占边界。 */
public record SourceSpan(int start, int end) {
    public SourceSpan {
        if (start < 0 || end <= start) {
            throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
        }
    }

    public String extract(String source) {
        if (end > source.codePointCount(0, source.length())) {
            throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
        }
        return source.substring(source.offsetByCodePoints(0, start), source.offsetByCodePoints(0, end));
    }
}
