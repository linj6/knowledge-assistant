package com.lnjecit.knowledge.assistant;

import static org.junit.jupiter.api.Assertions.*;

import com.lnjecit.knowledge.assistant.ingestion.parser.MarkdownDocumentParser;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MarkdownPerformanceIT {
    private static volatile Object sink;

    @Test
    void twoMiB() {
        String block = "# title😀\r\n\r\n正文𠀀 **em**\r\n\r\n> ## quote\r\n> text\r\n\r\n```\r\n# code\r\n```\r\n";
        byte[] bytes = block.repeat(2 * 1024 * 1024 / block.getBytes(StandardCharsets.UTF_8).length + 1)
                .getBytes(StandardCharsets.UTF_8);
        double[] times = new double[7];
        for (int i = -3; i < 7; i++) {
            long start = System.nanoTime();
            ParsedDocument parsed = new MarkdownDocumentParser().parse(bytes);
            sink = parsed;
            if (i >= 0) times[i] = (System.nanoTime() - start) / 1_000_000.0;
            assertEquals(
                    parsed.source().codePointCount(0, parsed.source().length()),
                    parsed.fragments().getLast().span().end());
        }
        Arrays.sort(times);
        System.out.println("PRODUCTION bytes=" + bytes.length + " minMedianMaxMs=" + times[0] + "/" + times[3] + "/"
                + times[6] + " heapUsed="
                + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + " heapMax="
                + Runtime.getRuntime().maxMemory());
    }
}
