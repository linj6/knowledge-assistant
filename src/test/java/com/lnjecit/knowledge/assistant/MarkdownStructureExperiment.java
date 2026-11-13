package com.lnjecit.knowledge.assistant;

import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** 仅供 M4-01 离线实验，不接入生产枚举或导入流程。 */
final class MarkdownStructureExperiment {
    static final String STRATEGY = "MARKDOWN_STRUCTURE_AWARE";

    record Unit(SourceSpan span, String kind, String auxiliary) {}

    record Piece(
            SourceSpan span,
            String sourceContent,
            List<ParsedDocument.Heading> headingPath,
            String reason,
            boolean overlap,
            String auxiliary) {}

    static List<Unit> structures(String source) {
        Node root = Parser.builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build()
                .parse(source.startsWith("\uFEFF") ? source.substring(1) : source);
        List<Unit> units = new ArrayList<>();
        int bom = source.startsWith("\uFEFF") ? 1 : 0;
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof BulletList || node instanceof OrderedList) {
                for (Node item = node.getFirstChild(); item != null; item = item.getNext())
                    add(source, item, bom, units);
            } else add(source, node, bom, units);
        }
        return List.copyOf(units);
    }

    private static void add(String source, Node node, int bom, List<Unit> units) {
        if (node.getSourceSpans().isEmpty()) return;
        int left = node.getSourceSpans().getFirst().getInputIndex() + bom;
        org.commonmark.node.SourceSpan last = node.getSourceSpans().getLast();
        int right = last.getInputIndex() + last.getLength() + bom;
        // 归属完整原始行；空行随后由前一结构承接，避免标记或换行丢失。
        while (left > 0 && source.charAt(left - 1) != '\n' && source.charAt(left - 1) != '\r') left--;
        while (right < source.length() && source.charAt(right) != '\n' && source.charAt(right) != '\r') right++;
        if (right < source.length() && source.charAt(right) == '\r') right++;
        if (right < source.length() && source.charAt(right) == '\n') right++;
        String kind = node instanceof FencedCodeBlock
                ? "FENCE"
                : node instanceof ListItem ? "LIST_ITEM" : node.getClass().getSimpleName();
        String auxiliary = node instanceof FencedCodeBlock fence ? fence.getInfo() : "";
        String text = source.substring(left, right);
        // 核心 CommonMark 不识别 GFM 表格：仅接受表头后紧跟标准分隔行的整个管道段落。
        String[] lines = text.split("\\R");
        if (node instanceof Paragraph
                && lines.length >= 2
                && lines[0].contains("|")
                && lines[1].matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*")
                && java.util.Arrays.stream(lines).allMatch(line -> line.contains("|"))) {
            kind = "TABLE";
            auxiliary = lines[0] + "\n" + lines[1];
        }
        units.add(new Unit(
                new SourceSpan(source.codePointCount(0, left), source.codePointCount(0, right)), kind, auxiliary));
    }

    static ParsedDocument scopedDocument(ParsedDocument document) {
        List<Unit> fences = structures(document.source()).stream()
                .filter(u -> u.kind().equals("FENCE"))
                .toList();
        List<ParsedDocument.Fragment> fragments = new ArrayList<>();
        for (ParsedDocument.Fragment fragment : document.fragments()) {
            List<ParsedDocument.Heading> path = fragment.headingPath();
            // 生产 AST 映射未覆盖围栏内空行；仅在实验中补齐围栏起始作用域。
            if (path.isEmpty()) {
                for (Unit fence : fences) {
                    if (fragment.span().start() >= fence.span().start()
                            && fragment.span().end() <= fence.span().end()) {
                        path = document.fragments().stream()
                                .filter(f -> f.span().start() <= fence.span().start()
                                        && f.span().end() > fence.span().start())
                                .findFirst()
                                .orElseThrow()
                                .headingPath();
                        break;
                    }
                }
            }
            fragments.add(new ParsedDocument.Fragment(fragment.span(), path));
        }
        return new ParsedDocument(document.source(), fragments);
    }

    static List<Piece> split(ParsedDocument original) {
        ParsedDocument document = scopedDocument(original);
        String source = document.source();
        int length = source.codePointCount(0, source.length());
        List<Unit> units = structures(source);
        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        boundaries.add(length);
        for (Unit unit : units) boundaries.add(unit.span().start());
        // 标题作用域以生产 Parser 为准，包括容器内标题；不跨路径变化合并。
        List<ParsedDocument.Heading> previous = List.of();
        for (ParsedDocument.Fragment fragment : document.fragments()) {
            if (!fragment.headingPath().equals(previous))
                boundaries.add(fragment.span().start());
            previous = fragment.headingPath();
        }
        List<Integer> points = new ArrayList<>(boundaries);
        List<Piece> result = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            int start = points.get(i);
            int end = points.get(i + 1);
            Unit unit = units.stream()
                    .filter(u -> u.span().start() <= start && u.span().end() > start)
                    .findFirst()
                    .orElse(new Unit(new SourceSpan(start, end), "GAP", ""));
            if (end - start <= 1200) append(result, document, start, end, "WHOLE", false, unit.auxiliary());
            else {
                // 超长代码、表格、列表项、引用块按原始行降级；普通段落优先句末。
                String text = new SourceSpan(start, end).extract(source);
                Pattern pattern = Pattern.compile(
                        unit.kind().equals("Paragraph") ? "[。！？]|[.!?](?:[\\s]|$)|\\r\\n|\\r|\\n" : "\\r\\n|\\r|\\n");
                Matcher matcher = pattern.matcher(text);
                int cursor = start;
                int candidate = start;
                while (matcher.find()) {
                    int boundary = start + text.codePointCount(0, matcher.end());
                    if (boundary - cursor > 1200 && candidate > cursor) {
                        append(
                                result,
                                document,
                                cursor,
                                candidate,
                                "LONG_" + unit.kind() + "_BOUNDARY",
                                false,
                                unit.auxiliary());
                        cursor = candidate;
                    }
                    candidate = boundary;
                    if (boundary - cursor >= 800) {
                        appendLong(result, document, cursor, boundary, unit);
                        cursor = boundary;
                    }
                }
                if (cursor < end) appendLong(result, document, cursor, end, unit);
            }
        }
        return List.copyOf(result);
    }

    private static void appendLong(List<Piece> result, ParsedDocument document, int start, int end, Unit unit) {
        if (end - start <= 1200 || !unit.kind().equals("Paragraph")) {
            append(
                    result,
                    document,
                    start,
                    end,
                    end - start > 1200 ? "OVERSIZE_SINGLE_LINE_" + unit.kind() : "LONG_" + unit.kind() + "_BOUNDARY",
                    false,
                    unit.auxiliary());
            return;
        }
        // 极端长句仅在该局部使用码点窗口，明确标记 120 码点重复来源。
        for (int cursor = start; cursor < end; cursor += 680) {
            int right = Math.min(cursor + 800, end);
            append(result, document, cursor, right, "LONG_SENTENCE_WINDOW", cursor > start, unit.auxiliary());
            if (right == end) break;
        }
    }

    private static void append(
            List<Piece> result,
            ParsedDocument document,
            int start,
            int end,
            String reason,
            boolean overlap,
            String auxiliary) {
        SourceSpan span = new SourceSpan(start, end);
        List<ParsedDocument.Heading> path = document.fragments().stream()
                .filter(f -> f.span().start() <= start && f.span().end() > start)
                .findFirst()
                .orElseThrow()
                .headingPath();
        // 普通短结构合并不超过目标值；完整 801~1200 单元独立保留。
        if (!result.isEmpty() && reason.equals("WHOLE")) {
            Piece last = result.getLast();
            if (last.reason().equals("WHOLE")
                    && last.span().end() == start
                    && end - last.span().start() <= 800
                    && last.headingPath().equals(path)) {
                SourceSpan merged = new SourceSpan(last.span().start(), end);
                result.set(
                        result.size() - 1,
                        new Piece(
                                merged,
                                merged.extract(document.source()),
                                path,
                                reason,
                                false,
                                last.auxiliary() + "\n" + auxiliary));
                return;
            }
        }
        result.add(new Piece(span, span.extract(document.source()), path, reason, overlap, auxiliary));
    }
}
