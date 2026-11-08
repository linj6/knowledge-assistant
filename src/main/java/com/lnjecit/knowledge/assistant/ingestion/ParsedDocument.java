package com.lnjecit.knowledge.assistant.ingestion;

import java.util.List;

/** 每个片段均保留连续原文，不拼接不连续区间，也不清理正文标记。 */
public record ParsedDocument(String source, List<Fragment> fragments) {
    public record Heading(String text, int level, SourceSpan span) {}

    public record Fragment(SourceSpan span, List<Heading> headingPath) {
        public Fragment {
            headingPath = List.copyOf(headingPath);
        }
    }

    public ParsedDocument {
        fragments = List.copyOf(fragments);
        int cursor = 0;
        for (Fragment fragment : fragments) {
            if (fragment.span().start() != cursor) throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
            fragment.span().extract(source);
            for (Heading heading : fragment.headingPath()) {
                if (!heading.span().extract(source).equals(heading.text()))
                    throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
            }
            cursor = fragment.span().end();
        }
        if (cursor != source.codePointCount(0, source.length()) || cursor == 0)
            throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
    }
}
