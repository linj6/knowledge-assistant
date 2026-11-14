package com.lnjecit.knowledge.assistant.evaluation;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class FrozenFingerprint {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FrozenFingerprint() {}

    public static String answer(JsonNode dataset) throws Exception {
        ObjectNode payload = (ObjectNode) dataset.deepCopy();
        payload.remove("freezeFingerprint");
        return fingerprint(payload);
    }
    // 对象键递归排序，数组保留顺序；UTF-8 紧凑 JSON，无时间戳，作为指纹输入。
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            node.propertyNames().stream().sorted().forEach(k -> sorted.set(k, canonical(node.path(k))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            for (JsonNode item : node) array.add(canonical(item));
            return array;
        }
        return node;
    }

    public static String fingerprint(JsonNode node) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(canonical(node).toString().getBytes(StandardCharsets.UTF_8)));
    }
}
