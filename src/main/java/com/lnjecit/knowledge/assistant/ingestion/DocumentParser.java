package com.lnjecit.knowledge.assistant.ingestion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 无外部依赖的 UTF-8 原文解析器；行片段不是 Chunk。 */
public final class DocumentParser {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|$)");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    public ParsedDocument parse(String filename, byte[] bytes) {
        String name = filename.toLowerCase(Locale.ROOT);
        boolean markdown = name.endsWith(".md");
        if (!markdown && !name.endsWith(".txt")) throw new ParseException(ParseException.Kind.UNSUPPORTED_FORMAT);
        String source;
        try {
            source = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ParseException(ParseException.Kind.INVALID_UTF8);
        }
        if (source.codePoints().allMatch(cp -> cp == 0xFEFF || Character.isWhitespace(cp) || Character.isSpaceChar(cp)))
            throw new ParseException(ParseException.Kind.NO_CONTENT);
        int[] points = source.codePoints().toArray();
        if (!markdown)
            return new ParsedDocument(
                    source, List.of(new ParsedDocument.Fragment(new SourceSpan(0, points.length), List.of())));
        List<ParsedDocument.Fragment> fragments = new ArrayList<>();
        List<ParsedDocument.Heading> path = new ArrayList<>();
        char fence = 0;
        int fenceLength = 0;
        int start = 0;
        // 直接沿码点扫描，不以搜索正文的方式反推位置；保留 BOM 和原始换行。
        while (start < points.length) {
            int end = start;
            while (end < points.length && points[end] != '\r' && points[end] != '\n') end++;
            int next = end;
            if (next < points.length && points[next++] == '\r' && next < points.length && points[next] == '\n') next++;
            int visibleStart = start == 0 && points[0] == 0xFEFF ? 1 : start;
            String line = new String(points, visibleStart, end - visibleStart);
            Matcher marker = FENCE.matcher(line);
            if (fence != 0) {
                if (marker.matches()
                        && marker.group(1).charAt(0) == fence
                        && marker.group(1).length() >= fenceLength
                        && marker.group(2).isBlank()) fence = 0;
            } else if (marker.matches()
                    && (marker.group(1).charAt(0) != '`' || !marker.group(2).contains("`"))) {
                fence = marker.group(1).charAt(0);
                fenceLength = marker.group(1).length();
            } else {
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    int level = heading.group(1).length();
                    String text = heading.group(2) == null ? "" : heading.group(2);
                    // 仅去掉 ATX 尾部语法；展示文字仍对应单一连续源码范围。
                    text = text.replaceFirst("[ \\t]+#+[ \\t]*$", "").stripTrailing();
                    while (!path.isEmpty() && path.getLast().level() >= level) path.removeLast();
                    if (!text.isEmpty()) {
                        int textStart = visibleStart + line.codePointCount(0, heading.start(2));
                        path.add(new ParsedDocument.Heading(
                                text,
                                level,
                                new SourceSpan(textStart, textStart + text.codePointCount(0, text.length()))));
                    }
                }
            }
            fragments.add(new ParsedDocument.Fragment(new SourceSpan(start, next), path));
            start = next;
        }
        return new ParsedDocument(source, fragments);
    }
}
