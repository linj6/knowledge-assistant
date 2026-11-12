package com.lnjecit.knowledge.assistant.infrastructure.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** 在编码输出时保护格式化消息，直接 SLF4J 调用也使用同一规则。 */
public class CredentialRedactingConverter extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return FailureLog.redact("requestId=" + event.getMDCPropertyMap().getOrDefault("requestId", "") + " - "
                + event.getFormattedMessage());
    }
}
