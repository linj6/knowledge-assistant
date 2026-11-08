package com.lnjecit.knowledge.assistant.ingestion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** AST 只决定结构和标题作用域；正文始终来自完整原文。 */
public final class MarkdownDocumentParser implements DocumentParser {
    private final Parser parser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    public String extension() {
        return "md";
    }

    public ParsedDocument parse(byte[] bytes) {
        String source = ParserSource.decode(bytes);
        int bom = source.charAt(0) == '\uFEFF' ? 1 : 0;
        String view = source.substring(bom);
        int[] coordinates = new int[source.length() + 1];
        Arrays.fill(coordinates, -1);
        int count = 0;
        for (int character = 0; character < source.length(); ) {
            coordinates[character] = count++;
            character += Character.charCount(source.codePointAt(character));
        }
        coordinates[source.length()] = count;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\r') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                if (i + 1 < source.length()) starts.add(i + 1);
            } else if (source.charAt(i) == '\n' && i + 1 < source.length()) starts.add(i + 1);
        }
        List<List<ParsedDocument.Heading>> paths = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) paths.add(List.of());
        Node root = parser.parse(view);
        assign(root, new ArrayList<>(), paths, source, coordinates, bom);
        List<ParsedDocument.Fragment> fragments = new ArrayList<>();
        for (int line = 0; line < starts.size(); line++) {
            int start = starts.get(line);
            int end = line + 1 < starts.size() ? starts.get(line + 1) : source.length();
            fragments.add(
                    new ParsedDocument.Fragment(new SourceSpan(coordinates[start], coordinates[end]), paths.get(line)));
        }
        return new ParsedDocument(source, fragments);
    }

    private void assign(
            Node container,
            List<ParsedDocument.Heading> inherited,
            List<List<ParsedDocument.Heading>> paths,
            String source,
            int[] coordinates,
            int bom) {
        List<ParsedDocument.Heading> path = new ArrayList<>(inherited);
        int nextLine = container.getSourceSpans().isEmpty()
                ? 0
                : container.getSourceSpans().getFirst().getLineIndex();
        for (Node node = container.getFirstChild(); node != null; node = node.getNext()) {
            if (node.getSourceSpans().isEmpty()) continue;
            int firstLine = node.getSourceSpans().getFirst().getLineIndex();
            for (int line = nextLine; line < firstLine; line++) paths.set(line, List.copyOf(path));
            if (node instanceof Heading heading) {
                while (!path.isEmpty() && path.getLast().level() >= heading.getLevel()) path.removeLast();
                // 使用行内节点源码的连续包络，保留其中的 Markdown 标记和容器前缀。
                // 多行标题不拼接去前缀后的离散文本，而保留包络内全部原文。
                Node first = heading.getFirstChild();
                Node last = heading.getLastChild();
                if (first != null && last != null) {
                    if (first.getSourceSpans().isEmpty()
                            || last.getSourceSpans().isEmpty())
                        throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
                    org.commonmark.node.SourceSpan left = first.getSourceSpans().getFirst();
                    org.commonmark.node.SourceSpan right = last.getSourceSpans().getLast();
                    int start = left.getInputIndex() + bom;
                    int end = right.getInputIndex() + right.getLength() + bom;
                    if (start < 0
                            || end >= coordinates.length
                            || coordinates[start] < 0
                            || coordinates[end] < 0
                            || end <= start) throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
                    path.add(new ParsedDocument.Heading(
                            source.substring(start, end),
                            heading.getLevel(),
                            new SourceSpan(coordinates[start], coordinates[end])));
                }
            }
            for (org.commonmark.node.SourceSpan span : node.getSourceSpans()) {
                int start = span.getInputIndex() + bom;
                int end = start + span.getLength();
                if (start < 0 || end >= coordinates.length || coordinates[start] < 0 || coordinates[end] < 0)
                    throw new ParseException(ParseException.Kind.UNRELIABLE_MAPPING);
                paths.set(span.getLineIndex(), List.copyOf(path));
            }
            // 每个容器单独复制路径；引用块、列表项中的标题不会修改外层或兄弟项。
            if (!(node instanceof Heading) && node.getFirstChild() != null)
                assign(node, path, paths, source, coordinates, bom);
            nextLine = node.getSourceSpans().getLast().getLineIndex() + 1;
        }
        if (container.getParent() == null)
            for (int line = nextLine; line < paths.size(); line++) paths.set(line, List.copyOf(path));
    }
}
