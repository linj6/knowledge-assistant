# M0-05 数据库验证

验证日期：2026-11-07；分支 feature/m0-foundation。

## 版本与配置

实际 Maven 依赖：Boot 4.1.2、Flyway core/database-postgresql 12.4.0、
PostgreSQL JDBC 42.7.14。使用 spring-boot-starter-jdbc 和
spring-boot-starter-flyway；后者引入 Boot 4 独立 spring-boot-flyway 模块，
自动配置类为 org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration。
不是仅添加 flyway-core 后沿用 Boot 3 自动配置。

KA_DB_URL 默认 jdbc:postgresql://localhost:5432/ka，KA_DB_USERNAME 默认 ka；
KA_DB_PASSWORD 必须由环境提供。应用与 Flyway 共用自动配置 DataSource。
不设置 Flyway 独立连接、不 baseline、不跳过迁移。Flyway 初始化异常阻止上下文启动。
Compose 的 .env 不会由 Spring Boot 自动读取，启动前需自行导出环境变量。

## 实际命令与结果

- git fetch origin：成功，本地 HEAD 与远端 feature/m0-foundation 一致，开始时工作区干净。
- mvn -B dependency:tree：成功，版本如上。
- docker start knowledge-assistant-postgres-1：启动已有 M0-03 容器，无卷变更。
- docker exec knowledge-assistant-postgres-1 psql -U ka -d ka：查询 version()，实际 PostgreSQL 17.6。
- 迁移前 to_regclass('public.flyway_schema_history') 为 NULL。
- mvn -B "-Dtest=DatabaseIT" test：2 tests，0 failures/errors/skipped。
- mvn clean verify：默认离线测试及打包成功。
- git diff --check：通过（Windows 行尾提示不属于空白错误）。

真实测试使用本地未跟踪 .env 的 KA_INFRA_PG_PASSWORD 导出至 KA_DB_PASSWORD，
未打印或提交密码。复现时显式设置上述三个 KA_DB_* 环境变量，再执行集成测试命令。

## 首次、重复与向量结果

首次应用上下文启动日志显示成功应用 1 个迁移，到 v1。
public.flyway_schema_history 实查仅一行：
installed_rank=1，version=1，description=enable vector extension，
checksum=-929483003，success=true。
pg_extension 实查 vector extversion=0.8.0。

同一测试调用 Flyway.migrate() 返回 migrationsExecuted=0；后续应用上下文启动
均报告 up to date。历史表仍只有一条 V1，扩展版本未变。
public 的普通表只有 flyway_schema_history，无业务表。

JDBC 在同一 Connection 创建 TEMP vector_probe，插入两个三维向量，
实际余弦距离排序为 (1,0.0)、(2,1.0)，容差 1e-9；连接关闭后临时表清理。

## 失败阻断与隔离

DatabaseIT 创建随机 m005_* Schema，使用独立临时文件目录中的故意错误 SQL。
实际 Flyway 报错，SpringApplication.run 抛出异常，测试断言失败原因包含该 SQL 函数名。
finally 仅 DROP 自建随机 Schema 并删除自建临时文件，不清理 public，不删除命名卷。
没有修改 Docker 全局设置或其他项目容器。

默认 Surefire 不选择 *IT；仅上下文离线测试显式启用 offline profile，
该 profile 在 test resources 排除 JDBC/Flyway 自动配置，不影响正式应用和 DatabaseIT。
其余单元测试继续执行，不需要 Docker 或模型 API Key。
真实数据库测试须显式运行，数据库不可达时失败，不以 Mock 或静默跳过替代。

## 限制

这是 Compose 真实数据库集成测试，不是 Testcontainers；M0-07 再统一测试基础设施。
集成测试会在指定数据库执行正式 V1，必须仅指向本项目开发数据库。
开发容器由本次启动后保持运行。仅验证当前本地版本和权限，不代表生产环境验证。
历史迁移成功后不得修改；遇到不兼容历史必须停止调查，不 repair/clean/baseline。
