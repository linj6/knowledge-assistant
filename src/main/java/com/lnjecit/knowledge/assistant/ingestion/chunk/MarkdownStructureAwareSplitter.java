package com.lnjecit.knowledge.assistant.ingestion.chunk;

import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.SourceSpan;
import com.lnjecit.knowledge.assistant.ingestion.text.UnicodeOffsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.TreeSet;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** 沿用 M4 的顶层结构和列表项边界，使用生产配置限制每个原文窗口。 */
public final class MarkdownStructureAwareSplitter implements DocumentSplitter {
    private static final Parser PARSER =
            Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();

    @Override
    public List<Chunk> split(ParsedDocument document, ChunkingConfig config) {
        String source = document.source();
        int[] offsets = UnicodeOffsets.boundaries(source);
        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        boundaries.add(offsets.length - 1);
        int bom = source.startsWith("\uFEFF") ? 1 : 0;
        Node root = PARSER.parse(source.substring(bom));
        BitSet fenceLines = new BitSet();
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof BulletList || node instanceof OrderedList) {
                for (Node item = node.getFirstChild(); item != null; item = item.getNext())
                    addBoundary(boundaries, offsets, source, item, bom);
            } else {
                addBoundary(boundaries, offsets, source, node, bom);
            }
        }
        markFenceLines(root, fenceLines);
        // Markdown 解析器提供逐行标题作用域。TXT 只有一个片段，仍可按原文结构或窗口切分。
        BitSet scopeBoundaries = new BitSet();
        List<ParsedDocument.Heading> previous = List.of();
        for (int line = 0; line < document.fragments().size(); line++) {
            ParsedDocument.Fragment fragment = document.fragments().get(line);
            if (fenceLines.get(line)) continue;
            if (!fragment.headingPath().equals(previous)) {
                boundaries.add(fragment.span().start());
                scopeBoundaries.set(fragment.span().start());
            }
            previous = fragment.headingPath();
        }
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int end = 0;
        int fragment = 0;
        for (int next : boundaries.tailSet(0, false)) {
            while (document.fragments().get(fragment).span().end() <= start) fragment++;
            List<ParsedDocument.Heading> path =
                    document.fragments().get(fragment).headingPath();
            if (end > start && (next - start > config.chunkSize() || scopeBoundaries.get(end))) {
                append(chunks, source, offsets, start, end, path);
                start = end;
                while (document.fragments().get(fragment).span().end() <= start) fragment++;
                path = document.fragments().get(fragment).headingPath();
            }
            if (next - start > config.chunkSize()) {
                // 只在超长结构内使用重叠窗口，优先沿原始行结束，极长行按码点拆分。
                splitLong(chunks, source, offsets, start, next, path, config);
                start = next;
            }
            end = next;
        }
        if (start < end) {
            while (document.fragments().get(fragment).span().end() <= start) fragment++;
            append(
                    chunks,
                    source,
                    offsets,
                    start,
                    end,
                    document.fragments().get(fragment).headingPath());
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("Document contains no non-blank chunks");
        return List.copyOf(chunks);
    }

    private static void addBoundary(TreeSet<Integer> boundaries, int[] offsets, String source, Node node, int bom) {
        if (node.getSourceSpans().isEmpty()) return;
        int start = node.getSourceSpans().getFirst().getInputIndex() + bom;
        while (start > 0 && source.charAt(start - 1) != '\n' && source.charAt(start - 1) != '\r') start--;
        boundaries.add(Arrays.binarySearch(offsets, start));
    }

    private static void markFenceLines(Node parent, BitSet lines) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof FencedCodeBlock && !node.getSourceSpans().isEmpty()) {
                lines.set(
                        node.getSourceSpans().getFirst().getLineIndex() + 1,
                        node.getSourceSpans().getLast().getLineIndex() + 1);
            } else {
                markFenceLines(node, lines);
            }
        }
    }

    private static void splitLong(
            List<Chunk> chunks,
            String source,
            int[] offsets,
            int start,
            int end,
            List<ParsedDocument.Heading> path,
            ChunkingConfig config) {
        for (int cursor = start; cursor < end; ) {
            int right = cursor + Math.min(config.chunkSize(), end - cursor);
            if (right < end) {
                int minimum = cursor + Math.max(config.chunkOverlap() + 1, config.chunkSize() / 2);
                for (int point = right; point >= minimum; point--) {
                    char last = source.charAt(offsets[point] - 1);
                    if (last == '\n' || (last == '\r' && source.charAt(offsets[point]) != '\n')) {
                        right = point;
                        break;
                    }
                }
            }
            append(chunks, source, offsets, cursor, right, path);
            if (right == end) break;
            cursor = right - config.chunkOverlap();
        }
    }

    private static void append(
            List<Chunk> chunks, String source, int[] offsets, int start, int end, List<ParsedDocument.Heading> path) {
        String content = source.substring(offsets[start], offsets[end]);
        if (!content.isBlank()) chunks.add(new Chunk(chunks.size(), content, new SourceSpan(start, end), path));
    }
}
