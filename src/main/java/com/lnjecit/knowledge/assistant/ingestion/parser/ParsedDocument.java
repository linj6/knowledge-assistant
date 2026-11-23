package com.lnjecit.knowledge.assistant.ingestion.parser;

import com.lnjecit.knowledge.assistant.ingestion.text.UnicodeOffsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        int[] boundaries = UnicodeOffsets.boundaries(source);
        Set<Heading> checked = new HashSet<>();
        int cursor = 0;
        for (Fragment fragment : fragments) {
            if (fragment.span().start() != cursor) throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
            if (fragment.span().end() >= boundaries.length)
                throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
            for (Heading heading : fragment.headingPath()) {
                if (checked.add(heading)
                        && (heading.span().end() >= boundaries.length
                                || !source.substring(
                                                boundaries[heading.span().start()],
                                                boundaries[heading.span().end()])
                                        .equals(heading.text())))
                    throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
            }
            cursor = fragment.span().end();
        }
        if (cursor != boundaries.length - 1 || cursor == 0)
            throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
    }
}
