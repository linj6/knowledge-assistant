package com.lnjecit.knowledge.assistant.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 补充产物独立保存，不改写 Run 的三个不可变产物。 */
@Component
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class AnswerReviewStore {
    private final Path root;
    private final JsonMapper json = JsonMapper.builder().build();

    public AnswerReviewStore() {
        this(EvaluationFiles.ROOT);
    }

    AnswerReviewStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path file(String id) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))
            throw new IllegalArgumentException("INVALID_RUN_ID");
        Path directory = root.resolve(id).toRealPath();
        if (!directory.getParent().equals(root.toRealPath())) throw new IllegalArgumentException("INVALID_RUN_PATH");
        Path file = directory.resolve("review.json");
        if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("INVALID_REVIEW_PATH");
        return file;
    }

    public AnswerReview.Saved read(String id) throws Exception {
        Path file = file(id);
        return Files.exists(file) ? json.readValue(Files.readString(file), AnswerReview.Saved.class) : null;
    }

    public void save(String id, AnswerReview.Saved review) throws Exception {
        Path file = file(id);
        Path temporary = Files.createTempFile(file.getParent(), ".review-", ".tmp");
        try {
            Files.writeString(temporary, json.writeValueAsString(review));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
