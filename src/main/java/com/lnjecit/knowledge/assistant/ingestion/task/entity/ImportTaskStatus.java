package com.lnjecit.knowledge.assistant.ingestion.task.entity;
/** 导入任务编排状态；对外状态由 API 映射，合法转换由业务事务控制。 */
public enum ImportTaskStatus {
    /** 等待领取，尚未开始执行。 */
    PENDING,
    /** 已领取并执行；有效执行权还须校验租约和领取代际。 */
    RUNNING,
    /** 首次发布事务已完成，任务成功。 */
    SUCCEEDED,
    /** 执行失败；是否可重试由错误分类和次数策略决定。 */
    FAILED
}
