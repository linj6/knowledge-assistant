package com.lnjecit.knowledge.assistant.ingestion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

final class ParserSource {
    private ParserSource() {}

    static String decode(byte[] bytes) {
        String source;
        try {
            source = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ParseException(ParseException.Kind.INVALID_UTF8);
        }
        if (source.codePoints().allMatch(cp -> cp == 0xFEFF || Character.isWhitespace(cp) || Character.isSpaceChar(cp)))
            throw new ParseException(ParseException.Kind.NO_CONTENT);
        return source;
    }
    // 一次顺序扫描建立码点边界表，后续范围校验不再从全文开头扫描。
    static int[] boundaries(String source) {
        int[] offsets = new int[source.codePointCount(0, source.length()) + 1];
        int character = 0;
        for (int point = 1; point < offsets.length; point++) {
            character += Character.charCount(source.codePointAt(character));
            offsets[point] = character;
        }
        return offsets;
    }
}
