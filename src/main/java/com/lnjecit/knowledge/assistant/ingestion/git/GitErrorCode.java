package com.lnjecit.knowledge.assistant.ingestion.git;

import com.lnjecit.knowledge.assistant.common.error.ErrorCode;
import lombok.Getter;

@Getter
public enum GitErrorCode implements ErrorCode {
    DISABLED(40410, "Git 仓库接入未启用"),
    FORBIDDEN(40310, "当前账号无权访问 Git 仓库"),
    SNAPSHOT_EXPIRED(41010, "Git 浏览快照已失效，请重新创建"),
    SNAPSHOT_CAPACITY(50310, "Git 浏览快照数量已达上限"),
    REPOSITORY_UNREADABLE(50311, "Git 仓库或固定提交不可读"),
    GIT_UNAVAILABLE(50312, "Git 命令无法启动"),
    COMMAND_TIMEOUT(50410, "Git 命令执行超时"),
    OUTPUT_LIMIT(42210, "Git 命令输出超过上限"),
    INVALID_SELECTION(40010, "仓库路径不合法或所选条目不可用"),
    FILE_COUNT_LIMIT(42211, "最多选择 30 个 Markdown 文件"),
    FILE_SIZE_LIMIT(42212, "所选 Markdown 文件超过大小上限");

    private final Integer code;
    private final String message;

    GitErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
