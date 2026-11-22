# Embedding 分批与集中启动配置日志验收

## 基线与范围

- 分支：`feature/web-ui-v1`。
- HEAD：`ba9e32be2c30547947c60806074a0788403e852c`。
- Java 21、Spring Boot 4.1.2、Spring AI 2.0.2；不升级依赖。
- 保留用户已有 application-local.yml 变更、验证文档及 infra/web06c，不修改真实配置或业务数据。

## 故障原因与限制依据

真实任务生成 27 个 Chunk。应用原批大小为 16，调用 text-embedding-v4 时收到 HTTP 400，供应商明确要求 input.contents 不超过 10 条。根因是应用批大小被当作供应商能力上限使用，而不是 Chunk 大小、维度或密钥错误。

官方依据（本轮核对）：

- [百炼向量化文档](https://www.alibabacloud.com/help/zh/model-studio/embedding)：text-embedding-v4 每次请求最多 10 条文本。
- [百炼同步 API](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)：模型版本间的批次限制不同；v4 文本列表最多 10 条。

不把其他产品的接口限制混用，不推测其他模型的能力。

## 原实现审查

- EmbeddingProperties 从 `assistant.embedding` 绑定，原默认/应用最大批大小均为 16，校验范围 1–16。
- EmbeddingService 读取版本和有序 Chunk；校验索引模型/维度与实际 OpenAiEmbeddingModel options 相同；过滤已持久化向量，然后按应用配置批大小串行调用。
- 每批校验返回数量、index 唯一性/范围、1024 维及有限 float；按返回 index 填回对应 Chunk，不依赖返回数组顺序。
- 模型调用与响应校验的异常仍由 ModelFailures.safe 包装并保留 Cause；PG 写入异常沿用原传播方式。
- EmbeddingRepository.write 原本每批独立事务，Document → IndexVersion 锁顺序不变；失败批次回滚不影响此前已提交批次。服务重入会跳过已经持久化向量。
- Worker 沿用原 errorCode/attempt/backoff/租约逻辑。HTTP 400 REQUEST_REJECTED 仍映射 EXECUTION_FAILED；SERVER/CONNECTION 等分类和重试语义不变。

### Spring AI 与 SDK 实际行为

检查了本地 Maven 中 **spring-ai-openai-2.0.2-sources.jar** 的 OpenAiEmbeddingModel、OpenAiEmbeddingOptions、OpenAiSetup 和 SpringAiOpenAiHttpClient：

- 本项目调用的 `OpenAiEmbeddingModel.call(EmbeddingRequest)` 把整批 instructions 生成参数，然后调用一次 SDK `embeddings().create(...)`；该入口没有按供应商的 10 条上限拆分输入。
- 这不等于声明 Spring AI 所有入口都没有批处理；本项目未使用其他批处理入口。
- ModelConfiguration 原有 `maxRetries(0)` 经 OpenAiSetup 传入 SDK，保持不变。本地 HTTP 500 测试验证了没有 SDK API 重试或应用缩批补调。
- SpringAiOpenAiHttpClient 的 OkHttp 构建器仍使用 **retryOnConnectionFailure(true)**。因此不能断言一次 call 在所有网络故障下只有一个物理 HTTP exchange。本轮不改变既有传输恢复行为；即使传输恢复，每份请求输入仍被应用限制在有效批大小内。
- Spring AI 对供应商省略原生 index 的响应会回退为位置 index；本轮保留该既有行为，应用继续验证 Spring AI 返回的数量、索引和维度。

## 新批次策略

保留现有应用容量保护，不把它当作供应商能力：

```text
configuredBatchSize = assistant.embedding.batch-size（仍为 1–16，默认 16）
modelMaxBatchSize = 已核实上限或显式模型限制
effectiveBatchSize = min(configuredBatchSize, modelMaxBatchSize)
```

- text-embedding-v4 内建上限 10；显式配置可以更保守，但不能把已核实上限抬高。
- 其他模型必须在 `assistant.embedding.model-max-batch-sizes` 明确配置正整数上限；缺失时在实际模型请求前拒绝执行，不盲目使用 16。
- 不引入策略类、工厂、数据库或注册中心。配置仅由简单 Map 管理。
- 未知模型的配置示例（8 仅表示开发者自行核验后的假设值，不是任何真实模型的官方限制）：

```yaml
assistant:
  embedding:
    batch-size: 16
    model-max-batch-sizes:
      my-verified-model: 8
```

- 非法应用批大小、空模型名、非正模型上限明确报错。
- 模型限制依据版本快照模型名解析；原客户端快照校验保留。
- 不改变原来的 800 码点单 Chunk 限制、维度、Embedding options、发布规则或状态机。
- 未新增失败后动态缩批和额外调用。历史失败任务不自动重试或重新入队。

## 分批及日志

27 个 Chunk、配置 16 时，本机 HTTP Stub 捕获的真实客户端请求为 **10、10、7**，文本顺序保持一致。配置为 4 时为 **4、4、4、4、4、4、3**。

- 反向排列响应的 index，仍逐条匹配正确 Chunk 和向量。
- 第二批 HTTP 500 或合成 PG 写入失败时：第一批 10 条保留，后 17 条未写入。
- 恢复执行的请求为 10、7；整个失败/恢复过程请求大小为 10、10（失败）、10、7。再次执行不发出请求。
- 仓库事务逻辑未改；离线假仓库模拟批次提交，真实数据库集成用例只更新断言并编译，未执行。

新增事件：`EMBEDDING_BATCH_STARTED`、`EMBEDDING_BATCH_COMPLETED`、`EMBEDDING_BATCH_FAILED`。

字段：documentId、indexVersionId、model、batchIndex、batchCount、batchSize、elapsedMs。batchIndex/batchCount 以本次待处理 Chunk 集合为准，恢复执行重新编号。

批次失败事件只记录摘要，原异常原样继续传播给现有 Worker 失败边界打印完整堆栈和 Cause 一次；以 documentId/indexVersionId 与带 taskId 的 Worker 事件关联。完成事件在原写入与截止时间检查后记录，不以日志伪造整体成功。不新增输入文本或向量数组日志。

## 集中启动配置日志

新增 StartupConfigLogger，唯一 ApplicationReadyEvent 监听器，通过原子标记避免重复输出。保留 WorkerStatusLogger，移除其独立 Ready 监听，由集中组件调用；原 Worker 信息合并为 APP_CONFIG / WORKER，不双重打印。

读取最终 Properties Bean 和 Environment，不读 YAML 文件、不连接外部服务、不调用模型、不领取或处理任务。

| section | 实际输出字段 |
| --- | --- |
| APPLICATION | applicationName、activeProfiles、defaultProfiles、实际 local.server.port（缺失时配置端口/默认值）、contextPath、servletPath、javaVersion |
| WORKER | enabled、processorAvailable、scanIntervalMs、batchSize、leaseDuration、maxTaskDuration、maxAttempts、retryBackoff、retryJitter |
| EMBEDDING | modelEnabled、onlineAuthorized、modelName、dimensions、configuredBatchSize、modelMaxBatchSize、effectiveBatchSize、timeoutSeconds、baseUrl |
| CHAT | modelName、modelEnabled、timeoutSeconds |
| RERANK | enabled、onlineAuthorized、modelName、endpoint、timeoutSeconds |
| POSTGRESQL | datasourceUrl、datasourceDriver、datasourceUsername、flywayEnabled |
| ELASTICSEARCH | enabled、endpoint、现有 EsIndexService.INDEX |
| MINIO | endpoint、bucket |
| UPLOAD_CHUNKING | maxFileBytes、chunkingStrategy、chunkSize、chunkOverlap、chunkingVersion |
| RETRIEVAL | 从 RetrievalParameters.defaults() 读取五项默认参数：vectorTopK、bm25TopK、rrfK、fusionTopK、finalTopK |
| RAG | ContextProperties 的 Token Budget、字符上限；现有 Prompt 版本 |

不为打印私有 TopK 校验范围和代码内策略默认值新增配置或复制魔数。未配置的普通字段显示 UNSET；没有显式 datasource driver 时不推测实际驱动，也不为了日志创建连接。未知/未配置模型上限显示 UNCONFIGURED / UNAVAILABLE，实际执行前仍强制校验。单个日志分组读取失败只标记 UNAVAILABLE，不阻止启动，也不改变原有配置校验。

onlineAuthorized、enabled 是实际配置标志，不代表外部服务已通过健康检查；本轮不改变它们原有的业务含义。

## 凭据保护

- 不读取模型/重排 API Key getter，不读取数据库密码或 MinIO Secret/Access Key，不输出 Properties 对象。
- URL 经过 URI 解析，删除全部用户信息、查询串及 fragment；非法 URL 只输出 INVALID_URL，缺失输出 UNSET。不以脱敏占位符展示认证字段。
- 查询串整体省略，包括普通连接参数，避免遗漏未知认证参数；日志主要用于确认连接目标。
- 复用现有 FailureLog 凭据规则及 Logback 输出保护；完整异常仍仅在原失败边界记录。
- 测试使用假凭据验证 PG、模型、ES、MinIO、Rerank URL 不泄露凭据；密钥 getter 设置为一旦调用即失败，报告过程未调用。
- 保留原日志系统的覆盖边界：任意编码的未知凭据及绕开默认配置的自定义输出仍需专项审查；本次不启用 HTTP wire 日志。

## 测试与验收

- 新增 EmbeddingBatchingTests：6 项（含参数化实例）；StartupConfigLoggerTests：3 项，共新增 9 项。
- 定向回归：50 项，0 失败、0 错误、0 跳过。
- 全量离线 Java：52 个测试类、365 项，0 失败、0 错误、0 跳过。
- Spotless apply/check、test-compile、Maven test 绑定的格式检查和编译通过。
- 更新 EmbeddingPersistenceIT 的分批断言为 10、7、7 及首批持久化 10 条；只编译，不执行真实 PG/Testcontainers 测试。
- 全量离线明确排除 DocumentLifecycleMigrationTests、DocumentQueryPersistenceTests、ImportPersistenceTests、KnowledgeAssistantApplicationTests、KnowledgeBasePersistenceTests、MybatisCompatibilityTests。所有 *IT/在线 Profile/Failsafe 不执行。
- 不调用真实模型，不启动真实 Worker，不上传文件，不启动 Docker，不读写实际业务数据库。
- git diff --check 通过；新增文件另检查行尾空白。

## 已知限制及审查结论

- 当前数据库仍约束 text-embedding-v4 / 1024。批次能力配置可扩展到其他模型，但不宣称任意模型已经能直接切换现有完整索引链；涉及数据库/维度演进仍需独立授权和迁移。
- 应用批容量保护仍为 16；未来需要更大应用批次时应同时审查持久化与资源保护，不在本轮放宽。
- 未执行真实基础设施验证，不代替供应商生产验证；不恢复此前失败任务。
- 完整代码审查确认：仅改变模型批次大小决策并增加观测；原事务边界、模型错误码、状态机、发布、重试、Prompt、前端和数据库均不改。
- READY TO COMMIT：是，仅限下列本任务文件；不自动提交或推送。

## 本任务文件

- src/main/java/com/lnjecit/knowledge/assistant/ingestion/embedding/EmbeddingProperties.java
- src/main/java/com/lnjecit/knowledge/assistant/ingestion/embedding/EmbeddingService.java
- src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/WorkerStatusLogger.java
- src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/StartupConfigLogger.java
- src/test/java/com/lnjecit/knowledge/assistant/EmbeddingServiceTests.java
- src/test/java/com/lnjecit/knowledge/assistant/EmbeddingPersistenceIT.java
- src/test/java/com/lnjecit/knowledge/assistant/EmbeddingBatchingTests.java
- src/test/java/com/lnjecit/knowledge/assistant/StartupConfigLoggerTests.java
- docs/engineering/embedding-batching-and-startup-config-review.md

## Git 检查

`git status --short`：

```text
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/embedding/EmbeddingProperties.java
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/embedding/EmbeddingService.java
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/WorkerStatusLogger.java
 M src/main/resources/application-local.yml
 M src/test/java/com/lnjecit/knowledge/assistant/EmbeddingPersistenceIT.java
 M src/test/java/com/lnjecit/knowledge/assistant/EmbeddingServiceTests.java
?? docs/engineering/embedding-batching-and-startup-config-review.md
?? docs/engineering/web-06-c-isolation-validation.md
?? docs/engineering/web-06-c-preflight.md
?? infra/web06c/
?? src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/StartupConfigLogger.java
?? src/test/java/com/lnjecit/knowledge/assistant/EmbeddingBatchingTests.java
?? src/test/java/com/lnjecit/knowledge/assistant/StartupConfigLoggerTests.java
```

`git diff --stat`：

```text
 .../ingestion/embedding/EmbeddingProperties.java   | 26 +++++++
 .../ingestion/embedding/EmbeddingService.java      | 91 ++++++++++++++++++----
 .../ingestion/task/WorkerStatusLogger.java         | 12 +--
 src/main/resources/application-local.yml           |  2 +-
 .../assistant/EmbeddingPersistenceIT.java          |  4 +-
 .../knowledge/assistant/EmbeddingServiceTests.java | 13 ++--
 6 files changed, 119 insertions(+), 29 deletions(-)
```

`git diff --check` 退出码 0，无输出。普通 diff --stat 不计新增文件：本任务 5 个已跟踪修改 + 4 个新增文件，共 9 个；统计中的 application-local.yml 为用户原有变更，不属于本任务。
