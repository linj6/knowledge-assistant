# 本地模型配置

## 首次配置

在项目根目录操作。确认 `config/application-local.yml` 不存在后，将
`config/application-local.yml.example` 复制到该路径；已有文件应手工补齐，不能覆盖。
只在私有文件中替换占位符。Embedding 基线使用
`https://dashscope.aliyuncs.com/compatible-mode/v1`、`text-embedding-v4`、1024 维。
Chat Model 填写账号支持的模型 ID；Rerank 未使用时保持关闭。
模板值均为占位符；dimensions 替换为整数 1024（不加引号）。

## 统一加载和运行

公共配置为 `src/main/resources/application.yml`。Boot 4.1.2 的 ConfigData 标准搜索路径
包含工作目录下的 `config/`，只有激活 `local` 才加载 `application-local.yml`。
不需要 import、dotenv 或 Maven 资源复制；私有文件在资源目录之外，不打入应用 JAR。
在线测试使用同一 ConfigData 和 Binder，绑定 ModelProperties / RerankProperties，
不启动应用上下文、数据库或 Flyway。命令必须在项目根目录运行。

IDEA：JUnit 运行配置的 Working directory 设为项目根目录，VM options 仅填写
`-Dspring.profiles.active=local`。选择具体 OnlineIT；Maven Profile `online-model`
不是 Spring Profile `local`，二者不能混用。

Maven 和 Codex CLI 使用相同命令。以下在线命令**仅在另外获得明确调用授权后**执行：

```powershell
mvn '-Dspring.profiles.active=local' '-Dtest=EmbeddingBaselineOnlineIT' test
# 通用 Chat/Embedding 与 Rerank 验证（会产生额外请求）
mvn '-Dspring.profiles.active=local' -Ponline-model test
```

Codex CLI 在项目根目录执行上述 Maven 命令，不需要另外填写 API Key 环境变量。
普通离线验证执行 `mvn test`，不要激活 local，不要选择 OnlineIT。

## 在线授权

配置密钥不会自动执行请求，默认 Surefire 只运行 `*Tests`。
每次在线操作仍需人工明确授权；配置中的布尔值不能替代任务授权。
模型调用同时要求 `assistant.model.enabled` 和 `assistant.model.online-authorized` 为 true；
Embedding 基线额外要求 `assistant.embedding.baseline-authorized=true`。
Rerank 同时要求 `assistant.rerank.enabled` 和 `assistant.rerank.online-authorized` 为 true。
操作结束后将授权恢复 false。不要为了配置 API Key 把授权一并打开。

兼容环境变量分别为 ASSISTANT_MODEL_ENABLED、ASSISTANT_MODEL_ONLINE_AUTHORIZED、
ASSISTANT_EMBEDDING_BASELINE_AUTHORIZED、ASSISTANT_RERANK_ONLINE_AUTHORIZED；
其含义与上述属性一致。Boot 的环境变量和 JVM 属性优先于 YAML；旧环境变量会覆盖本地配置，
建议移除过时设置。维度通过 `assistant.model.dimensions`（或 ASSISTANT_MODEL_DIMENSIONS）绑定。

## 排查与安全

- 配置未生效：检查工作目录、local Profile、文件扩展名和旧环境变量。
- 授权失败：检查对应 enabled 和独立授权项；禁止绕过门禁。
- 缺少密钥：已授权且模型启用时会提示 `assistant.model.api-key is required when enabled`。
- YAML 或绑定失败：检查缩进及数值类型；错误仅输出安全说明，不打印配置值和原始异常链。
- 基线拒绝配置：检查上述固定模型、1024 维和端点；不会自动切换模型。
- 不将密钥写入 pom.xml、JVM 参数、命令、文档、对话或日志；不截图私有文件。
- 提交前执行 `git check-ignore config/application-local.yml`，只暂存具体任务文件，
  禁止强制添加私有配置。不要把真实密钥传给测试断言或测试报告。

本次实现不修改模型 SDK、依赖、业务数据库或冻结架构文档。
Embedding 历史报告仍包含之前的配置阻断记录；用户提供的 IDEA 四次成功结果应在独立基线归档任务中整理，
本配置任务不重新调用供应商或宣称已经冻结基线。

## 本次离线验证（2026-11-07）

- `mvn spotless:apply`、`mvn spotless:check -DskipTests test-compile`：通过。
- `mvn test`：48 项通过；随后新增两项安全用例。
- `mvn -Dtest=OnlineAuthorizationTests,RerankTests test`：14 项通过，包含最终六项配置授权测试。
- ConfigData 临时目录实测：未激活 local 不读取私有文件，激活后可读取；模型 ID 和维度绑定正确。
- 授权关闭、完整配置未授权、基线额外授权、缺少密钥、非法绑定及 YAML 安全异常均已验证。
- `.gitignore` 原有规则已覆盖私有文件，示例未被忽略；未读取、创建或覆盖真实本地配置。
- 本次真实供应商请求为 0；未运行 Docker、基础设施 IT 或完整 `clean verify`。
- IDEA 未进行 GUI 实跑；统一规则由在线入口共享 ConfigData/Binder 保证，需保持上述工作目录。
