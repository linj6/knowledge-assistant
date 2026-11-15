package com.lnjecit.knowledge.assistant.ingestion.chunk;

import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.SourceSpan;
import com.lnjecit.knowledge.assistant.ingestion.text.UnicodeOffsets;
import java.util.ArrayList;
import java.util.List;

public final class FixedCodePointSplitter implements DocumentSplitter {
    public List<Chunk> split(ParsedDocument document, ChunkingConfig config) {
        if ((config.version() != 1 && config.version() != 2)
                || config.strategy() != ChunkingStrategy.FIXED_CODE_POINT) {
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
            String content = source.substring(boundaries[start], boundaries[end]);
            // v1 保留历史窗口身份；v2 跳过空白窗口，保留原文偏移并连续编号。
            if (config.version() == 1 || !content.isBlank())
                chunks.add(new Chunk(
                        chunks.size(),
                        content,
                        new SourceSpan(start, end),
                        document.fragments().get(fragment).headingPath()));
            if (end == length) break;
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("Document contains no non-blank chunks");
        return List.copyOf(chunks);
    }
}
