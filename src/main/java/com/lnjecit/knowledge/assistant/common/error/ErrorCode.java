package com.lnjecit.knowledge.assistant.common.error;

/** 稳定的错误语义；实现提供的消息必须是可公开的固定文案。 */
public interface ErrorCode {
    String getCode();

    String getMessage();
}
