package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.evaluation.EvaluationCorpus;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy;
import com.lnjecit.knowledge.assistant.ingestion.chunk.DocumentSplitter;
import com.lnjecit.knowledge.assistant.ingestion.chunk.FixedCodePointSplitter;
import com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 离线入口：输入为每行一个 Markdown 路径的清单，不加载 Spring。 */
class ChunkingBaselineTests {
    @Test
    void evaluateLocalMarkdown() throws Exception {
        String manifest = System.getProperty("chunking.manifest");
        org.junit.jupiter.api.Assumptions.assumeTrue(manifest != null, "指定 chunking.manifest 才读取本地样本");
        Path output = Path.of(System.getProperty("chunking.output", "target/chunking-baseline"));
        Files.createDirectories(output);
        StringBuilder summary = new StringBuilder(
                "path\tsha256\tcodePoints\theadings\tchunks\tmin\tmax\taverage\tshortBelow200\tcrossHeading\n");
        int sample = 0;
        for (String input : Files.readAllLines(Path.of(manifest))) {
            if (input.isBlank()) continue;
            Path path = EvaluationCorpus.resolve(input);
            byte[] bytes = Files.readAllBytes(path);
            ParsedDocument parsed = new DocumentParserFactory().parse(path.toString(), bytes);
            List<DocumentSplitter.Chunk> chunks = new FixedCodePointSplitter()
                    .split(parsed, new ChunkingConfig(1, ChunkingStrategy.FIXED_CODE_POINT, 800, 120));
            StringBuilder details =
                    new StringBuilder("FIXED_CODE_POINT v1 800/120; ranges=[start,end); short<200 CP\n");
            int total = 0;
            int min = 800;
            int shortCount = 0;
            int cross = 0;
            int previousEnd = 0;
            for (DocumentSplitter.Chunk chunk : chunks) {
                int length = chunk.span().end() - chunk.span().start();
                total += length;
                min = Math.min(min, length);
                if (length < 200) shortCount++;
                // 跨标题只标候选，不自动判定语义缺陷。
                boolean crossing = parsed.fragments().stream()
                        .anyMatch(f -> f.span().start() < chunk.span().end()
                                && f.span().end() > chunk.span().start()
                                && !f.headingPath().equals(chunk.headingPath()));
                if (crossing) cross++;
                assertThat(chunk.content()).isEqualTo(chunk.span().extract(parsed.source()));
                assertThat(chunk.span().start()).isEqualTo(chunk.chunkOrder() * 680);
                if (chunk.chunkOrder() > 0)
                    assertThat(previousEnd - chunk.span().start()).isEqualTo(120);
                previousEnd = chunk.span().end();
                details.append("\nCHUNK ")
                        .append(chunk.chunkOrder())
                        .append(" ")
                        .append(chunk.span())
                        .append(" heading=")
                        .append(chunk.headingPath())
                        .append(" Potential-crossHeading=")
                        .append(crossing)
                        .append("\n")
                        .append(chunk.content())
                        .append("\nEND CHUNK\n");
            }
            int sourceLength = parsed.source().codePointCount(0, parsed.source().length());
            assertThat(previousEnd).isEqualTo(sourceLength);
            long headings = parsed.fragments().stream()
                    .flatMap(f -> f.headingPath().stream())
                    .distinct()
                    .count();
            summary.append(input)
                    .append('\t')
                    .append(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .append('\t')
                    .append(sourceLength)
                    .append('\t')
                    .append(headings)
                    .append('\t')
                    .append(chunks.size())
                    .append('\t')
                    .append(min)
                    .append('\t')
                    .append(chunks.stream()
                            .mapToInt(c -> c.span().end() - c.span().start())
                            .max()
                            .orElse(0))
                    .append('\t')
                    .append((double) total / chunks.size())
                    .append('\t')
                    .append(shortCount)
                    .append('\t')
                    .append(cross)
                    .append('\n');
            Files.writeString(output.resolve("sample-" + (++sample) + ".txt"), details);
        }
        assertThat(sample).isPositive();
        Files.writeString(output.resolve("summary.tsv"), summary);
    }
}
