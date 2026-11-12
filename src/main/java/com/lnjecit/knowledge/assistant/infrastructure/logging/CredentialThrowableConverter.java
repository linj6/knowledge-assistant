package com.lnjecit.knowledge.assistant.infrastructure.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** 保留标准完整异常渲染；继承异常转换器，避免 Logback 自动追加未脱敏堆栈。 */
public class CredentialThrowableConverter extends ThrowableProxyConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return FailureLog.redact(super.convert(event));
    }
}
