package com.lnjecit.knowledge.assistant.ingestion.task;
/** M1-13 真实处理器必须在各耗时阶段检查截止时间，并配置客户端请求超时。 */
@FunctionalInterface
public interface TaskProcessor {
    void process(ClaimedTask task, TaskExecutionContext context);
}
