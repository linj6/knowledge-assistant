package com.lnjecit.knowledge.assistant.ingestion.task;
/** 仅允许固定安全错误描述；禁止传入供应商响应或原文。 */
public class TaskExecutionException extends RuntimeException {
    public enum Code {
        MODEL_UNAVAILABLE(true, "模型服务暂时不可用"),
        MODEL_TIMEOUT(true, "模型请求超时"),
        SEARCH_UNAVAILABLE(true, "搜索服务暂时不可用"),
        INFRASTRUCTURE_TEMPORARY(true, "基础设施暂时不可用"),
        TASK_TIMEOUT(true, "任务执行超时"),
        WORKER_INTERRUPTED(true, "原执行进程中断"),
        UNSUPPORTED_FORMAT(false, "文件格式不支持"),
        INVALID_CONTENT(false, "文档内容非法"),
        VALIDATION_FAILED(false, "业务校验失败"),
        EXECUTION_FAILED(false, "任务执行失败");
        private final boolean retryable;
        private final String safeMessage;

        Code(boolean retryable, String safeMessage) {
            this.retryable = retryable;
            this.safeMessage = safeMessage;
        }

        public boolean retryable() {
            return retryable;
        }

        public String safeMessage() {
            return safeMessage;
        }
    }

    private final Code code;

    public TaskExecutionException(Code code) {
        super(code.safeMessage());
        this.code = code;
    }

    public TaskExecutionException(Code code, Throwable cause) {
        super(code.safeMessage(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
