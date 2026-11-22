# M1-03 数据库表名规范化验收

日期：2026-11-08；分支 `feature/m1-ingestion-indexing`；基线 `cc0e8005e216fbcc84640fc71ac1976430bba005`，初始工作区干净。

## 变更与兼容边界

为明确文档生命周期数据归属，新增 `V4__rename_document_lifecycle_tables.sql`：

| V1–V3 物理表名 | V4 起物理表名 | Java 实体 / Mapper |
|---|---|---|
| index_version | document_index_version | IndexVersion / IndexVersionMapper |
| import_task | document_import_task | ImportTask / ImportTaskMapper |

V1、V2、V3 原文件保持不变。V4 仅使用 PostgreSQL 原生 RENAME：重命名两张表、10 个主键/CHECK 约束及五个普通索引。主键约束重命名自动同步其索引名称，不重复创建索引。普通索引采用 `idx_document_index_version_document` 和 `idx_document_import_task_{document,version,retry,lease}`。

不重建、复制业务表或数据，不改变字段名、类型、默认值、CHECK、UNIQUE 语义、ID 策略和状态机，不新增外键。尤其 `document` 的同库内容 UNIQUE、`document_chunk.index_version_id`、任务的 `index_version_id` 字段均保持不变。

生产代码仅更新两个 Entity 的 @TableName。Mapper 继续通过 MyBatis-Plus 映射生成 SQL，Repository 和 Service 业务代码没有改动。更新 MybatisCompatibilityTests、ImportPersistenceTests 中当前 SQL 引用；旧名称仅保留在历史迁移及升级前数据准备/旧表不存在断言中。

已检查仓库显式引用及 V3 真实系统目录：两表没有序列、非内部触发器、视图或物化视图需要额外处理。V4 保持对象 OID 及依赖关系。冻结 Architecture/Product Spec 不改写；其中旧物理表名由本工程说明补充映射。历史验收记录保留原事实，M1-03 主验收文档增加本说明的链接。

## 验证设计

- 全新数据库：现有 MybatisCompatibilityTests、KnowledgeBasePersistenceTests、ImportPersistenceTests 自动执行 V1→V4；检查四条成功迁移、字段与注释、CHECK/UNIQUE、ASSIGN_ID、真实 Controller 导入、三表关联、并发去重、事务回滚和即时补偿。
- V3 升级：DocumentLifecycleMigrationTests 先用 Flyway target=3 创建 V3 数据库，经旧表名写入知识库、文档、索引版本和任务，再启动应用自动执行 V4。比较全部已有行、字段类型/默认值/非空和列注释、约束 OID/定义、索引 OID/键/操作符类/有效性/唯一性/谓词，验证仅改名称且表 OID 不变。
- 升级后确认新表存在、旧表不存在，主键/CHECK/普通索引均为新前缀；通过新 Entity/Mapper 读取升级前记录及关联、模型参数和真实 ChunkingConfig 快照。重复 migrate 不执行任何迁移，V4 成功记录只有一条。

## 实际执行结果

```powershell
mvn spotless:apply
mvn spotless:check
mvn -DskipTests test-compile
mvn "-Dtest=DocumentLifecycleMigrationTests,MybatisCompatibilityTests,KnowledgeBasePersistenceTests,ImportPersistenceTests,ImportTests,ImportApiTests" test
mvn "-Dit.test=DatabaseIT" failsafe:integration-test failsafe:verify
git diff --check
git diff --cached --check
```

上述命令均成功。局部测试共 10 项通过（存量升级 1、MyBatis 兼容与空库迁移 1、知识库持久化 1、导入持久化/真实 HTTP 2、导入单元 4、Controller 协议 1），0 失败、0 错误、0 跳过。DatabaseIT 2 项通过，0 失败、0 错误、0 跳过，包含重复迁移及迁移失败阻止启动的既有检查；其中故障注入的预期启动失败日志不代表测试失败。

真实测试使用 Testcontainers 固定 `pgvector/pgvector:0.8.0-pg17` digest 镜像，实际 PostgreSQL 17.6。存量升级实际保留 V3 全部已有版本/任务行、字段默认值、注释、CHECK/主键和索引语义；新 Mapper 读取原 ID/归属/模型/切分快照成功。V4 成功记录为一条，重复迁移数为零。新表上的同库 UNIQUE、跨库导入、并发裁决、三表回滚及补偿测试全部通过。

未执行全量测试、MinIO 真实读写、ES 或供应商请求：本次不改变这些链路；导入回归使用内存存储作故障注入，不将其记为本次 MinIO 验证。已审阅完整 diff，历史 V1–V3 没有变化，无新增阻断问题。

## 风险与非目标

表重命名需要 PostgreSQL 表锁，应在维护窗口随应用映射一起发布；V4 后旧应用 SQL 不再兼容，不能混跑新旧应用。未对操作者现有 Compose 数据库执行迁移，也未模拟生产锁等待或提交应答丢失。用户自行添加、未纳入 Flyway 的外部 SQL 或自定义数据库对象需部署前另行核查。

本次未修改 API、MinIO、ChunkingConfig、模型配置或业务方法签名，不实现解析、Chunk、Worker、发布或清理功能，未进入 M1-04。
