# M1-01-CLEANUP 验证记录

日期：2026-11-08。分支 feature/m1-ingestion-indexing，起点 07f12f6f8745baf6cd994c3410fef96ab0cdabfd，开始时工作区干净。

## 修改范围

- 五个 Entity 类及全部业务字段补齐中文 JavaDoc；两个状态 Enum 和全部枚举值说明业务语义及事务校验边界。
- 五个 Entity 使用 Lombok @Getter/@Setter 替代手写访问器，未生成 equals/hashCode/toString，未变更字段类型及 MyBatis-Plus/TypeHandler 映射注解。Mapper 和 VectorTypeHandler 实现保持不变。
- pom.xml 新增 provided Lombok 和编译器 annotationProcessorPaths，版本复用 Boot BOM，实际解析为 1.18.48。Java 21.0.10 / Boot 4.1.2 下编译通过；没有升级原有依赖。
- V3 仅使用 COMMENT ON TABLE / COMMENT ON COLUMN，为五表及全部列补充中文说明；V1/V2 未改，类型、约束、索引和数据不变。
- Code Style 明确 Controller → Service → Repository → Mapper 职责；Repository 按真实持久化语义引入，不机械转发、不创建空壳。

## 实际执行

- `mvn spotless:apply`
- `mvn spotless:apply spotless:check -DskipTests test-compile`：成功；主代码与测试重新编译，确认 Lombok 注解处理生效。
- `mvn -Dtest=MybatisCompatibilityTests,DatabaseIT,KnowledgeAssistantApplicationTests,ProfileConfigurationTests,ConfigurationTests test`：14 项，0失败、0错误、0跳过。
- `mvn help:evaluate -Dexpression=lombok.version -q -DforceStdout`：1.18.48。
- `git diff --check`：通过；完整 diff 审阅确认仅上述范围。

真实 Testcontainers 使用仓库原有固定 pgvector 镜像：PostgreSQL 17.6 / pgvector 0.8.0。DatabaseIT 与 MybatisCompatibilityTests 共 3 项聚合数据库测试，覆盖空库 V1→V2→V3、同库重启及重复迁移不执行、失败迁移阻止启动、所有表及实际列注释非空；原有约束测试继续通过。新增 Lombok 实体向量 Mapper 插入/读取、实体更新事务回滚；原有 Snowflake ID 和 Instant 往返断言通过。

未执行全量测试、ES/MinIO 集成或真实模型请求，本次不涉及对应链路。测试不代表后续关联归属、发布不可变或 Worker fencing 已实现；M1-01 原有业务边界仍适用。未开始 M1-02。
