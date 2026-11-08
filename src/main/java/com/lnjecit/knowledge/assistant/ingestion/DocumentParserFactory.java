package com.lnjecit.knowledge.assistant.ingestion;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DocumentParserFactory {
    private final Map<String, DocumentParser> parsers;

    public DocumentParserFactory() {
        this(List.of(new TxtDocumentParser(), new MarkdownDocumentParser()));
    }

    public DocumentParserFactory(List<DocumentParser> candidates) {
        Map<String, DocumentParser> selected = new HashMap<>();
        for (DocumentParser parser : candidates) {
            String extension = parser.extension().toLowerCase(Locale.ROOT);
            if (!List.of("txt", "md").contains(extension) || selected.putIfAbsent(extension, parser) != null)
                throw new IllegalArgumentException("Unsupported or duplicate parser extension");
        }
        parsers = Map.copyOf(selected);
    }

    public DocumentParser select(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        DocumentParser parser = parsers.get(name.substring(name.lastIndexOf('.') + 1));
        if (name.lastIndexOf('.') < 0 || parser == null)
            throw new ParseException(ParseException.Kind.UNSUPPORTED_FORMAT);
        return parser;
    }

    public ParsedDocument parse(String filename, byte[] bytes) {
        return select(filename).parse(bytes);
    }
}
