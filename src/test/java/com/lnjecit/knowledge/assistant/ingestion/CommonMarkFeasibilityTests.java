package com.lnjecit.knowledge.assistant.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import com.lnjecit.knowledge.assistant.ingestion.parser.MarkdownDocumentParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;

/** 仅测试范围的技术选型实验，不提供正式业务适配器。 */
class CommonMarkFeasibilityTests {
    private final Parser plain = Parser.builder().build();
    private final Parser spans = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();
    private static volatile Object sink;
    private static final String COMPOSITE =
            "\uFEFF# 中文😀\r\n## JVM\r\n### GC\r\n正文𠀀\r\n```java\r\n# 假标题\r\n```\r\n## JVM\r\n同文\r\n#### 跳跃\r\n> 引用\r\n# 新标题";

    private List<Node> nodes(Node root) {
        List<Node> result = new ArrayList<>();
        List<Node> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            result.add(node);
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) pending.add(child);
        }
        return result;
    }
    // UTF-16 边界一次转换；代理对内部不是合法业务边界。
    private int[] coordinates(String source) {
        int[] map = new int[source.length() + 1];
        Arrays.fill(map, -1);
        int point = 0;
        for (int character = 0; character < source.length(); ) {
            map[character] = point++;
            character += Character.charCount(source.codePointAt(character));
        }
        map[source.length()] = point;
        return map;
    }

    private long validate(String source, Node root, boolean exact) {
        int[] map = coordinates(source);
        List<Integer> lines = new ArrayList<>();
        lines.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\r') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                lines.add(i + 1);
            } else if (source.charAt(i) == '\n') lines.add(i + 1);
        }
        long count = 0;
        for (Node node : nodes(root))
            for (SourceSpan span : node.getSourceSpans()) {
                int start = span.getInputIndex();
                int end = start + span.getLength();
                assertTrue(start >= 0 && end <= source.length());
                assertEquals(lines.get(span.getLineIndex()) + span.getColumnIndex(), start);
                assertTrue(map[start] >= 0 && map[end] >= map[start]);
                if (exact)
                    assertEquals(
                            source.substring(start, end),
                            source.substring(
                                    source.offsetByCodePoints(0, map[start]), source.offsetByCodePoints(0, map[end])));
                count++;
            }
        return count;
    }

    @Test
    void unicodeAndBomEvidence() {
        for (String source : List.of(
                COMPOSITE,
                "😀x\r\n# 中文𠀀\n重复\r重复",
                "> 😀甲\r\n> 乙𠀀",
                "# 😀 **中** [文](url)\\*\n",
                "\t代码😀\r\n",
                "",
                "\n\r\n",
                "\u0000😀",
                "#",
                "[broken](",
                "```\n# open")) {
            Node root = spans.parse(source);
            validate(source, root, true);
            System.out.println("EVIDENCE input=" + source.replace("\r", "\\r").replace("\n", "\\n"));
            for (Node node : nodes(root))
                if (node instanceof Heading || node instanceof Paragraph || node instanceof FencedCodeBlock)
                    System.out.println(node + " " + node.getSourceSpans());
        }
        Node root = spans.parse(COMPOSITE);
        assertInstanceOf(Paragraph.class, root.getFirstChild());
        assertEquals(5, nodes(root).stream().filter(n -> n instanceof Heading).count());
        assertEquals(
                6,
                new MarkdownDocumentParser()
                        .parse(COMPOSITE.getBytes(StandardCharsets.UTF_8)).fragments().stream()
                                .flatMap(f -> f.headingPath().stream())
                                .distinct()
                                .count());
        String raw = "# 😀 **中** [文](url)\\*";
        Node heading = spans.parse(raw).getFirstChild();
        assertEquals(
                raw,
                raw.substring(
                        heading.getSourceSpans().getFirst().getInputIndex(),
                        heading.getSourceSpans().getFirst().getLength()));
        assertNotEquals(raw, TextContentRenderer.builder().build().render(heading));
    }

    @Test
    void structuresAndDiscontinuousRanges() {
        String source = "标题😀\r\n===\r\n\r\n- # 嵌套\n  - item\n\n> ## 引用\n> 多行😀\n> 下一行\n\n    # 缩进代码\n\n```\n# 未关闭";
        Node root = spans.parse(source);
        validate(source, root, true);
        for (Class<?> type : List.of(
                Heading.class, BulletList.class, BlockQuote.class, IndentedCodeBlock.class, FencedCodeBlock.class))
            assertTrue(nodes(root).stream().anyMatch(type::isInstance));
        Heading setext = (Heading) root.getFirstChild();
        assertEquals(1, setext.getLevel());
        assertEquals(2, setext.getSourceSpans().size());
        Node quoteParagraph = nodes(root).stream()
                .filter(n -> n instanceof Paragraph && n.getParent() instanceof BlockQuote)
                .findFirst()
                .orElseThrow();
        assertEquals(2, quoteParagraph.getSourceSpans().size());
        SourceSpan first = quoteParagraph.getSourceSpans().getFirst();
        SourceSpan second = quoteParagraph.getSourceSpans().getLast();
        assertTrue(first.getInputIndex() + first.getLength() < second.getInputIndex());
        assertEquals("多行😀", source.substring(first.getInputIndex(), first.getInputIndex() + first.getLength()));
        assertEquals(
                3,
                new MarkdownDocumentParser()
                        .parse(source.getBytes(StandardCharsets.UTF_8)).fragments().stream()
                                .flatMap(f -> f.headingPath().stream())
                                .distinct()
                                .count());
        Node inline = spans.parse("*em* [link](url) \\*\n");
        assertTrue(nodes(inline).stream().anyMatch(Emphasis.class::isInstance));
        assertTrue(nodes(inline).stream().anyMatch(Link.class::isInstance));
        assertInstanceOf(Paragraph.class, spans.parse("a | b\n--- | ---\nx | y").getFirstChild());
    }
}
