package com.lnjecit.knowledge.assistant.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory;
import com.lnjecit.knowledge.assistant.ingestion.parser.MarkdownDocumentParser;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParseException;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.SourceSpan;
import com.lnjecit.knowledge.assistant.ingestion.parser.TxtDocumentParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentParserTests {
    private final DocumentParserFactory parser = new DocumentParserFactory();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "English 123",
                "中文",
                "😀𠀀",
                "\uFEFF正文",
                "a\nb\n",
                "a\r\nb",
                "a\rb\nc\r\n",
                "end",
                "end\n",
                "**行内** `code`",
                "- 列表\n> 引用",
                "同文\n\n同文"
            })
    void preservesSource(String source) {
        for (String name : List.of("a.md", "a.txt")) {
            ParsedDocument parsed = parse(name, source);
            assertEquals(source, parsed.source());
            assertEquals(parsed, parse(name, source));
            StringBuilder rebuilt = new StringBuilder();
            for (ParsedDocument.Fragment fragment : parsed.fragments())
                rebuilt.append(fragment.span().extract(source));
            assertEquals(source, rebuilt.toString());
        }
    }

    @Test
    void headingsAndFences() {
        String source =
                "\uFEFF# 中文😀\r\n## JVM\r\n### GC\r\n正文𠀀\r\n```java\r\n# 假标题\r\n```\r\n## JVM\r\n同文\r\n#### 跳跃\r\n> 引用\r\n# 新标题";
        ParsedDocument parsed = parse("a.md", source);
        assertEquals(List.of("中文😀", "JVM", "GC"), titles(parsed, 3));
        assertEquals(titles(parsed, 3), titles(parsed, 5));
        assertEquals(List.of("中文😀", "JVM"), titles(parsed, 8));
        assertEquals(List.of("中文😀", "JVM", "跳跃"), titles(parsed, 10));
        assertEquals(List.of("新标题"), titles(parsed, 11));
        assertEquals(
                source,
                parsed.fragments().stream().map(f -> f.span().extract(source)).reduce("", String::concat));
        assertEquals(
                1, parsed.fragments().getFirst().headingPath().getFirst().span().start() - 2);
        assertNotEquals(
                parsed.fragments().get(1).headingPath().getLast().span(),
                parsed.fragments().get(7).headingPath().getLast().span());
    }

    @Test
    void failures() {
        for (String source : List.of("", " \r\n\t", "\uFEFF \u00a0"))
            assertEquals(
                    ParseException.Kind.NO_CONTENT,
                    assertThrows(ParseException.class, () -> parse("a.txt", source))
                            .kind());
        assertEquals(
                ParseException.Kind.INVALID_UTF8,
                assertThrows(ParseException.class, () -> parser.parse("a.md", new byte[] {(byte) 0xc0, (byte) 0xaf}))
                        .kind());
        assertEquals(
                ParseException.Kind.UNSUPPORTED_FORMAT,
                assertThrows(ParseException.class, () -> parse("a.pdf", "text")).kind());
    }

    @Test
    void rejectsUnreliableRanges() {
        assertThrows(ParseException.class, () -> new SourceSpan(-1, 1));
        assertThrows(ParseException.class, () -> new SourceSpan(1, 1));
        assertThrows(ParseException.class, () -> new SourceSpan(0, 3).extract("😀中"));
        assertThrows(
                ParseException.class,
                () -> new ParsedDocument("abc", List.of(new ParsedDocument.Fragment(new SourceSpan(1, 3), List.of()))));
    }

    @Test
    void routesAndRejectsConflicts() {
        assertInstanceOf(TxtDocumentParser.class, parser.select("a.TXT"));
        assertInstanceOf(MarkdownDocumentParser.class, parser.select("a.md"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentParserFactory(List.of(new TxtDocumentParser(), new TxtDocumentParser())));
    }

    @Test
    void manyShortLines() {
        String line = "\u4e2d\uD83D\uDE00\uD840\uDC00\r\n";
        String source = "\uFEFF# title\r\n" + line.repeat(50000);
        long start = System.nanoTime();
        ParsedDocument parsed = parse("large.md", source);
        long elapsed = System.nanoTime() - start;
        assertEquals(50001, parsed.fragments().size());
        int cursor = 0;
        int character = 0;
        for (ParsedDocument.Fragment fragment : parsed.fragments()) {
            assertEquals(cursor, fragment.span().start());
            int end = source.offsetByCodePoints(character, fragment.span().end() - cursor);
            assertEquals(character == 0 ? "\uFEFF# title\r\n" : line, source.substring(character, end));
            character = end;
            cursor = fragment.span().end();
        }
        assertEquals(source.length(), character);
        System.out.println(
                "SHORT_LINES lines=50001 codePoints=" + cursor + " parseAndValidationMs=" + elapsed / 1_000_000.0);
    }

    @Test
    void containerScopesAndSetext() {
        String source =
                "\uFEFF# 外😀\r\n正文\r\n\r\n> ## 引用𠀀\r\n> 内容\r\n\r\n外文\r\n\r\n- ## 列表\r\n  内容\r\n- 兄弟\r\n\r\n尾文\r\n\r\n标题 **原样**\r\n===\r\n正文";
        ParsedDocument parsed = parse("a.md", source);
        assertEquals(List.of("外😀", "引用𠀀"), titles(parsed, 4));
        assertEquals(List.of("外😀"), titles(parsed, 6));
        assertEquals(List.of("外😀", "列表"), titles(parsed, 9));
        assertEquals(List.of("外😀"), titles(parsed, 10));
        assertEquals(List.of("外😀"), titles(parsed, 12));
        assertEquals(List.of("标题 **原样**"), titles(parsed, 16));
        assertEquals(parsed, parse("a.md", source));
        StringBuilder result = new StringBuilder();
        for (ParsedDocument.Fragment fragment : parsed.fragments()) {
            result.append(fragment.span().extract(source));
            for (ParsedDocument.Heading heading : fragment.headingPath())
                assertEquals(heading.text(), heading.span().extract(source));
        }
        assertEquals(source, result.toString());
    }

    @Test
    void multilineHeadingKeepsContinuousEnvelope() {
        String source = "> 第一😀\r\n> 第二𠀀\r\n> ===\r\n> 文本\r\n\r\n外部";
        ParsedDocument parsed = parse("a.md", source);
        assertEquals(List.of("第一😀\r\n> 第二𠀀"), titles(parsed, 3));
        assertEquals(List.of(), titles(parsed, 5));
        assertEquals(
                source,
                parsed.fragments().stream()
                        .map(f -> f.span().extract(source))
                        .collect(java.util.stream.Collectors.joining()));
    }

    private ParsedDocument parse(String name, String source) {
        return parser.parse(name, source.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> titles(ParsedDocument parsed, int line) {
        return parsed.fragments().get(line).headingPath().stream()
                .map(ParsedDocument.Heading::text)
                .toList();
    }
}
