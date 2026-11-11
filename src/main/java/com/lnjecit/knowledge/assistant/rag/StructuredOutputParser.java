package com.lnjecit.knowledge.assistant.rag;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 模型文本仅按严格协议读取，不修复、不做类型转换。 */
public final class StructuredOutputParser {
    public static final int MAX_CHARACTERS = 32768;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ParsedOutput parse(RawGenerationResult raw) {
        try {
            if (raw == null || raw.rawText() == null || raw.rawText().length() > MAX_CHARACTERS)
                throw new IllegalArgumentException();
            JsonNode root = mapper.readTree(raw.rawText());
            if (root == null
                    || !root.isObject()
                    || root.size() != 3
                    || !root.has("answerable")
                    || !root.has("answer")
                    || !root.has("citations")
                    || !root.get("answerable").isBoolean()
                    || !root.get("answer").isString()
                    || !root.get("citations").isArray()) throw new IllegalArgumentException();
            String answer = root.get("answer").asString();
            boolean answerable = root.get("answerable").asBoolean();
            List<String> citations = new ArrayList<>();
            for (JsonNode item : root.get("citations")) {
                if (!item.isString()) throw new IllegalArgumentException();
                citations.add(item.asString());
            }
            // 可答必须有引用；拒答必须没有引用，两者均须提供说明文字。
            if (answer.isBlank() || (answerable ? citations.isEmpty() : !citations.isEmpty()))
                throw new IllegalArgumentException();
            return new ParsedOutput(answerable, answer, citations);
        } catch (RuntimeException invalid) {
            // 不保留 Jackson 的原始异常，避免位置及消息携带模型文本。
            throw new InvalidOutputException();
        }
    }

    public record ParsedOutput(boolean answerable, String answer, List<String> citations) {
        public ParsedOutput {
            citations = List.copyOf(citations);
        }

        @Override
        public String toString() {
            return "ParsedOutput";
        }
    }

    public static final class InvalidOutputException extends RuntimeException {
        public InvalidOutputException() {
            super("OUTPUT_INVALID");
        }
    }
}
