# M1-01 数据模型与持久化验证

日期：2026-11-08；分支 feature/m1-ingestion-indexing；起点 dc339cc50c8bb69b239819079dc37e6fd3ec53a8，开始时工作区干净。

## 兼容性检查点

先执行 `mvn dependency:get -Dartifact=com.baomidou:mybatis-plus-spring-boot4-starter:3.5.18 -Dtransitive=true`，成功；再加入项目检查 dependency:tree。

实际解析：MyBatis-Plus 3.5.18、MyBatis 3.5.19、mybatis-spring 4.1.0、Boot 4.1.2、spring-jdbc 7.0.10、HikariCP 7.0.2。未修改原有版本，未发现重复 ORM/JDBC 版本。真实 PostgreSQL 17.6 / pgvector 0.8.0 临时容器上的最小 Mapper 实验通过后才新增 V2。

ID 使用 Long / BIGINT 和 ASSIGN_ID，由 MyBatis-Plus 默认 Snowflake 生成器在服务端插入前生成，数据库无序列或自增。当前仅单应用部署；多实例部署前必须明确 worker/datacenter ID 的分配和时钟回拨策略，不能将单容器实验理解为分布式唯一性证明。

## Schema 与模型

V1 保持不变，V2 创建 knowledge_base、document、index_version、document_chunk、import_task。五个同名驼峰 Entity 各有一个 BaseMapper；枚举为 IndexVersionStatus 和 ImportTaskStatus。复用自动配置与 @Mapper，无 Service、Controller 或通用基类。

时间字段统一 Instant / TIMESTAMPTZ；不带本地时区歧义。向量以明确的 PostgreSQL vector 文本表示，VectorTypeHandler 使用 OTHER 参数绑定。切分配置为 TEXT 快照，具体配置协议留给切分任务。

数据库保证：主键、必填字段、同库内容哈希唯一、同版本顺序唯一、非负顺序、合法偏移区间、固定模型/1024 维、合法状态、非负次数和代际；VERIFIED/PUBLISHED 要求正 Chunk 数。构建时 embedding 可空；active_version_id 初始可空。索引覆盖版本文档关联、Chunk 知识库/文档及任务关联、重试与租约扫描。没有外键或触发器。

## 后续业务不变量

数据库不保证知识库存在、Document/IndexVersion/Chunk/Task 归属一致、active_version_id 属于文档；负面测试主动写入错误引用并确认数据库接受，表示边界暴露，不能标记业务校验通过。

后续创建事务必须校验关联并原子创建 Document/Version/Task；发布事务必须校验归属、有效租约、claim_epoch、VERIFIED、向量齐全、双索引验证事实及 active_version_id 为空。配置创建后不可变、发布后数据不可写、状态转换合法性也由事务与条件更新保证。本次没有实现这些流程，也未用复杂触发器替代。

claim_epoch 仅预留 fencing 字段，不代表 fencing 已实现，更不防止旧 Worker 覆写 ES。文档容量 1000 的并发裁决留在 M1-03 创建业务事务实现；本次未引入计数 Service。

## 实际验证

- Spotless apply/check、Java 主代码及测试编译成功。
- MybatisCompatibilityTests：真实容器，1 项聚合测试，CRUD、参数绑定、Snowflake、事务回滚；V1→V2、重复迁移、五表 BIGINT、vector(1024)、1024 维插入/错误维度、同库去重/跨库重复、重复顺序、非法偏移/状态、无外键和错误归属负面边界。
- DatabaseIT：2 项，0失败、0错误、0跳过；真实迁移、向量运算、重复迁移及失败迁移阻止启动。
- 未调用真实模型，无 API Key 依赖；未执行 ES/MinIO 测试和全量测试，修改不涉及这些链路。

测试只证明约束与最小持久化路径；不证明解析器码点映射、Worker、首次发布、检索或 RAG 质量。没有启动 M1-02。
