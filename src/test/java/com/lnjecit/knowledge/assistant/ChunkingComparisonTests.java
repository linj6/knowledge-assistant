package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy;
import com.lnjecit.knowledge.assistant.ingestion.chunk.FixedCodePointSplitter;
import com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.SourceSpan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChunkingComparisonTests {
    record Evidence(SourceSpan span, String text) {}

    record Gold(
            String questionId,
            String documentPath,
            String sha256,
            String question,
            List<String> expectedAnswerPoints,
            List<Evidence> goldEvidence,
            String annotation) {}

    @Test
    void compareRealDocuments() throws Exception {
        String manifest = System.getProperty("chunking.comparisonManifest");
        org.junit.jupiter.api.Assumptions.assumeTrue(manifest != null, "离线样本需显式指定清单");
        Path output = Path.of(System.getProperty("chunking.comparisonOutput", "target/chunking-comparison"));
        Files.createDirectories(output);
        JsonMapper json = JsonMapper.builder().build();
        Gold[] gold =
                json.readValue(Files.readString(Path.of("docs/engineering/m4-01-gold-evidence.json")), Gold[].class);
        StringBuilder statistics = new StringBuilder(
                "sample\tstrategy\tsha256\tsourceLength\tcount\taverage\tp50\tp90\tmin\tmax\tshort\tshortRatio\toversize\ttotal\trepeatRatio\tfenceCuts\ttableCuts\titemCuts\tcrossHeading\tdegraded\n");
        StringBuilder evidence = new StringBuilder("question\tstrategy\tintervalCandidate\tcontainingChunks\n");
        int sample = 0;
        int checkedGold = 0;
        for (String input : Files.readAllLines(Path.of(manifest))) {
            if (input.isBlank()) continue;
            sample++;
            byte[] bytes = Files.readAllBytes(Path.of(input));
            String hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            ParsedDocument document = new DocumentParserFactory().parse(input, bytes);
            List<MarkdownStructureExperiment.Piece> baseline = new FixedCodePointSplitter()
                    .split(document, new ChunkingConfig(1, ChunkingStrategy.FIXED_CODE_POINT, 800, 120)).stream()
                            .map(c -> new MarkdownStructureExperiment.Piece(
                                    c.span(), c.content(), c.headingPath(), "FIXED_WINDOW", c.chunkOrder() > 0, ""))
                            .toList();
            List<MarkdownStructureExperiment.Piece> structured = MarkdownStructureExperiment.split(document);
            assertThat(structured).isEqualTo(MarkdownStructureExperiment.split(document));
            for (String strategy : List.of("FIXED_CODE_POINT", MarkdownStructureExperiment.STRATEGY)) {
                List<MarkdownStructureExperiment.Piece> pieces =
                        strategy.equals("FIXED_CODE_POINT") ? baseline : structured;
                verify(document, pieces, strategy.equals("FIXED_CODE_POINT"));
                Files.writeString(
                        output.resolve("sample-" + sample + "-" + strategy + ".json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(pieces));
                int[] lengths = pieces.stream()
                        .mapToInt(p -> p.span().end() - p.span().start())
                        .sorted()
                        .toArray();
                int total = java.util.Arrays.stream(lengths).sum();
                int sourceLength =
                        document.source().codePointCount(0, document.source().length());
                long shortCount =
                        java.util.Arrays.stream(lengths).filter(n -> n < 200).count();
                ParsedDocument scoped = MarkdownStructureExperiment.scopedDocument(document);
                long cross = pieces.stream()
                        .filter(p -> scoped.fragments().stream()
                                .anyMatch(f -> f.span().start() < p.span().end()
                                        && f.span().end() > p.span().start()
                                        && !f.headingPath().equals(p.headingPath())))
                        .count();
                statistics
                        .append(sample)
                        .append('\t')
                        .append(strategy)
                        .append('\t')
                        .append(hash)
                        .append('\t')
                        .append(sourceLength)
                        .append('\t')
                        .append(pieces.size())
                        .append('\t')
                        .append((double) total / pieces.size())
                        .append('\t')
                        .append(lengths[(int) Math.ceil(lengths.length * 0.5) - 1])
                        .append('\t')
                        .append(lengths[(int) Math.ceil(lengths.length * 0.9) - 1])
                        .append('\t')
                        .append(lengths[0])
                        .append('\t')
                        .append(lengths[lengths.length - 1])
                        .append('\t')
                        .append(shortCount)
                        .append('\t')
                        .append((double) shortCount / pieces.size())
                        .append('\t')
                        .append(java.util.Arrays.stream(lengths)
                                .filter(n -> n > 1200)
                                .count())
                        .append('\t')
                        .append(total)
                        .append('\t')
                        .append((double) (total - sourceLength) / sourceLength);
                for (String kind : List.of("FENCE", "TABLE", "LIST_ITEM"))
                    statistics.append('\t').append(cuts(document.source(), pieces, kind));
                statistics
                        .append('\t')
                        .append(cross)
                        .append('\t')
                        .append(pieces.stream()
                                .filter(p -> !p.reason().equals("WHOLE")
                                        && !p.reason().equals("FIXED_WINDOW"))
                                .count())
                        .append('\n');
                for (Gold question : gold) {
                    if (!question.documentPath().equals(input)) continue;
                    assertThat(hash).as(question.questionId() + " 版本").isEqualTo(question.sha256());
                    for (Evidence range : question.goldEvidence())
                        assertThat(range.span().extract(document.source())).isEqualTo(range.text());
                    List<Integer> containing = new ArrayList<>();
                    for (int i = 0; i < pieces.size(); i++) {
                        SourceSpan span = pieces.get(i).span();
                        if (question.goldEvidence().stream()
                                .allMatch(g -> span.start() <= g.span().start()
                                        && span.end() >= g.span().end())) containing.add(i);
                    }
                    // 这里只产生区间候选；COMPLETE/DISTRIBUTED 的语义结论在人工审查记录中维护。
                    evidence.append(question.questionId())
                            .append('\t')
                            .append(strategy)
                            .append('\t')
                            .append(containing.isEmpty() ? "MULTI_CHUNK" : "SINGLE_CHUNK")
                            .append('\t')
                            .append(containing)
                            .append('\n');
                    if (strategy.equals("FIXED_CODE_POINT")) checkedGold++;
                }
            }
        }
        assertThat(checkedGold).isEqualTo(gold.length);
        assertThat(sample).isPositive();
        Files.writeString(output.resolve("statistics.tsv"), statistics);
        Files.writeString(output.resolve("evidence-candidates.tsv"), evidence);
    }

    private static long cuts(String source, List<MarkdownStructureExperiment.Piece> pieces, String kind) {
        // 计数为“结构×Chunk边界”事件（去重同一坐标），并非语义缺陷数量。
        return MarkdownStructureExperiment.structures(source).stream()
                .filter(u -> u.kind().equals(kind))
                .mapToLong(u -> pieces.stream()
                        .flatMap(p -> List.of(p.span().start(), p.span().end()).stream())
                        .distinct()
                        .filter(b -> b > u.span().start() && b < u.span().end())
                        .count())
                .sum();
    }

    private static void verify(ParsedDocument document, List<MarkdownStructureExperiment.Piece> pieces, boolean fixed) {
        int covered = 0;
        int previousStart = -1;
        for (MarkdownStructureExperiment.Piece piece : pieces) {
            assertThat(piece.sourceContent()).isEqualTo(piece.span().extract(document.source()));
            assertThat(piece.span().start()).isGreaterThan(previousStart).isLessThanOrEqualTo(covered);
            if (piece.span().start() < covered) {
                assertThat(piece.overlap()).isTrue();
                assertThat(covered - piece.span().start()).isEqualTo(120);
                if (!fixed) assertThat(piece.reason()).isEqualTo("LONG_SENTENCE_WINDOW");
            } else if (!fixed) assertThat(piece.overlap()).isFalse();
            covered = Math.max(covered, piece.span().end());
            previousStart = piece.span().start();
        }
        assertThat(covered)
                .isEqualTo(document.source().codePointCount(0, document.source().length()));
    }

    @Test
    void protectsUnicodeScopesTablesAndLongUnits() {
        String source = "\uFEFF前言😀\r\n# A\r\n## B\r\n" + "```java\r\n" + "int value = 1; //😀\r\n".repeat(100)
                + "```\r\n\r\n" + "| A | B |\r\n| --- | --- |\r\n" + "| 甲😀 | 乙 |\r\n".repeat(130) + "\r\n## C\r\n"
                + "长😀".repeat(1500) + "\r\n";
        ParsedDocument document = new DocumentParserFactory().parse("test.md", source.getBytes(StandardCharsets.UTF_8));
        List<MarkdownStructureExperiment.Piece> pieces = MarkdownStructureExperiment.split(document);
        verify(document, pieces, false);
        assertThat(pieces).isEqualTo(MarkdownStructureExperiment.split(document));
        assertThat(pieces).anyMatch(p -> p.overlap());
        assertThat(pieces).allMatch(p -> document.fragments().stream()
                .filter(f -> f.span().start() < p.span().end()
                        && f.span().end() > p.span().start())
                .allMatch(f -> f.headingPath().equals(p.headingPath())));
        assertThat(MarkdownStructureExperiment.structures(source))
                .anyMatch(u -> u.kind().equals("TABLE"));
        String row = "# X\n| A | B |\n| --- | --- |\n| " + "字".repeat(1300) + " | B |\n";
        List<MarkdownStructureExperiment.Piece> oversized = MarkdownStructureExperiment.split(
                new DocumentParserFactory().parse("row.md", row.getBytes(StandardCharsets.UTF_8)));
        assertThat(oversized).anyMatch(p -> p.reason().startsWith("OVERSIZE_SINGLE_LINE_TABLE"));
    }

    @Test
    void retainsSoftMaxUnitsAndCompleteMultilineItems() {
        String source = "# A\n\n```java\n" + "int n = 1;\n".repeat(90) + "```\n\n"
                + "- 第一项😀\n  延续说明\n- 第二项\n\n> 引用第一行\n> 引用第二行\n\n"
                + "## B\n\n" + "句子说明。".repeat(260) + "\n";
        ParsedDocument document = new DocumentParserFactory().parse("soft.md", source.getBytes(StandardCharsets.UTF_8));
        List<MarkdownStructureExperiment.Piece> pieces = MarkdownStructureExperiment.split(document);
        verify(document, pieces, false);
        assertThat(pieces)
                .anyMatch(p -> p.span().end() - p.span().start() > 800
                        && p.span().end() - p.span().start() <= 1200);
        for (MarkdownStructureExperiment.Unit unit : MarkdownStructureExperiment.structures(source)) {
            if (List.of("FENCE", "LIST_ITEM", "BlockQuote").contains(unit.kind())) {
                assertThat(pieces)
                        .anyMatch(p -> p.span().start() <= unit.span().start()
                                && p.span().end() >= unit.span().end());
            }
        }
        assertThat(pieces).anyMatch(p -> p.reason().startsWith("LONG_Paragraph"));
        assertThat(MarkdownStructureExperiment.structures("a | b\nnot a delimiter\n"))
                .noneMatch(u -> u.kind().equals("TABLE"));
    }
}
