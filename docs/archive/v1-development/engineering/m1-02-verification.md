# M1-02 知识库 REST API 验收记录

日期：2026-11-08。分支 feature/m1-ingestion-indexing，起点 1face6c4dce19250da844fa75d9ca6c87606414b；工作区初始干净，上轮没有残留文件。

## 实现与协议

- knowledge 包新增 Controller、Service、具体 Repository、CreateKnowledgeBaseRequest 和 KnowledgeBaseResponse；复用 KnowledgeBaseMapper 和现有实体。
- POST /api/v1/knowledge-bases：name 必填，strip 后非空且最多 100 个 UTF-16 单元；description 可选，strip 后最多 1000 个 UTF-16 单元。Bean Validation 在请求 DTO 标准化后执行。允许同名，成功返回 201 和相对 Location。
- GET /api/v1/knowledge-bases：返回 DTO 数组，createdAt、id 升序，空集合为 []。
- GET /api/v1/knowledge-bases/{id}：正十进制 Long，非法/溢出/非正值 400，不存在 404。
- 响应字段仅 id、name、description、createdAt；内部 Long 在 DTO 转为十进制 String，未全局改变 Long 序列化。时间来自数据库默认值。
- 复用 BusinessException、CommonErrorCode、ApiExceptionHandler 和 requestId；新增 KNOWLEDGE_BASE_NOT_FOUND 的 404 映射。数据库/未知异常保持安全 500，不输出 SQL 或堆栈。
- Service 管理创建事务，Repository 插入后回读。未变更依赖、Flyway 或冻结规范。

## 实际验证

以下最终执行成功：

```powershell
mvn spotless:apply
mvn spotless:check
mvn -DskipTests test-compile
mvn "-Dtest=KnowledgeBaseApiTests,KnowledgeBasePersistenceTests,ApiBaselineTests,BusinessExceptionTests" test
git diff --check
```

共 19 项，0 失败、0 错误、0 跳过：API 7、真实数据库 1 项聚合测试、M0 错误协议回归 11。

API 测试覆盖创建/Location/字符串大 ID、详情、空及非空列表、DTO 字段、标准化后的长度边界、description 缺省、非法字段/路径、404、requestId 和安全 500。

Testcontainers 使用仓库固定 pgvector 0.8.0-pg17 镜像（PostgreSQL 17.6），真实执行 V1→V2→V3；验证持久化、Snowflake、标准化保存、默认时间、同名创建、ID 查询、不存在、时间及 ID 两级排序、事务提交、数据库约束拒绝插入后记录数不变及后续创建恢复。仅使用临时测试约束，不制造复杂事务场景。

初次并行 Maven 构建产生编译输出竞争，改为串行后编译成功；初次回归发现旧测试枚举状态数组未包含新增 404，补齐后上述 19 项全部通过。已审阅全部新增文件和完整 diff。

未执行全量测试、ES/MinIO 或真实模型请求，本任务不涉及这些链路；未验证多实例 Snowflake 分配，沿用 M1-01 边界。无已知 M1-02 阻断项。M1-02 完成，未进入 M1-03。
