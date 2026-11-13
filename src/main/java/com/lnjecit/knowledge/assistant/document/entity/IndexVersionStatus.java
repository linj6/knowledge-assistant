package com.lnjecit.knowledge.assistant.document.entity;
/** 索引版本构建状态；状态转换由业务事务控制，检索可见性仅以文档发布指针为准。 */
public enum IndexVersionStatus {
    /** 正在构建；未发布版本重试时可重新进入此状态。 */
    BUILDING,
    /** 双索引完整性和可搜索性已验证，仍须发布事务才能对检索可见。 */
    VERIFIED,
    /** 发布事务记录的诊断状态；已发布版本禁止后续重写。 */
    PUBLISHED,
    /** 构建或验证失败；仅未发布版本可按重试策略重建。 */
    FAILED
}
