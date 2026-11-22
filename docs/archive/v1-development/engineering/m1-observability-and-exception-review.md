# M1 人工验收前可观测性与异常审查

日期：2026-11-09。基线：`a182b6f3524881f4157d45ffa40c68cef726e06a`，分支 `feature/m1-ingestion-indexing`，开始时工作区干净。本次不修改 SQL、状态机、事务、重试策略、HTTP 响应或冻结架构；不执行在线模型及人工验收。

## 核心事件

| 事件 | 级别 | 位置与含义 |
|---|---|---|
| IMPORT_RECEIVED | INFO | 上传元数据事务返回后，新任务已接收 |
| IMPORT_DUPLICATE | INFO | 已有任务或唯一约束胜出任务复用 |
| IMPORT_TASK_CLAIMED | INFO | Scheduler 中 claimNext 的代理事务返回后 |
| IMPORT_CHUNK_COMPLETED | INFO | ChunkService 初始化事务返回后，包含 Chunk 总数 |
| IMPORT_EMBEDDING_COMPLETED | INFO | 缺失向量补齐完成，总数不是本轮请求条数 |
| IMPORT_ES_COMPLETED | INFO | create-only 写入完成，不代表已验证或发布 |
| IMPORT_VERIFIED | INFO | 双索引验证及 PG VERIFIED 事务返回后 |
| IMPORT_PUBLISHED | INFO | IndexPublishService 代理事务返回后 |
| IMPORT_TASK_FAILED | WARN | 失败状态更新事务已返回，保留安全分类及异常类型 |
| IMPORT_TASK_RECOVERED | WARN | recoverNext 返回 true 后，数据库恢复事务已返回 |
| IMPORT_TASK_FAILURE_UPDATE_FAILED | WARN | 失败状态未能确认写入，原处理错误码与两类异常类型 |
| IMPORT_PUBLISH_OUTCOME_UNKNOWN | WARN | 发布提交返回异常，需要重新核对事实 |
| IMPORT_TASK_SCAN_FAILED | WARN | 扫描退出，固定错误码与异常类型 |

下例是格式说明，**不是本地人工验收证据**：

```text
event=IMPORT_RECEIVED taskId=101 documentId=201 indexVersionId=301 attemptCount=0 elapsedMs=25
event=IMPORT_CHUNK_COMPLETED taskId=101 documentId=201 indexVersionId=301 attemptCount=1 chunkCount=3 elapsedMs=12
event=IMPORT_PUBLISHED taskId=101 documentId=201 indexVersionId=301 outcome=FIRST_PUBLISH elapsedMs=8
event=IMPORT_TASK_FAILED taskId=101 documentId=201 indexVersionId=301 errorCode=MODEL_TIMEOUT exceptionType=com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException elapsedMs=30001
```

发布 outcome：FIRST_PUBLISH 表示正常首次发布调用成功；EXISTING_FACTS_CONFIRMED 表示已发现发布事实并由事务核对；UNCERTAIN_RESULT_RESOLVED 表示首次调用提交结果未知、重入发布事务成功，**不声称已判定实际是哪次调用提交**。

TaskScheduler 已持有的 ClaimedTask 不包含 attemptCount，恢复接口仅返回 boolean，因此领取日志不输出次数，恢复日志不输出 ID；没有为日志增加数据库查询。处理器利用已有 ImportTask 快照记录次数。elapsedMs 使用单调时钟；领取耗时包含本轮恢复检查，其余按当前阶段或本次上传/执行计时。Scheduler 入口清理 requestId，后台不借用上传请求 MDC。保留 HTTP RequestIdFilter 原设计。

ImportEvents 只包装日志后端 RuntimeException，避免日志故障触发补偿或任务失败，不递归告警。调用方只传固定事件、数值、枚举和异常类型，禁止 Throwable、异常消息、正文、文件名、hash、向量、模型请求或凭据。

## TaskMapper 逐条审查

标记：必要=状态/执行权协议所必需；防御=已有锁和正常调用前提下重复检查但保留；它们不构成多 Worker 支持承诺。

| SQL | 作用及关键条件 | 并发控制与防御边界 |
|---|---|---|
| lockNext | attempt_count 小于上限；PENDING 无重试时间或已到期；FAILED 必须有到期重试时间；按 created_at,id 选一个 | FOR UPDATE 保证锁定领取行；SKIP LOCKED 避免等待已锁行；LIMIT 1 限制范围。FAILED 的非空时间是可重试标志，不能省略。数据库时间避免 JVM 时钟偏差 |
| lockExpired | 只选 RUNNING、租约已到期、owner 不同的一个任务 | 行锁与 SKIP LOCKED 控制竞争；不同 owner 避免恢复当前轮自己的执行；不证明旧进程已停止，不提供 fencing |
| validOwnership | Document ID、Version ID、v.document_id=d.id 校验关联；active_version_id 为空、published_at 为空保证未发布；BUILDING 或失败任务对应 VERIFIED/FAILED | 普通快照查询，**不锁文档或版本**，避免 Task→Document 逆序锁。两个发布标记分别防御部分事实异常，不能只留一个。版本 FAILED 与 Processor 不一致，见下文 |
| claim | ID 定位；再次检查次数、状态及到期时间；写 RUNNING、租约、owner，次数及 epoch 加一，清重试时间 | 同一事务已锁 Task 时状态/次数谓词有重复防御作用；也保护 claimById 和未来调用路径。epoch 区分同任务多次执行；数据库时间租约是执行权协议必要部分，不能删除 |
| fail | task/document/version ID 一致、RUNNING、owner/epoch 匹配且租约未到期才更新 FAILED；清租约，按安全分类决定重试时间 | status、owner、epoch、lease_until 是必要执行权条件，避免旧执行者破坏新领取或成功事实。document/version ID 对已锁不可变任务属于防御性关联确认。影响行数必须为 1 |
| recover | ID、RUNNING、租约到期、owner 不同再次校验；写 WORKER_INTERRUPTED，清租约，按剩余次数决定时间 | lockExpired 已持锁时是重复防御，但保持独立条件更新语义。不要求 attempt_count 小于上限：最后一次中断仍需退出 RUNNING，时间置空后终止 |

TaskService 提供短事务；TaskRepository 决定次数和退避；TaskStateMachine 只允许 PENDING/FAILED→RUNNING 与 RUNNING→FAILED，SUCCEEDED 由发布事务负责。Scheduler 每轮生成新 owner，同步顺序执行 recover、claim、Worker。Processor 只接受未发布 BUILDING/VERIFIED，VERIFIED 仍重验。发布锁顺序 Document→Version→Task，最终更新按数据库时间检查租约，完整发布事实的幂等确认要求同 claim_epoch。

### 不一致与最小方案（本次仅报告）

1. `validOwnership` 对 FAILED 任务允许 IndexVersion.FAILED，但生产 fail/recover 不产生该版本状态。若存在这类历史/人工数据，领取后 Processor 会抛不可重试 VALIDATION_FAILED；fail 写入 next_retry_at=NULL，**正常不会自动反复领取**，但会无效消耗一次次数。
2. 更一般的失配任务（如不存在版本或已有发布指针）可以先被 lockNext 选中，再被 validOwnership 拒绝，事务回滚且任务不变。若一直排在队首，每次扫描都可能遇到它并退出，阻塞后续任务。正常 M1 生产路径未证明会产生这种记录；不得为复现篡改现有业务数据。
3. 最小收敛候选：仅从 validOwnership 移除版本 FAILED 兼容条件，保留失败任务对 VERIFIED 的支持。这能收敛处理器契约，但**单独修改不能解决失配队首阻断**。若要一并解决，需另审是否让 lockNext 采用相同的只读关联过滤并保留 validOwnership 防御，或明确隔离非法任务策略；不顺带添加状态。
4. 后续测试方案：隔离 PG 数据构造 FAILED 任务/FAILED 版本，确认收敛后不被领取；验证 FAILED/VERIFIED 可领取并重新验证；失配队首加有效后继，验证选取策略；保留旧 epoch、错误 owner、租约边界、第四次失败及发布事务回归。以上是修复建议，**本次未改 SQL，未宣称这些候选修复已通过测试**。

## 全仓 catch 审查清单

扫描范围 `src/main/java/**/*.java`，包含新增 ImportEvents。表中文件名均指同名生产类；未单独打印日志的转换由 Worker 或 HTTP 边界安全记录。保留诊断表示保留安全分类，不意味着保留原始 cause。

| 文件 / 方法 | 捕获类型 | 当前行为及分类 | 日志 | 保留诊断 | 风险 / 建议 |
|---|---|---|---|---|---|
| EmbeddingRepository / validateVector | RuntimeException | 向量格式错误转固定异常 | 边界 | 固定安全描述 | 合理，不输出向量 |
| EmbeddingRepository / write | RuntimeException | DB 写异常转固定异常，整批回滚 | Worker | 仅固定描述 | 丢失 DB 分类；未知错误不重试是现有策略，本次不改 |
| EmbeddingService / embed | RuntimeException | ModelFailures.safe 分类 | Worker | 模型安全 kind | 合理，不传供应商 cause |
| EsIndexRepository / checkIndex 内层 | ElasticsearchException | 仅资源已存在继续检查映射，其他重抛外层 | 边界 | 外层固定码 | 合理，不是任意吞没 |
| EsIndexRepository / checkIndex | JsonException | ES_PROTOCOL_INVALID | Worker | 固定码 | 合理 |
| EsIndexRepository / checkIndex | IOException | 超时/断连结果未知分类 | Worker | 固定码 | 合理 |
| EsIndexRepository / checkIndex | ElasticsearchException | ES_INDEX_REQUEST_REJECTED | Worker | 固定码 | 不回显 ES body |
| EsIndexRepository / create | JsonException | ES_PROTOCOL_INVALID | Worker | 固定码 | 合理 |
| EsIndexRepository / create | IOException | 结果未知，保留已有索引供重试 | Worker | 固定码 | 合理 |
| EsIndexRepository / create | ElasticsearchException | ES_BULK_REQUEST_REJECTED | Worker | 固定码 | 不回显 ES body |
| ParserSource / decode | CharacterCodingException | 转 INVALID_UTF8 | Worker | ParseException.kind | 合理 |
| IngestionTaskProcessor / process | ParseException | 转 UNSUPPORTED_FORMAT 或 INVALID_CONTENT | Worker | 安全任务码 | 更细 parser kind 被合并，符合现协议 |
| IngestionTaskProcessor / process | SafeModelException | kind 映射超时/不可用/永久错误 | Worker | 安全任务码 | 合理 |
| IngestionTaskProcessor / process | IllegalStateException | 仅两个 ES 结果未知码转 SEARCH_UNAVAILABLE，其他重抛 | Worker | 安全任务码或异常类型 | 不记录未知异常正文 |
| IngestionTaskProcessor / publish | TransactionSystemException 或 DataAccessResourceFailureException | 读任务后再次调用发布核对 | 新 WARN + 成功 INFO | 安全码及类型、outcome | 原来缺少结果未知诊断，本次补充，不改变重入策略 |
| IngestionTaskProcessor / sha256 | NoSuchAlgorithmException | 固定 SHA256_UNAVAILABLE | Worker | 固定描述 | SHA-256 为标准算法，保留防御 |
| TaskWorker / execute 外层 | RuntimeException | 安全分类后尝试 fail | 新 WARN | 任务码、异常类型和 IDs | 不把处理器正常返回当成功 |
| TaskWorker / execute 内层（新增） | RuntimeException | fail 自身失败告警并原样重抛 | 新 WARN | 原码及两类异常类型 | 原实现只有 Scheduler 泛化告警，缺上下文；已补 |
| TaskScheduler / scan | RuntimeException | 本轮退出，下轮再试 | WARN | 新增 exceptionType | 不再仅有事件名；与失败更新告警分层，不重复堆栈 |
| DocumentImportService / receive 编码 | CharacterCodingException | INVALID_ARGUMENT | HTTP 边界 | 固定错误码 | 合理，不含原文 |
| DocumentImportService / receive 对象准备 | RuntimeException | 尝试补偿 originals 后重抛 | 清理失败/HTTP 边界 | 安全边界类型 | 删除失败不覆盖原异常 |
| DocumentImportService / receive 元数据事务 | RuntimeException | 主库核对引用；唯一键冲突读取胜出任务 | 引用未知/清理失败 WARN | 固定码 | 不以提交异常推断已回滚 |
| DocumentImportService / receive 引用核对 | RuntimeException | 保留 originals 并告警 | WARN | 固定码及对象 Key | 有意继续，避免误删已引用对象 |
| DocumentImportService / cleanup | RuntimeException | 清理失败告警继续 | WARN | 固定码及随机 Key | finally 不覆盖业务结果；正式孤儿仍需人工核对 |
| EsIntegrityService / verifyIndex | IOException | 连接类转 ES_CONNECTION_OUTCOME_UNKNOWN，其他固定验证错误 | Worker | 固定码 | 合理 |
| EsIntegrityService / verifyIndex | RuntimeException | 统一 ES_VERIFICATION_FAILED | Worker | 固定描述 | 更细映射/协议分类被合并，保留现有重试策略 |
| EsIntegrityService / refreshAndVerifySnapshot 主体 | IOException 或 RuntimeException | 标记原验证失败并重抛 | Worker | 原分类供外层转换 | 合理，用于 finally 优先级 |
| EsIntegrityService / refreshAndVerifySnapshot 清理 | IOException 或 RuntimeException | 原验证已失败则不覆盖；否则清理失败决定结果 | Worker | 主错误分类 | 同时失败时清理诊断省略，scroll 有 1m 过期；保留行为 |
| DashScopeReranker / rerank 等待 | TimeoutException | cancel 并转 TIMEOUT | 调用边界 | kind | 合理 |
| DashScopeReranker / rerank 等待 | ExecutionException | 解包安全分类 | 调用边界 | kind | 不保留供应商原始 cause |
| DashScopeReranker / rerank 内层 | InterruptedException | cancel 后交给外层 | 外层 | 中断分类 | 合理 |
| DashScopeReranker / rerank 外层 | InterruptedException | 恢复线程中断，转 OTHER | 调用边界 | 中断标志及 kind | 正确恢复中断 |
| DashScopeReranker / rerank 外层 | RuntimeException | 安全异常保留，其余 OTHER | 调用边界 | kind | 合理，无线上调用验证 |
| DashScopeReranker / parse | JacksonException | 转非法响应 | 调用边界 | INVALID_RESPONSE | 不回显响应体 |
| ModelConfiguration / validate | RuntimeException | 固定配置错误 | 启动边界 | 固定描述 | 不回显带凭据 URL |
| ModelResponseChecks / json | RuntimeException | InvalidResponseException | 调用边界 | 响应校验类别 | 合理，广泛捕获有意脱敏 |
| RerankProperties / afterPropertiesSet | RuntimeException | 固定配置错误 | 启动边界 | 固定描述 | 不回显 Key/endpoint |
| EsConfiguration / validatedEndpoint | RuntimeException | 固定地址错误 | 启动边界 | 固定描述 | 不回显凭据 |
| MinioOriginalFileStore / copy | Exception | 固定 Object copy failed | 上传/HTTP 边界 | 操作类别 | 丢失底层类型；保留安全边界 |
| MinioOriginalFileStore / put | Exception | 固定 Object upload failed | 上传/HTTP 边界 | 操作类别 | 同上 |
| MinioOriginalFileStore / read | Exception | 直接超时/连接异常可重试，其余固定读失败 | Worker | 任务码或操作类别 | 包装层差异可能影响分类，未改变重试策略 |
| MinioOriginalFileStore / delete | Exception | 固定清理失败 | 上传补偿 WARN | 操作类别 | 清理调用方决定是否继续 |
| ImportEvents / info（新增） | RuntimeException | 忽略日志后端异常 | 不递归记录 | 无 | 有意吞没以保护业务结果，有测试 |
| ImportEvents / warn（新增） | RuntimeException | 忽略日志后端异常 | 不递归记录 | 无 | 同上 |

共 44 个生产 catch。没有发现显式 InterruptedException 漏恢复：Reranker 内层向外传播，外层恢复标志。MinIO SDK 的宽泛 Exception 不是显式中断处理入口，未凭推测重写异常协议。try-with-resources 的关闭失败遵循 Java suppressed 语义；上传 finally 的清理捕获 RuntimeException，不覆盖正常业务异常。未发现生产代码将供应商原始异常信息直接作为 HTTP 响应；ApiExceptionHandler 仅输出安全错误码/消息，未知异常只记录类型。

## 实际修改与保留策略

- ObjectKeyPrefixes 简单常量类，STAGING/ORIGINALS 字节值不变；上传、copy 校验、读取正则、补偿和清理及相关测试使用常量。前缀长度校验由常量长度表达。迁移测试的历史 SQL Key 与只读 SQL 字面断言刻意独立保留，验证旧对象兼容性；Python ILM 不变。
- 核心编排日志及失败更新失败诊断；不增加 Repository/Mapper 日志或数据库查询，不逐 Chunk 打 INFO。
- 架构文档移除 BOM；四图使用 Mermaid CLI 11.12.0 和 Chrome 实际生成 SVG。首次默认 Puppeteer 未找到下载版 Chrome，指定本机 Chrome 后成功；未新增项目依赖，临时产物不提交。
- 没有机械补 log.error；供应商、向量、数据库底层异常继续使用既有安全转换，不打印原始 cause。

## 验证记录

2026-11-09 实际执行：

| 检查 | 结果 | 说明 |
|---|---|---|
| Mermaid 四图实际 SVG 渲染 | PASS | CLI 11.12.0，指定本机 Chrome；四份 SVG 均非空 |
| 文档 UTF-8 无 BOM | PASS | 字节检查，架构与审查文档均无 EF BB BF 前缀 |
| mvn spotless:apply / spotless:check | PASS | 格式化完成；clean verify 中再次执行 check |
| mvn -DskipTests test-compile | PASS | 生产编译、测试编译完成，此命令不作为运行测试证据 |
| 定向运行 ImportObservabilityTests、IngestionTaskProcessorTests、TaskSchedulerRecoveryTests | PASS | 23 项，失败/错误/跳过均 0 |
| mvn clean verify | PASS | 2026-11-09 22:36:31 完成，耗时 11:04；154 单元 + 35 集成，共 189 项，失败/错误/跳过均 0 |
| git diff --check | PASS | 提交前完整差异检查 |
| 本地应用启动与人工验收 | NOT VERIFIED | 本次按要求不进入人工验收 |
| 真实在线模型 | NOT VERIFIED | 未授权也未调用；默认回归使用既有本地 Stub |

完整默认回归中的集成测试：

| 测试类 | 项数 | 失败 / 错误 / 跳过 | 结果 |
|---|---|---|---|
| DatabaseIT | 2 | 0 / 0 / 0 | PASS |
| ElasticsearchIT | 1 | 0 / 0 / 0 | PASS |
| EsIndexIT | 4 | 0 / 0 / 0 | PASS |
| EsIntegrityIT | 2 | 0 / 0 / 0 | PASS |
| ImportTaskIT | 1 | 0 / 0 / 0 | PASS |
| IndexPublishIT | 1 | 0 / 0 / 0 | PASS |
| IndexVerificationIT | 2 | 0 / 0 / 0 | PASS |
| IngestionPipelineIT | 15 | 0 / 0 / 0 | PASS |
| MinioIT | 2 | 0 / 0 / 0 | PASS |
| PgIntegrityIT | 1 | 0 / 0 / 0 | PASS |
| StorageImportIT | 1 | 0 / 0 / 0 | PASS |
| TaskRecoveryIT | 3 | 0 / 0 / 0 | PASS |

日志测试覆盖日志后端 RuntimeException 不传播、失败状态写入失败保留原异常传播且不泄露消息/堆栈、Scheduler 清理旧 RequestId、主要阶段日志、已有发布事实确认及提交结果未知后重入标识。既有流水线回归验证状态/发布协议，未为日志增加数据库访问。

环境：Java 21.0.10、Maven 3.9.16、Docker Server 29.4.0；测试使用既有 PG/pgvector、ES/IK、MinIO Testcontainers 镜像，不操作开发 Compose 数据。编译出现既有 deprecated/unchecked 提示，不影响构建。原始 Maven 日志保留系统临时目录 `ka-clean-verify.log`，JUnit XML 位于 target/surefire-reports 与 target/failsafe-reports；这些临时日志不提交，避免带入原始敏感日志。

## 未关闭风险

SQL 的 FAILED 版本兼容与失配队首阻断未修改，见最小方案。日志不可替代存储查询和人工验收；恢复日志没有 ID，需结合任务查询定位。日志后端故障可能丢事件，不能以日志缺失推断状态。跨存储不具备严格 fencing，单 Worker 部署约束不变。在线模型、本地启动及人工验收不在本次执行范围。
