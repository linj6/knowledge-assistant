package com.lnjecit.knowledge.assistant.ingestion;

import java.util.List;

public interface DocumentSplitter {
    List<Chunk> split(ParsedDocument document, ChunkingConfig config);

    record Chunk(int chunkOrder, String content, SourceSpan span, List<ParsedDocument.Heading> headingPath) {
        public Chunk {
            headingPath = List.copyOf(headingPath);
        }
    }
}
