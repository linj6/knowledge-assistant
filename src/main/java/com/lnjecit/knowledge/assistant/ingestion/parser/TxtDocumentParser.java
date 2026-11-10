package com.lnjecit.knowledge.assistant.ingestion.parser;

import java.util.List;

public final class TxtDocumentParser implements DocumentParser {
    public String extension() {
        return "txt";
    }

    public ParsedDocument parse(byte[] bytes) {
        String source = ParserSource.decode(bytes);
        return new ParsedDocument(
                source,
                List.of(new ParsedDocument.Fragment(
                        new SourceSpan(0, source.codePointCount(0, source.length())), List.of())));
    }
}
