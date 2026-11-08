package com.lnjecit.knowledge.assistant.infrastructure.persistence;
/** 数据库存储同名状态；状态转换由后续业务事务控制。 */
public enum IndexVersionStatus {
    BUILDING,
    VERIFIED,
    PUBLISHED,
    FAILED
}
