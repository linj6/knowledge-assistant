package com.lnjecit.knowledge.assistant.ingestion;
/** 单一文件格式的纯解析协议。 */
public interface DocumentParser {
    String extension();

    ParsedDocument parse(byte[] bytes);
}
