package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.chunk.DocumentSplitter;
import com.lnjecit.knowledge.assistant.ingestion.chunk.FixedCodePointSplitter;
import com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentSplitterTests {
    @org.junit.jupiter.api.Test
    void skipsBlankWindowsWithoutMovingSourceOffsets() {
        String source = "a" + " ".repeat(2000) + "b";
        DocumentSplitter splitter = new FixedCodePointSplitter();
        ChunkingConfig config = new ChunkingConfig(
                1, com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy.FIXED_CODE_POINT, 800, 120);
        ParsedDocument parsed = new DocumentParserFactory().parse("a.txt", source.getBytes(StandardCharsets.UTF_8));
        java.util.List<DocumentSplitter.Chunk> chunks = splitter.split(parsed, config);
        assertThat(chunks).hasSize(2).allMatch(c -> !c.content().isBlank());
        assertThat(chunks).extracting(DocumentSplitter.Chunk::chunkOrder).containsExactly(0, 1);
        assertThat(chunks).extracting(c -> c.span().start()).containsExactly(0, 1360);
        assertThat(chunks).extracting(c -> c.span().end()).containsExactly(800, 2002);
        assertThat(chunks).allMatch(c -> c.content().equals(c.span().extract(source)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DocumentParserFactory()
                        .parse("a.txt", " ".repeat(2000).getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("NO_CONTENT");
    }

    @org.junit.jupiter.api.Test
    void headingJsonRoundTrip() {
        ParsedDocument parsed = new DocumentParserFactory()
                .parse("a.md", ("\uFEFF# 一级\r\n## 二级😀\n" + "正文".repeat(900)).getBytes(StandardCharsets.UTF_8));
        tools.jackson.databind.json.JsonMapper json =
                tools.jackson.databind.json.JsonMapper.builder().build();
        com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService.HeadingPath path =
                new com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService.HeadingPath(
                        1, parsed.fragments().get(2).headingPath());
        assertThat(path.headings()).hasSize(2);
        assertThat(json.readValue(
                        json.writeValueAsString(path),
                        com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService.HeadingPath.class))
                .isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 679, 680, 799, 800, 801, 1360, 1480, 1481, 200000})
    void preservesCodePointsAndDeterminism(int length) {
        String seed = "中\uFEFF😀𐐀\r\n# A\n## B\r\n```\n# fenced\n```\n";
        String source = seed.repeat(length / seed.codePointCount(0, seed.length()) + 1);
        source = source.substring(0, source.offsetByCodePoints(0, length));
        for (String extension : new String[] {"txt", "md"}) {
            ParsedDocument parsed =
                    new DocumentParserFactory().parse("a." + extension, source.getBytes(StandardCharsets.UTF_8));
            DocumentSplitter splitter = new FixedCodePointSplitter();
            ChunkingConfig config = new ChunkingConfig(
                    1, com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy.FIXED_CODE_POINT, 800, 120);
            java.util.List<DocumentSplitter.Chunk> chunks = splitter.split(parsed, config);
            assertThat(splitter.split(parsed, config)).isEqualTo(chunks);
            int previousEnd = 0;
            for (int i = 0; i < chunks.size(); i++) {
                DocumentSplitter.Chunk chunk = chunks.get(i);
                assertThat(chunk.chunkOrder()).isEqualTo(i);
                assertThat(chunk.span().start()).isEqualTo(i * 680);
                assertThat(chunk.span().end()).isEqualTo(Math.min(i * 680 + 800, length));
                assertThat(chunk.span().end()).isGreaterThan(previousEnd);
                assertThat(chunk.content()).isEqualTo(chunk.span().extract(source));
                assertThat(chunk.headingPath())
                        .isEqualTo(parsed.fragments().stream()
                                .filter(f -> f.span().start() <= chunk.span().start()
                                        && f.span().end() > chunk.span().start())
                                .findFirst()
                                .orElseThrow()
                                .headingPath());
                previousEnd = chunk.span().end();
            }
            assertThat(previousEnd).isEqualTo(length);
        }
    }
}
