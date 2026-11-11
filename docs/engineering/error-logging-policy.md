# 异常日志与诊断规范

## 服务端与客户端边界

客户端继续使用原 Result/领域错误码；不得返回 Throwable、cause、SQL、供应商正文或认证信息。异常对象在进程内保留 Cause，领域状态持久化仍只保存固定分类。服务端需要异常类型、原因链、堆栈及可安全提取的结构化错误信息。

INFO 表示正常状态；WARN 表示业务拒绝、预期可恢复问题；ERROR 表示依赖或执行失败；DEBUG 表示进一步诊断。检索 FAILED 的逐策略/逐阶段摘要使用 DEBUG；共享召回实际失败位置输出一次 ERROR，不能因四策略复用而输出四份堆栈。

## 日志输出边界

`FailureLog` 将原始异常转换为独立诊断 Throwable，再交给 SLF4J；原始异常从不直接传给日志后端。转换同时处理 cause、suppressed 和堆栈，原因链限制16层并识别循环；不是仅对格式化消息做替换。原始异常仍保留给服务端调试，不直接序列化至 API。

实际配置中的模型 Key、数据源密码、MinIO access-key/secret-key 按完整值替换；同时处理凭据赋值、Bearer、Authorization、Cookie 和 URL userinfo。普通异常消息经脱敏后保留。普通 SDK、网络和 JDBC 包装消息经过脱敏后保留，不按包名隐藏。确实包含完整 HTTP 响应的异常采用结构化摘要：OpenAI status/code/type/error.message，ES status/type/reason，MinIO HTTP status/code/message，SQLException SQLState/vendorCode/脱敏描述。OpenAI 只读取结构化 error.message；非对象或无法解码时明确记录 unavailable 和省略原因，禁止回退到完整响应。默认不输出请求头、响应体、问题、上传正文或向量。

这不是全局 Logback 过滤器：业务必须通过此边界记录异常，不得直接 `log.error(..., rawThrowable)`。第三方自行输出的日志不受此工具保护；动态新增认证属性需加入凭据注册，编码/变换后的密钥、任意未知 SDK 中隐藏的 Payload 不能依靠正则获得绝对安全保证。上线前需要额外核验 SDK 日志级别和采集通道。SQL 服务端 Detail 可能包含业务值，生产场景需进一步评估。

## 责任边界

| 模块 | 主要日志责任 |
|---|---|
| API advice | 未处理系统异常 ERROR，响应保持固定错误；业务拒绝 WARN |
| RetrievalService | 快照、共享 Embedding、融合及重排转 FAILED 时记录；传播失败不重复记录 |
| VectorRetriever / KeywordRetriever | PG/ES 失败转 Recall 时记录；底层 Repository 保留 Cause、透传不打印 |
| QueryEmbeddingService / RerankService | 保留安全分类和原始 Cause，不自行打印；最终消费边界负责 |
| TaskWorker | 处理器失败一次 ERROR；状态更新的独立失败由继续传播的最终边界处理 |
| TaskScheduler | 扫描或未被 Worker 消费的异常 ERROR |
| DocumentImportService | 补偿删除和引用检查失败在当地记录；原业务异常继续由 API 记录 |
| MinIO / ES 写入与校验 | 包装时保留 Cause，Worker/API 负责主要日志 |

`ImportEvents` 吞掉日志后端故障是有意设计，避免诊断改变首次发布/补偿语义，不递归打印日志故障。参数非法、文本编码拒绝、重复导入、受控解析/模型响应校验属于合理业务转换，不机械提升为 ERROR。Repository 事务、重试、首次发布语义及检索算法不变。

## 审计结果

恢复时生产源码共有65处 catch（不包括测试），其中新增 Rerank 错误体解析 catch 已纳入审计；本次工具增加2处 catch，完成后共67处 catch、26个含 catch 的生产文件。重点问题：ModelFailures/MinIO/ES 包装删除 Cause；API 兜底只留类名；Retrieval/Worker/补偿转状态不记录主要故障。以上已进行针对性修复。本轮继续修复 MinIO 读取超时/连接转换、Embedding 数据库写入丢失 Cause；ES Scroll 清理失败作为原故障 suppressed 保留；ES Bulk 非成功项保留 HTTP status/type/reason，响应正文不进入异常。解析器、参数 DTO 和向量内容校验继续使用安全拒绝信息，不记录原始业务 Payload。下表按源码文件列出 catch 数量，数量用于覆盖审计范围，不表示每处都需要修改。

| 文件 | catch数 |
|---|---:|
| `infrastructure/ai/DashScopeReranker.java` | 7 |
| `infrastructure/logging/FailureLog.java` | 2 |
| `infrastructure/ai/ModelConfiguration.java` | 1 |
| `infrastructure/ai/ModelResponseChecks.java` | 1 |
| `infrastructure/config/RerankProperties.java` | 1 |
| `infrastructure/elasticsearch/EsConfiguration.java` | 1 |
| `infrastructure/storage/MinioOriginalFileStore.java` | 4 |
| `ingestion/embedding/EmbeddingRepository.java` | 2 |
| `ingestion/embedding/EmbeddingService.java` | 1 |
| `ingestion/indexing/EsIndexRepository.java` | 7 |
| `ingestion/parser/ParserSource.java` | 1 |
| `ingestion/task/ImportEvents.java` | 2 |
| `ingestion/task/IngestionTaskProcessor.java` | 5 |
| `ingestion/task/TaskScheduler.java` | 1 |
| `ingestion/task/TaskWorker.java` | 2 |
| `ingestion/upload/DocumentImportService.java` | 5 |
| `ingestion/verification/EsIntegrityService.java` | 4 |
| `retrieval/RetrievalService.java` | 9 |
| `retrieval/api/RetrievalSearchRequest.java` | 1 |
| `retrieval/embedding/QueryEmbeddingService.java` | 1 |
| `retrieval/keyword/KeywordRetriever.java` | 2 |
| `retrieval/keyword/KeywordSearchRepository.java` | 2 |
| `retrieval/rerank/RerankService.java` | 1 |
| `retrieval/snapshot/PublishedVersionSnapshot.java` | 1 |
| `retrieval/snapshot/PublishedVersionSnapshotRepository.java` | 1 |
| `retrieval/vector/VectorRetriever.java` | 2 |

## 验证与边界

离线测试使用日志事件的真实 ThrowableProxy 渲染，覆盖嵌套 Cause、suppressed、密码/Key/Token/Cookie，普通原因保留、SQLState、连接/超时及所有 ModelFailures.Kind。API 测试继续要求响应不包含内部异常，主要 ERROR 数量断言用于防止重复堆栈。历史“异常不得携带 Cause”的测试契约改为外部消息安全与内部 Cause 保留。

当前 IDEA 旧进程不会自动使用这次修改，未擅自重启或向付费模型发送请求。原四策略真实故障根因尚未验证，不能将历史 EMBEDDING_FAILED 推断成认证失败。后续获授权后重启新代码，关联 requestId/boundary/kind/httpStatus/providerCode 定位。

示例：修复前仅 `error=EMBEDDING_FAILED`；修复后 `ERROR requestId=... boundary=EMBEDDING kind=AUTHENTICATION`，诊断原因链含 SDK 异常类型、`httpStatus=401 providerCode=...` 与调用堆栈，认证值为 `[REDACTED]`。此为格式示例，不是真实在线故障结论。

## 2026-11-11 续作审计与证据

续作基线：`feature/retrieval-demo`，HEAD `0860a52`；保留恢复的代码/测试修改。`application-local.yml` 是进入本轮前已存在的本地配置修改，不纳入本次提交。日志不提交原始 Maven 输出。

- MinIO 超时/连接仍转换为 INFRASTRUCTURE_TEMPORARY，增加原始 Cause；其余读写异常的固定消息和业务语义不变。
- 普通消息采用已配置凭据完整值替换、引号值（含空格/逗号）、Bearer、认证头/Cookie、Token、URL userinfo 脱敏。消息换行折叠，避免伪造日志行。Cause 与 suppressed 递归使用同样规则。
- 不按 SDK/JDBC 包名丢弃 message。HTTP SDK 完整响应可能包含请求头和任意业务 Payload，因此只提取上述错误字段。供应商描述仍须脱敏；不能推断未知字符串一定不含凭据。
- 日志后端故障由 FailureLog 本地消费，避免日志失败改变补偿、任务状态与 API 返回；不递归写日志。原因图循环和深度16之后停止遍历。
- Worker 消费的执行故障打印一次；状态写入失败是另一独立故障，继续抛给 Scheduler 记录。正常传播的 Repository/ModelFailures 不打印。Retrieval 的共享 Embedding/召回失败打印一次，下游融合及四策略结果复用不重复打印。
- 保留参数校验、编码/内容校验、向量合法性校验、快照构造校验的固定拒绝；解析原文可能含正文，合理转换继续避免回显原文。保留 ImportEvents 的日志后端故障消费，以及发布提交结果未知后核对既有事实的恢复协议。

日志对比（离线格式示例）：此前只有 `error=EMBEDDING_FAILED` 或异常类名；现在有 `boundary=EMBEDDING kind=AUTHENTICATION`、`UnauthorizedException httpStatus=401 providerCode=invalid_api_key message=invalid credential token=[REDACTED]` 和脱敏 Cause/堆栈。REST 仍返回原 FAILED/EMBEDDING_FAILED 协议。

当前 Embedding 错误已能通过 requestId、阶段、分类、SDK 状态/错误码/描述及 Cause 定位；已用真实 SDK 异常对象做离线日志验证。未调用真实模型，未重启 IDEA 旧进程，因此不声称已查明旧进程历史 Embedding 失败根因。

验证记录：上一轮会话定向12项全部通过。本轮首次新增定向23项中22项通过、1项测试构造错误（真实 SDK builder 要求 headers）；已补齐 SDK 必填 headers 后重新运行。最终结果见下方完成记录。

仍存在的限制：第三方自主日志不经 FailureLog；编码/分片后的密钥、未知凭据属性及供应商以任意自然语言回显的未知 Token 不能由正则绝对识别；新增凭据配置需注册，日志采集访问权限仍需部署侧管理。保留的 SQL/供应商消息可能含业务值，按原需求保留故障信息，不能视为完整业务数据匿名化。16层之后的异常图不输出；日志后端故障会丢诊断日志。

首次默认全量验证：247项单元测试中245项通过、2项 ProfileConfigurationTests 失败；工作区已有 application-local.yml 启用了模型，与默认关闭的契约不符，Failsafe 尚未执行。未修改操作者配置或测试断言。随后在系统临时目录构造隔离验证副本：所有待提交生产/测试源码逐文件复制，application-local.yml 使用 HEAD 原始版本，运行默认 profile 的 `mvn -Dmaven.repo.local=D:\develop\repository clean verify`。此验证对应本次提交内容，不将本地个性化配置误报为通过。

新增定向最终结果：30项，失败/错误/跳过均0。中间第二轮29项出现1项错误码断言失败，揭示真实 SDK 对未知 JSON 的 code() 返回空；已加入结构化 code/type 回退读取，最终30项覆盖该路径及四策略共享故障只输出一份堆栈。

隔离副本第一次重跑：247项中246项通过，1项 gitIgnoreProtectsPrivateFileAndAllowsTrackedProfiles 因副本没有 .git 而失败（git exit 128）。随后仅在临时副本初始化空 Git 仓库，保留原 .gitignore，再次运行默认 clean verify；未修改或跳过该测试。

## 最终完成记录

2026-11-11 14:48:28（Asia/Shanghai），隔离副本默认 `mvn -Dmaven.repo.local=D:\develop\repository clean verify`：BUILD SUCCESS，耗时14:52。Spotless check、生产/测试编译通过；Surefire 247项、Failsafe 43项，共290项，失败/错误/跳过均0。此前单独执行 Spotless apply/check、test-compile 和定向30项通过。隔离副本全部 Java 及 pom.xml 与工作区逐文件字节比较一致，使用 HEAD 默认 local 配置；不将原工作区个性化配置标记为通过。

真实容器集成结果（项数 / 失败 / 错误 / 跳过）：

| 类 | 结果 |
|---|---|
| DatabaseIT | 2 / 0 / 0 / 0 |
| ElasticsearchIT | 1 / 0 / 0 / 0 |
| EsIndexIT | 4 / 0 / 0 / 0 |
| EsIntegrityIT | 2 / 0 / 0 / 0 |
| ImportTaskIT | 1 / 0 / 0 / 0 |
| IndexPublishIT | 1 / 0 / 0 / 0 |
| IndexVerificationIT | 2 / 0 / 0 / 0 |
| IngestionPipelineIT | 15 / 0 / 0 / 0 |
| KeywordSearchIT | 3 / 0 / 0 / 0 |
| MinioIT | 2 / 0 / 0 / 0 |
| PgIntegrityIT | 1 / 0 / 0 / 0 |
| RetrievalFoundationIT | 2 / 0 / 0 / 0 |
| RetrievalServiceIT | 2 / 0 / 0 / 0 |
| StorageImportIT | 1 / 0 / 0 / 0 |
| TaskRecoveryIT | 3 / 0 / 0 / 0 |
| VectorSearchIT | 1 / 0 / 0 / 0 |

环境：Java 21.0.10、Spring Boot 4.1.2；Docker Server 29.9.3。复用仓库既定 PG/pgvector、ES/IK、MinIO 镜像，不升级版本，不启用 online profile。日志路径为系统临时目录 `ka-logging-verify-kvfief9h/verify.log`，JUnit XML 位于该目录的 target/surefire-reports、target/failsafe-reports；先前运行日志在 prior-runs 下，不提交。未运行真实供应商 API，未重启开发进程。

提交前审阅完整生产/测试 diff、新增日志工具和本规范；git diff --check 通过。仅提交任务相关源码、测试、本文，保留原 application-local.yml 未提交修改。M1/M2 REST 错误协议、SQL、迁移、事务及检索策略不变，不进入 M3。
