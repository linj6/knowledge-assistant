package com.lnjecit.knowledge.assistant.ingestion.chunk;

import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.SourceSpan;
import com.lnjecit.knowledge.assistant.ingestion.text.UnicodeOffsets;
import java.util.ArrayList;
import java.util.List;

public final class FixedCodePointSplitter implements DocumentSplitter {
    public List<Chunk> split(ParsedDocument document, ChunkingConfig config) {
        if (config.version() != 1 || config.strategy() != ChunkingStrategy.FIXED_CODE_POINT) {
            throw new IllegalArgumentException("Unsupported chunking protocol");
        }
        String source = document.source();
        int[] boundaries = UnicodeOffsets.boundaries(source);
        int length = boundaries.length - 1;
        List<Chunk> chunks = new ArrayList<>();
        int fragment = 0;
        // 边界表只建一次，片段游标单向前进；最后窗口已覆盖全文时立即停止。
        for (int start = 0; start < length; start += config.chunkSize() - config.chunkOverlap()) {
            int end = start + Math.min(config.chunkSize(), length - start);
            while (document.fragments().get(fragment).span().end() <= start) fragment++;
            chunks.add(new Chunk(
                    chunks.size(),
                    source.substring(boundaries[start], boundaries[end]),
                    new SourceSpan(start, end),
                    document.fragments().get(fragment).headingPath()));
            if (end == length) break;
        }
        return List.copyOf(chunks);
    }
}
