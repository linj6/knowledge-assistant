package com.lnjecit.knowledge.assistant.ingestion.chunk;

import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.SourceSpan;
import java.util.List;

public interface DocumentSplitter {
    List<Chunk> split(ParsedDocument document, ChunkingConfig config);

    record Chunk(int chunkOrder, String content, SourceSpan span, List<ParsedDocument.Heading> headingPath) {
        public Chunk {
            headingPath = List.copyOf(headingPath);
        }
    }
}
