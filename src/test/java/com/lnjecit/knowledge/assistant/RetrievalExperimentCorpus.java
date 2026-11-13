package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy;
import com.lnjecit.knowledge.assistant.ingestion.chunk.FixedCodePointSplitter;
import com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class RetrievalExperimentCorpus {
    record Document(
            String path,
            String sha,
            List<MarkdownStructureExperiment.Piece> fixed,
            List<MarkdownStructureExperiment.Piece> structure) {}

    static List<Document> load() throws Exception {
        List<Document> result = new ArrayList<>();
        JsonNode gold = JsonMapper.builder()
                .build()
                .readTree(Files.readString(Path.of("docs/engineering/m4-01-gold-evidence.json")));
        List<String> oversize = new ArrayList<>();
        for (String path : Files.readAllLines(Path.of("docs/engineering/m4-01-samples.txt"))) {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            String sha = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            boolean matched = false;
            for (JsonNode question : gold)
                if (question.path("documentPath").asString().equals(path)) {
                    assertThat(sha).isEqualTo(question.path("sha256").asString());
                    matched = true;
                }
            assertThat(matched).isTrue();
            ParsedDocument parsed = new DocumentParserFactory().parse(path, bytes);
            List<MarkdownStructureExperiment.Piece> fixed = new FixedCodePointSplitter()
                    .split(parsed, new ChunkingConfig(1, ChunkingStrategy.FIXED_CODE_POINT, 800, 120)).stream()
                            .map(c -> new MarkdownStructureExperiment.Piece(
                                    c.span(), c.content(), c.headingPath(), "FIXED_WINDOW", false, ""))
                            .toList();
            List<MarkdownStructureExperiment.Piece> structure = MarkdownStructureExperiment.split(parsed);
            for (int i = 0; i < structure.size(); i++) {
                MarkdownStructureExperiment.Piece piece = structure.get(i);
                int length = piece.sourceContent()
                        .codePointCount(0, piece.sourceContent().length());
                if (length > 800) oversize.add(path + "\t" + i + "\t" + length);
            }
            result.add(new Document(path, sha, fixed, structure));
        }
        assertThat(result).hasSize(8);
        assertThat(result.stream().mapToInt(d -> d.fixed().size()).sum()).isEqualTo(58);
        assertThat(result.stream().mapToInt(d -> d.structure().size()).sum()).isEqualTo(83);
        Files.createDirectories(Path.of("target/m4-02b"));
        Files.write(Path.of("target/m4-02b/oversize.tsv"), oversize);
        return List.copyOf(result);
    }
    /** SYNTHETIC：仅验证数据流，不能用于语义质量评价。 */
    static float[] synthetic(String text) {
        float[] values = new float[1024];
        values[0] = 1;
        values[1] = (EmbeddingSnapshot.sha(text).charAt(0) + 1) / 128.0f;
        return values;
    }
}
