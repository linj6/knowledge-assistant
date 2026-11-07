# 本地开发与多环境配置

## 首次配置与启动

在项目根目录操作。仅当 `config/application-local.yml` 不存在时，从
`config/application-local.yml.example` 复制；已有文件手工合并，禁止覆盖。
填写本地 PostgreSQL 密码（与 Compose 的 KA_INFRA_PG_PASSWORD 一致）和模型 API Key。
模型默认关闭，启动普通应用不需要模型密钥。数据库需由操作者预先准备，本任务不自动启动 Docker。
不要将密钥放入 JVM 参数、命令行、pom.xml、Git、对话或日志。

IDEA：应用运行配置 Working directory 为项目根目录，Active profiles 填 `local`，
或使用 VM options `-Dspring.profiles.active=local`。
Maven 与 Codex CLI 在项目根目录执行相同命令：

```powershell
mvn spring-boot:run '-Dspring-boot.run.profiles=local'
```

## 各环境职责

| 文件 | 职责 |
|---|---|
| `src/main/resources/application.yml` | 应用名、Flyway、30 秒通用超时；模型及所有在线授权默认关闭，无默认激活 Profile |
| `src/main/resources/application-local.yml` | PostgreSQL localhost:5432/ka、用户名 ka；密码留空需个人配置；DashScope 北京端点、Chat 候选 qwen-plus、Embedding text-embedding-v4 / 1024；Rerank 关闭 |
| `src/main/resources/application-test.yml` | 模型及在线授权关闭，不提供真实密钥，不禁用数据库自动配置 |
| `src/main/resources/application-prod.yml` | 数据库 URL/用户名/密码来自 KA_DB_URL / KA_DB_USERNAME / KA_DB_PASSWORD，无本地回退；启用模型时部署必须提供有效 Endpoint、API Key 和对应模型 ID |
| `config/application-local.yml` | 私有密码、API Key、个人覆盖，Git 忽略，不打包 |
| `config/application-local.yml.example` | 可提交的最小私有配置模板，字符串仅占位符、开关默认 false |

生产模型变量为 ASSISTANT_MODEL_ENABLED、ASSISTANT_MODEL_BASE_URL、ASSISTANT_MODEL_API_KEY、
ASSISTANT_MODEL_CHAT_MODEL、ASSISTANT_MODEL_EMBEDDING_MODEL、ASSISTANT_MODEL_DIMENSIONS。
模型关闭时允许空密钥；启用时会校验密钥及端点，创建 Chat/Embedding 客户端时分别校验对应 ID，
Embedding 额外校验维度范围。完整应用当前仍创建两个客户端，启用时需两个 ID；独立 Embedding 测试不需要 Chat 占位值。
生产 Rerank 同样默认关闭，启用需部署提供 endpoint、api-key，模型为 qwen3-rerank。

当前没有 Elasticsearch / MinIO 的应用配置消费者，不新增装饰性 YAML。
现有 Compose 地址分别为 http://localhost:9200 和 http://localhost:9000（控制台 :9001），
待相应客户端任务实现时再添加正式属性。基础设施密码仍按 Compose 说明配置，不由应用 YAML 驱动 Compose。

## 加载顺序与隔离

Boot ConfigData 标准加载：classpath 公共 → classpath Profile → 外部公共 → 外部 Profile；
JVM 系统属性、环境变量通常优先于 YAML，应用命令行参数优先级更高。
在线 JUnit 测试无应用命令行解析，使用相同 ConfigData/Binder，不创建完整应用上下文。
同名外部 local 文件只覆盖填写的字段，未填写的模型 ID、维度继续继承 classpath local。
local 文件只在激活 local 时读取；prod/test 不读取它。不要同时激活 local 与 prod/test，
也不要用 spring.config.import 显式导入私有文件。不要新增外部 application.yml 保存密钥，
它会跨 Profile 加载。旧环境变量可能覆盖个人配置，排查时移除过时设置。

## 离线和在线测试

```powershell
mvn test
```

默认 Surefire 仅包含 `*Tests`，不运行 OnlineIT；离线上下文测试显式使用 test Profile，
并在测试注解中排除数据库/Flyway 自动配置。test Profile 本身保留数据库自动配置，
数据库 IT 的容器命令行连接或后续 DynamicPropertySource 可优先覆盖连接，禁止依赖私有配置。
不要给普通离线测试配置 local。基础设施 IT 需要单独授权，本任务未运行。

在线调用必须另外获得明确授权；有 API Key 不等于授权。
私有配置中模型需 `assistant.model.enabled=true` 与 `assistant.model.online-authorized=true`；
Embedding 基线另需 `assistant.embedding.baseline-authorized=true`；
Rerank 需自身 enabled 与 online-authorized 均为 true，另填 endpoint、api-key。
完成后恢复所有授权 false。

IDEA 选择具体 OnlineIT，VM options 填 `-Dspring.profiles.active=local`。
Maven / Codex CLI 仅获授权后可执行：

```powershell
mvn '-Dspring.profiles.active=local' '-Dtest=EmbeddingBaselineOnlineIT' test
# 会调用 Chat、Embedding 和 Rerank，需分别获得授权和配置
mvn '-Dspring.profiles.active=local' -Ponline-model test
```

Maven 的 online-model Profile 与 Spring local Profile 是两回事。
Embedding 基线固定北京端点、text-embedding-v4、1024 维，20 秒超时，最多四次，不调用 Chat。
已有验证报告的历史记录不在本配置任务改写，后续单独归档用户提供的 IDEA 成功证据。

## 排查与密钥保护

- 未生效：检查项目根目录、Profile、文件名、旧环境变量；不要打印全部 Environment。
- 缺少 API Key：启用时提示 `assistant.model.api-key is required when enabled`，不包含值。
- 授权失败：检查 enabled 与独立授权项；不能绕过门禁。
- YAML/绑定失败：在线加载器只给安全说明，去掉可能含私有值的原始异常链。
- prod 缺少数据库变量：必须提供部署变量，不允许退回本地地址或凭据。
- 模型 ID 不代表已获供应商能力验证，qwen-plus 是本地候选；基线归档另行处理。
- 提交前运行 `git check-ignore config/application-local.yml`；classpath local 和 example 可提交。
  仅暂存明确任务文件，不使用强制添加私有配置。

离线验证使用临时目录和固定假密钥，不读取真实私有文件；IDEA GUI 和生产部署未实际执行。

## M1-01-CONFIG 离线验证（2026-11-07）

- `mvn spotless:apply`、`mvn spotless:check -DskipTests test-compile` 通过。
- `mvn test`：55 项，0 失败、0 错误、0 跳过；未运行任何 OnlineIT。
- 临时假密钥文件验证 classpath local 默认值、外部同名 local 覆盖、未填写字段继承，
  以及 test/prod/无 Profile 的私有文件隔离；prod 缺少数据库变量时无本地回退。
- 模型独立校验、缺少密钥的安全错误、在线门禁及非法配置安全异常均通过。
- Git 忽略规则测试确认私有配置被忽略，classpath local 与 example 可提交。
- 未读取或覆盖已有私有文件；未执行真实供应商请求、Docker、生产部署或 IDEA GUI。
- Testcontainers 数据库连接优先级仅通过配置覆盖测试和现有 DatabaseIT 代码检查，未启动容器实测。
