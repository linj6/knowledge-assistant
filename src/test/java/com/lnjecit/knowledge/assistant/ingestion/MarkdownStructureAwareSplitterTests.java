package com.lnjecit.knowledge.assistant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy;
import com.lnjecit.knowledge.assistant.ingestion.chunk.DocumentSplitter.Chunk;
import com.lnjecit.knowledge.assistant.ingestion.chunk.MarkdownStructureAwareSplitter;
import com.lnjecit.knowledge.assistant.ingestion.parser.MarkdownDocumentParser;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.TxtDocumentParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownStructureAwareSplitterTests {
    private final MarkdownStructureAwareSplitter splitter = new MarkdownStructureAwareSplitter();

    @Test
    void preservesCompleteBlocksAndHeadingScopes() {
        String fence = "```java\r\n一行\r\n\r\n二行\r\n```\r\n";
        String table = "|A|B|\r\n|---|---|\r\n|一|😀|\r\n";
        String source = "\uFEFF# 一级\r\n\r\n第一段。\r\n\r\n" + fence + "\r\n" + table + "\r\n## 二级\r\n正文";
        ParsedDocument document = new MarkdownDocumentParser().parse(source.getBytes(StandardCharsets.UTF_8));
        ChunkingConfig config = config(36, 5);
        List<Chunk> chunks = splitter.split(document, config);
        assertThat(chunks).isEqualTo(splitter.split(document, config));
        assertThat(chunks)
                .anySatisfy(chunk -> {
                    assertThat(chunk.content()).contains(fence);
                    assertThat(chunk.headingPath())
                            .extracting(ParsedDocument.Heading::text)
                            .containsExactly("一级");
                })
                .anySatisfy(chunk -> assertThat(chunk.content()).contains(table))
                .anySatisfy(chunk -> {
                    assertThat(chunk.content()).contains("第一段。");
                    assertThat(chunk.headingPath())
                            .extracting(ParsedDocument.Heading::text)
                            .containsExactly("一级");
                });
        assertThat(chunks.getLast().headingPath())
                .extracting(ParsedDocument.Heading::text)
                .containsExactly("一级", "二级");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            assertThat(chunk.chunkOrder()).isEqualTo(i);
            assertThat(chunk.content()).isEqualTo(chunk.span().extract(source));
            assertThat(chunk.span().end() - chunk.span().start()).isLessThanOrEqualTo(36);
        }
    }

    @Test
    void splitsLongLinesWithOverlapAndSupportsSingleCodePointTxtChunks() {
        ParsedDocument markdown = new MarkdownDocumentParser()
                .parse(("```\n" + "😀".repeat(12) + "\n```").getBytes(StandardCharsets.UTF_8));
        List<Chunk> chunks = splitter.split(markdown, config(6, 2));
        assertThat(chunks.get(0).span().end() - chunks.get(1).span().start()).isEqualTo(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).isEqualTo(chunk.span().extract(markdown.source()));
            assertThat(chunk.span().end() - chunk.span().start()).isLessThanOrEqualTo(6);
        });

        ParsedDocument txt = new TxtDocumentParser().parse("😀字".getBytes(StandardCharsets.UTF_8));
        assertThat(splitter.split(txt, config(1, 0))).extracting(Chunk::content).containsExactly("😀", "字");
    }

    private static ChunkingConfig config(int size, int overlap) {
        return new ChunkingConfig(2, ChunkingStrategy.MARKDOWN_STRUCTURE_AWARE, size, overlap);
    }
}
