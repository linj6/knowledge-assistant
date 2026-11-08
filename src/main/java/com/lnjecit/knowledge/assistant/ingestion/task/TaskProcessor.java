package com.lnjecit.knowledge.assistant.ingestion.task;
/** 仅供显式注入受控处理器；真实生产处理器依赖 M1-09，目前不提供 Bean。 */
@FunctionalInterface
public interface TaskProcessor {
    void process(ClaimedTask task);
}
