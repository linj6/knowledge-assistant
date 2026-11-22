> 本文为历史验收记录，旧机制描述不代表现行规范。

当前 FailureLog 将原始 Throwable 交给 SLF4J，不创建独立诊断副本、不限制 Cause 16 层。默认 logback-spring.xml 的 CredentialRedactingConverter 和 CredentialThrowableConverter 分别对消息与标准完整堆栈输出脱敏，保留 Cause、suppressed 与定位信息。自定义 Appender、额外采集通道及原始日志事件不自动获得相同保障，需单独核验。

# 全项目日志与异常诊断审查

## 范围与基线

- 分支：`feature/web-ui-v1`；基线 HEAD：`7c555e07b16516a019e0c26e797d4f4e2fe54afa`。
- 全局扫描原有 151 个 Java 生产文件，重点逐项审查 29 个含 catch 的文件；新增 3 个日志基础设施类（当前生产 Java 共 154 个）。
- 覆盖 common/Web、knowledge、document、ingestion 上传/解析/Chunk/Embedding/PG Vector/ES/核验/发布/任务、retrieval 四种策略及重排、RAG、基础设施配置/存储/Mapper。
- 搜索 catch、ERROR/WARN、FailureLog、标准输出、异常构造和 Cause；检查请求 ID、任务 ID、事务与异常分类。
- 不修改已有 `application-local.yml` 用户变更、既有验证资料和 infra/web06c。

## 问题清单

以下按根因归并为 7 类，并非每个调用点各算一个问题。

| 优先级 | 问题 | 根因与修复 |
| --- | --- | --- |
| P1 | Worker、Scheduler 只打印固定异常 | 用新异常代替实际异常；恢复传递原 Throwable，Worker 补齐 taskId/documentId/indexVersionId/errorCode |
| P1 | 公共诊断丢失真实类型及完整 Cause | FailureLog 重建 RuntimeException、限制深度、按来源删除消息；改为传递实际异常，完整堆栈交给 Logback |
| P1 | 脱敏无法覆盖直接 SLF4J、启动失败 | 旧脱敏仅作用于 FailureLog；增加默认输出转换器，配置加载后注册凭据 |
| P2 | 多处技术异常包装丢 Cause | 配置校验、解析、模型响应、存储向量校验、快照构造和摘要计算；只增加 Cause，不改类型和消息 |
| P2 | 已处理失败缺诊断或仅固定分类 | RAG 上下文/输出/引用失败及快照业务失败；在转换为结果的边界记录实际异常 |
| P2 | API 内部业务错误只有 WARN | INTERNAL_ERROR 使用统一 ERROR 堆栈；普通参数和业务拒绝保留 WARN，响应协议不变 |
| P2 | 关联身份及阶段不明确 | 元数据告警增加文档/版本身份；检索失败补明确阶段；控制台统一显示并脱敏 MDC requestId |

## 实际调整与模块结论

- 保留 FailureLog，简化为结构化事件、requestId、边界、模型分类、SQLState 和真实异常；不再构造异常副本、截断 Cause 或屏蔽普通异常正文。
- ModelFailures 继续承担现有分类与错误码映射；保留原 Cause，不调整重试分类。
- Worker 原始处理异常记录一次完整堆栈，再沿用原失败更新。失败更新本身异常由 Scheduler 最终边界记录，属于另一个异常；原有结构化失败事件仍保留。
- TaskService、ChunkService、索引/核验/发布、Mapper 的事务及业务算法不变；下层继续包装/传播，由最终边界打印。
- MinIO 上传、复制、读取、清理原本保留 Cause；同步最终失败由 API 边界记录，补偿失败仍是独立诊断，不在存储适配器重复打印。
- ES 的 HTTP、协议、连接异常原本已保留 Cause；并发创建已有资源属于正常控制流，不新增 ERROR。
- VECTOR/BM25 的 Recall 已处理异常由其边界记录，编排接收失败结果时不再次打印；HYBRID 不隐式降级，融合与重排错误单独标识阶段。
- RAG ChatModel 调用仍在生成边界记录一次；RagService 接收 GenerationException 后不重复打印。输出解析保留原 Cause，引用规则不变。
- 知识库创建/查询、文档查询及 Controller 无需逐层加日志；非预期错误交给全局异常处理。正常业务拒绝不强制增加堆栈。
- 未发现生产代码 printStackTrace、System.out/System.err；日志设施故障的 catch 保留，避免影响领域失败和业务结果。

## 保留的日志规范

- ERROR：失败边界使用真实异常对象，保留类型、消息、原始栈位置、Cause 与 suppressed。
- WARN：参数/业务拒绝、恢复、异常状态及补偿状态；关键失败仍有独立堆栈事件。
- INFO：保留上传、领取、阶段完成、发布、检索与 RAG 的既有事件；空任务扫描不增加 INFO。
- 不主动记录完整 Prompt、请求/响应、向量或文档正文。异常自身的正常诊断消息按本次用户决策保留。
- 对外 API 继续仅使用错误码和固定安全消息，内部诊断不改变对外响应。

## 凭据保护及限制

- 默认 `logback-spring.xml` 在控制台编码阶段统一处理已格式化消息及完整异常输出，直接 `logger.error(..., throwable)` 也会经过转换器。
- 消息转换器同时保护 requestId；标准 ThrowableProxyConverter 子类负责完整异常并阻止自动追加未脱敏堆栈。原 Throwable 仍保存在日志事件中；这符合真实异常诊断要求，但 ListAppender、外部采集器、自定义 Appender/Encoder 必须采用相同规则，不能认为内存事件已经脱敏。
- 配置加载后通过 EnvironmentPostProcessor 注册模型、重排 API Key、数据库密码、MinIO Access/Secret Key、ES 密码；Bean 初始化时再次注册。保护原值和 URL 编码值。
- 有限规则覆盖带/不带引号的 api-key/password/passwd/access-key/secret-key/token 字段，Authorization/Cookie 行、Bearer/Basic、URL user:password、签名 URL 常见 X-Amz 字段以及常见凭据 XML 标签。
- 普通异常消息、HTTP 状态、SQLState、真实异常类型、堆栈及完整 Cause 不做删除；规则匹配到的凭据替换为 `[REDACTED]`。
- 不承诺识别任意未知格式、加密/任意编码/切碎/部分回显的凭据；短凭据可能误遮盖普通文本。保留的原始异常可能含供应商回显业务数据，需按新的内部日志策略管理访问。配置尚未加载前的裸值、第三方自行输出到文件/标准错误、绕开本配置的日志后端不在覆盖范围。
- 不启用 HTTP wire/request/response DEBUG；若未来接入新认证来源或输出后端，必须补充注册与真实编码器测试。当前没有将第三方整个 Cause 链删除来换取脱敏。
- 无 raw-exception-enabled 开关，无 Profile 差异。

## 离线验证

- 定向回归：176 项通过；最后的超时/凭据/编码器相关定向回归：24 项通过（范围有重叠，不相加）。
- 全量离线 Java：50 个测试类，356 项，0 失败、0 错误、0 跳过；BUILD SUCCESS。上述定向测试包含在全量范围内。
- `mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile` 通过；最终 Maven test 绑定的 Spotless 检查及测试编译通过。
- `git diff --check` 通过；新增文件额外检查无行尾空白。
- 凭据保护测试覆盖注册值、URL 编码、JSON/带引号字段、Authorization/Bearer/Basic、Cookie、URL 用户信息、签名 URL、XML、多层 Cause/suppressed、直接 SLF4J、默认生产编码器及 requestId，均通过。

全量离线命令：

```powershell
mvn '-Dtest=*Tests,!DocumentLifecycleMigrationTests,!DocumentQueryPersistenceTests,!ImportPersistenceTests,!KnowledgeAssistantApplicationTests,!KnowledgeBasePersistenceTests,!MybatisCompatibilityTests' test
```

全量回归曾发现重排超时测试只接受外层 TimeoutException；实际也可能先收到 ExecutionException → HttpTimeoutException。只调整测试接受这两种合法 Cause，并继续严格断言 TIMEOUT 分类，不修改生产代码。

| 验证点 | 离线测试依据 |
| --- | --- |
| Worker/Scheduler、原 Throwable、关联身份、只打印一次堆栈 | LoggingBoundaryTests、ImportObservabilityTests、IngestionTaskProcessorTests |
| 上传/MinIO/Controller 最终失败 | MinioFailureTests、ImportTests、ImportApiTests、LoggingBoundaryTests |
| Embedding HTTP 401、响应维度非法 | EmbeddingFailureLoggingTests（本机真实 SDK + HTTP Stub） |
| PG Vector/ES 失败链及错误码 | 合成 repository 写入异常、IngestionTaskProcessorTests、EsIndexTests；不是实际 PG/ES 集成验证 |
| 四种检索策略及共享失败只打印一次 | RetrievalServiceTests、RetrievalApiTests、QueryEmbeddingTests、KeywordRetrievalTests |
| RAG ChatModel/Context/Output/Citation | AnswerGeneratorTests、RagReliabilityTests、ContextBuilderTests、CitationValidationTests、RagApiTests |
| 多层 Cause、suppressed、循环、24 层原因链 | FailureLogTests、LoggingBoundaryTests |
| 凭据与默认编码器、直接 SLF4J、配置绑定消息及 requestId | FailureLogTests、LoggingBoundaryTests |
| 分类和协议回归 | IngestionTaskProcessorTests、RerankTests、全量离线 API/模型测试 |

范围：合成异常和假凭据；Embedding 使用 127.0.0.1 HTTP Stub；不调用真实模型，不启动 Worker，不访问或更改真实业务数据。

默认 Surefire 的 `*Tests` 中有 6 个 Testcontainers 类，离线全量明确排除：DocumentLifecycleMigrationTests、DocumentQueryPersistenceTests、ImportPersistenceTests、KnowledgeAssistantApplicationTests、KnowledgeBasePersistenceTests、MybatisCompatibilityTests。所有 `*IT`、在线模型 Profile 和 Failsafe 均不执行，不启动 Docker。

## 诊断示例

以下为合成异常示意，非真实任务根因：

```text
event=FAILURE requestId=null boundary=TaskWorker errorCode=EXECUTION_FAILED taskId=1 documentId=2 indexVersionId=3 kind=INVALID_RESPONSE sqlState=null
com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures$SafeModelException: Model request failed: stage=EMBEDDING_BATCH kind=INVALID_RESPONSE
    at ...EmbeddingService.embed(EmbeddingService.java:...)
Caused by: ...ModelResponseChecks$InvalidResponseException: Invalid embedding response
    at ...ModelResponseChecks.embeddings(ModelResponseChecks.java:...)
```

HTTP、PG 和存储等异常同样保留实际 Cause 和故障语句；`password=...` 等凭据字段输出为 `password=[REDACTED]`。

## 限制与交付判断

- 恢复事件仍是既有汇总事件（recoverNext 返回 boolean），不新增查询去补取任务身份；正常领取、处理、发布和失败链以 taskId 关联。
- 本轮不重试历史任务，不能恢复此前已经丢失的异常证据，也不据此认定历史失败为 API Key 错误。
- 不改变错误码、状态机、Worker 领取/恢复/重试、事务、Embedding 请求、ES 算法、Prompt、引用规则、前端、迁移和数据库。
- 未执行真实基础设施或在线供应商验证；Java 编译已有弃用/unchecked 提示不在本任务中扩展修复。
- READY TO COMMIT：是，仅限本任务 38 个文件；已完成完整 diff 和新增文件审阅。不自动提交、推送、合并或变基。

## 本任务文件清单

- `src/main/java/com/lnjecit/knowledge/assistant/document/DocumentQueryService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/ai/DashScopeReranker.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/ai/ModelConfiguration.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/ai/ModelResponseChecks.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/config/RerankProperties.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/elasticsearch/EsConfiguration.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/FailureLog.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/web/ApiExceptionHandler.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/embedding/EmbeddingRepository.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/parser/ParserSource.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/IngestionTaskProcessor.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskScheduler.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskWorker.java`
- `src/main/java/com/lnjecit/knowledge/assistant/rag/RagService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/rag/generation/AnswerGenerator.java`
- `src/main/java/com/lnjecit/knowledge/assistant/rag/validation/StructuredOutputParser.java`
- `src/main/java/com/lnjecit/knowledge/assistant/retrieval/RetrievalService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/retrieval/snapshot/PublishedVersionSnapshot.java`
- `src/main/java/com/lnjecit/knowledge/assistant/retrieval/snapshot/PublishedVersionSnapshotRepository.java`
- `src/test/java/com/lnjecit/knowledge/assistant/AnswerGeneratorTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/CitationValidationTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/EsIndexTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/FailureLogTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/ImportObservabilityTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/IngestionTaskProcessorTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/RetrievalServiceTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/infrastructure/storage/MinioFailureTests.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/CredentialEnvironmentPostProcessor.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/CredentialRedactingConverter.java`
- `src/main/resources/META-INF/spring.factories`
- `src/main/resources/logback-spring.xml`
- `src/test/java/com/lnjecit/knowledge/assistant/EmbeddingFailureLoggingTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/LoggingBoundaryTests.java`
- `docs/engineering/logging-observability-review.md`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/CredentialThrowableConverter.java`
- `src/test/java/com/lnjecit/knowledge/assistant/ContextBuilderTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/RetrievalApiTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/RerankTests.java`

## 最终工作区检查

`git status --short`：

```text
 M src/main/java/com/lnjecit/knowledge/assistant/document/DocumentQueryService.java
 M src/main/java/com/lnjecit/knowledge/assistant/infrastructure/ai/DashScopeReranker.java
 M src/main/java/com/lnjecit/knowledge/assistant/infrastructure/ai/ModelConfiguration.java
 M src/main/java/com/lnjecit/knowledge/assistant/infrastructure/ai/ModelResponseChecks.java
 M src/main/java/com/lnjecit/knowledge/assistant/infrastructure/config/RerankProperties.java
 M src/main/java/com/lnjecit/knowledge/assistant/infrastructure/elasticsearch/EsConfiguration.java
 M src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/FailureLog.java
 M src/main/java/com/lnjecit/knowledge/assistant/infrastructure/web/ApiExceptionHandler.java
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/embedding/EmbeddingRepository.java
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/parser/ParserSource.java
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/IngestionTaskProcessor.java
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskScheduler.java
 M src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskWorker.java
 M src/main/java/com/lnjecit/knowledge/assistant/rag/RagService.java
 M src/main/java/com/lnjecit/knowledge/assistant/rag/generation/AnswerGenerator.java
 M src/main/java/com/lnjecit/knowledge/assistant/rag/validation/StructuredOutputParser.java
 M src/main/java/com/lnjecit/knowledge/assistant/retrieval/RetrievalService.java
 M src/main/java/com/lnjecit/knowledge/assistant/retrieval/snapshot/PublishedVersionSnapshot.java
 M src/main/java/com/lnjecit/knowledge/assistant/retrieval/snapshot/PublishedVersionSnapshotRepository.java
 M src/main/resources/application-local.yml
 M src/test/java/com/lnjecit/knowledge/assistant/AnswerGeneratorTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/CitationValidationTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/ContextBuilderTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/EsIndexTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/FailureLogTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/ImportObservabilityTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/IngestionTaskProcessorTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/RerankTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/RetrievalApiTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/RetrievalServiceTests.java
 M src/test/java/com/lnjecit/knowledge/assistant/infrastructure/storage/MinioFailureTests.java
?? docs/engineering/logging-observability-review.md
?? docs/engineering/web-06-c-isolation-validation.md
?? docs/engineering/web-06-c-preflight.md
?? infra/web06c/
?? src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/CredentialEnvironmentPostProcessor.java
?? src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/CredentialRedactingConverter.java
?? src/main/java/com/lnjecit/knowledge/assistant/infrastructure/logging/CredentialThrowableConverter.java
?? src/main/resources/META-INF/
?? src/main/resources/logback-spring.xml
?? src/test/java/com/lnjecit/knowledge/assistant/EmbeddingFailureLoggingTests.java
?? src/test/java/com/lnjecit/knowledge/assistant/LoggingBoundaryTests.java
```

`git diff --stat`：

```text
 .../assistant/document/DocumentQueryService.java   |  10 +-
 .../infrastructure/ai/DashScopeReranker.java       |   2 +-
 .../infrastructure/ai/ModelConfiguration.java      |   3 +-
 .../infrastructure/ai/ModelResponseChecks.java     |   3 +-
 .../infrastructure/config/RerankProperties.java    |   2 +-
 .../elasticsearch/EsConfiguration.java             |   2 +-
 .../infrastructure/logging/FailureLog.java         | 109 ++++++---------------
 .../infrastructure/web/ApiExceptionHandler.java    |   5 +-
 .../ingestion/embedding/EmbeddingRepository.java   |   2 +-
 .../assistant/ingestion/parser/ParserSource.java   |   2 +-
 .../ingestion/task/IngestionTaskProcessor.java     |   2 +-
 .../assistant/ingestion/task/TaskScheduler.java    |   2 +-
 .../assistant/ingestion/task/TaskWorker.java       |   7 +-
 .../knowledge/assistant/rag/RagService.java        |   9 +-
 .../assistant/rag/generation/AnswerGenerator.java  |   9 +-
 .../rag/validation/StructuredOutputParser.java     |   8 +-
 .../assistant/retrieval/RetrievalService.java      |   8 +-
 .../snapshot/PublishedVersionSnapshot.java         |   2 +-
 .../PublishedVersionSnapshotRepository.java        |   2 +-
 src/main/resources/application-local.yml           |   2 +-
 .../knowledge/assistant/AnswerGeneratorTests.java  |   4 +-
 .../assistant/CitationValidationTests.java         |  10 +-
 .../knowledge/assistant/ContextBuilderTests.java   |  15 +--
 .../lnjecit/knowledge/assistant/EsIndexTests.java  |   2 +-
 .../knowledge/assistant/FailureLogTests.java       |  31 ++++--
 .../assistant/ImportObservabilityTests.java        |   2 +-
 .../assistant/IngestionTaskProcessorTests.java     |  34 +++++++
 .../lnjecit/knowledge/assistant/RerankTests.java   |   7 +-
 .../knowledge/assistant/RetrievalApiTests.java     |   3 +-
 .../knowledge/assistant/RetrievalServiceTests.java |  28 ++++++
 .../infrastructure/storage/MinioFailureTests.java  |  35 +++++++
 31 files changed, 214 insertions(+), 148 deletions(-)
```

`git diff --check`：退出码 0，无输出。

原有 application-local.yml 的修改及验证资料未改动。普通 git diff --stat 不包含新增文件：本任务 30 个已跟踪文件 + 8 个新增文件，共 38 个；统计中的额外 local 配置属于用户原有变更。
