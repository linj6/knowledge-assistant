package com.lnjecit.knowledge.assistant.ingestion.parser;

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
}
