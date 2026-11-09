package com.lnjecit.knowledge.assistant.ingestion.task;

import org.slf4j.Logger;

/** 仅接收固定事件和安全标量；日志后端故障不得改变导入结果。 */
public final class ImportEvents {
    private ImportEvents() {}

    public static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public static void info(Logger log, String pattern, Object... fields) {
        try {
            log.info(pattern, fields);
        } catch (RuntimeException ignored) {
            // 日志设施异常不可触发任务失败或覆盖原始异常。
        }
    }

    public static void warn(Logger log, String pattern, Object... fields) {
        try {
            log.warn(pattern, fields);
        } catch (RuntimeException ignored) {
            // 不递归记录日志设施自身的故障。
        }
    }
}
