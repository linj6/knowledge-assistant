package com.lnjecit.knowledge.assistant.ingestion.text;

public final class UnicodeOffsets {
    private UnicodeOffsets() {}
    // 一次顺序扫描建立码点边界表，后续范围校验不再从全文开头扫描。
    public static int[] boundaries(String source) {
        int[] offsets = new int[source.codePointCount(0, source.length()) + 1];
        int character = 0;
        for (int point = 1; point < offsets.length; point++) {
            character += Character.charCount(source.codePointAt(character));
            offsets[point] = character;
        }
        return offsets;
    }
}
