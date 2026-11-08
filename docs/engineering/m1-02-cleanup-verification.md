# M1-02-CLEANUP 验收补充

日期：2026-11-08；分支 feature/m1-ingestion-indexing；基线 da7114588b10470a7f9483f53cfc0ae7ac9e9c13，初始工作区干净。

## 修改

- KnowledgeBaseRepository.create 设置名称、描述和 Instant.now()，仅调用一次 mapper.insert，直接返回实体，不再 SELECT 回读。
- KnowledgeBaseService.create 移除 @Transactional；单条 INSERT 由数据库保证原子性，没有新增事务注解。
- 检查确认 KnowledgeBase 的 @TableId(ASSIGN_ID)、BaseMapper 和默认驼峰映射保持不变；createdAt 非空时参与 created_at INSERT。没有自定义主键生成或字段插入策略覆盖。Flyway 的 DEFAULT CURRENT_TIMESTAMP 保留。
- ApiExceptionHandler.statusFor 使用 instanceof CommonErrorCode 和 Java 21 枚举 switch 映射；其他 ErrorCode 默认 400。整数码值、Result、Jackson 和 API 路径/Location 未改。

## 验证

```powershell
mvn spotless:apply
mvn spotless:check
mvn "-Dtest=KnowledgeBaseRepositoryTests,KnowledgeBaseApiTests,KnowledgeBasePersistenceTests,ApiBaselineTests,BusinessExceptionTests" test
git diff --check
```

全部成功；测试命令包含主代码和测试编译。21 项，0 失败、0 错误、0 跳过：原有 19 项继续通过，新增 Repository 2 项验证创建仅一次 insert、无后续查询、使用回填实体及失败异常原样传播。

真实 Testcontainers PostgreSQL 17.6 / pgvector 0.8.0 验证 ASSIGN_ID 回填正数 Long、应用创建时间非空及与数据库保存值之差小于 1 微秒（考虑 JDBC/PostgreSQL 舍入）、创建后查询、同名允许、失败插入无新增记录及后续创建恢复。原真实 HTTP 前缀、201/Location、Long 字符串和错误状态映射回归均通过。

已审阅完整 diff，未修改迁移、实体、Mapper、依赖或其他 M1 模块。未执行全量测试、ES/MinIO 或真实供应商 API，本任务不涉及这些链路。

本记录更新前两次 M1-02 记录中的创建实现：不再由数据库生成创建时间或以业务事务执行 INSERT+SELECT。创建响应保留应用纳秒精度，后续查询可能因 PostgreSQL 微秒精度出现小于 1 微秒的差异，不承诺时间字符串逐字相等。无已知阻断项，未进入 M1-03。
