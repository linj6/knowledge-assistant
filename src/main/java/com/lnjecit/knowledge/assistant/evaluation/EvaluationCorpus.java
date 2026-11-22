package com.lnjecit.knowledge.assistant.evaluation;

import java.nio.file.Path;

public final class EvaluationCorpus {
    private static final Path ROOT = Path.of("evaluation", "corpus");

    private EvaluationCorpus() {}

    public static Path resolve(String source) {
        Path path = Path.of(source);
        for (String repository : new String[] {"backend-interview-lab", "ai-application-lab"}) {
            if (path.startsWith(Path.of("..", repository))) {
                // 保留冻结来源标识，只在读取原文字节时映射到随仓库提供的副本。
                Path bundled =
                        ROOT.resolve(path.subpath(1, path.getNameCount())).normalize();
                if (!bundled.startsWith(ROOT.resolve(repository))) {
                    throw new IllegalArgumentException("Invalid evaluation corpus path");
                }
                return bundled;
            }
        }
        return path;
    }
}
