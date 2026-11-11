# M3-05 RAG 状态、异常与诊断闭环

## 范围与实际问题

基线 feature/m3-rag / 6c3ca74b6dde582edb8ac23e144c18e24eb81b49，开始时工作区干净。阅读 M3-01～04、架构9/11/12、日志规范及正式 RAG/检索/全局异常边界。未修改本地配置、依赖、迁移、M2 算法或冻结架构。架构 Boot 3.x 与 pom 4.1.2 差异沿用既有记录。

发现并最小修复：

- RagService 没有各阶段及最终汇总耗时，补充单调时钟和安全摘要。
- Context 的 RuntimeException 兜底会将 NPE 等程序缺陷误作 HTTP 200 的领域失败，收窄为身份冲突及 Jackson 序列化异常。
- Parser 同样收窄为 JacksonException / IllegalArgumentException，避免未来意外程序缺陷被统一吞成 OUTPUT_INVALID。
- 空文本和明确截断在读取元数据前拒绝，丢失已返回的真实 usage；提前读取元数据，通过 GenerationException 的可空 usage 透传。
- AnswerGenerator 原有 INFO 日志没有隔离日志后端故障，补充本地保护。

不修改：引用校验、不变量、检索结果传递、单次 Context/Chat、严格 JSON 协议和依赖分类已经正确；完整候选后发生解析/引用失败的 usage 本来已经保留。没有增加重试、JSON 修复或新观测框架。

## HTTP 与状态矩阵

合法领域结果均 HTTP 200、Envelope code=0；requestId 位于 Envelope。FAILED 恒 answer=null/citations=[]；策略、degraded、检索诊断来自同次 RetrievalResult，没有静默降级。

| 场景 | status / error |
|---|---|
| 单/多证据合法回答 | ANSWERED / null |
| 空检索 | INSUFFICIENT_EVIDENCE / null，answer=null |
| 合法拒答 | INSUFFICIENT_EVIDENCE / null，保留说明，空引用 |
| 检索失败 | FAILED / RETRIEVAL_FAILED |
| 全部证据超预算 | FAILED / CONTEXT_BUDGET_EXCEEDED |
| 重复身份冲突 | FAILED / CONTEXT_DATA_INCONSISTENT |
| Jackson Context 序列化失败 | FAILED / CONTEXT_BUILD_FAILED |
| 模型关闭 | FAILED / MODEL_NOT_CONFIGURED |
| 超时、限流、认证失败 | FAILED / MODEL_TIMEOUT、MODEL_RATE_LIMITED、MODEL_AUTHENTICATION_FAILED |
| null 响应或空文本 | FAILED / MODEL_RESPONSE_EMPTY |
| 明确 length 截断 | FAILED / MODEL_RESPONSE_TRUNCATED |
| JSON/Schema 非法 | FAILED / OUTPUT_INVALID |
| 越界、重复、跨 KB 引用 | FAILED / CITATION_INVALID |
| Context NPE 等程序缺陷 | HTTP 500 / 公共 INTERNAL_ERROR，无 RagAnswer |

参数非法沿用 HTTP 400 / 40000，知识库不存在沿用404。HTTP 200 表示领域执行结果已正常封装，不表示 data.status=ANSWERED。

## 异常与日志责任

ContextBuilder 的身份冲突为显式一致性拒绝；Jackson 序列化异常为受控构建故障，两者由 RAG_CONTEXT 输出一次安全 ERROR（固定分类、故障位置，不接收原始异常消息或原因正文）。TokenEstimator 当前为本地纯计算，无外部依赖；其运行时异常、null 候选和其他意外逻辑缺陷继续向全局处理器传播，由 API_INTERNAL_ERROR 输出最终 ERROR。RagService 不重复打印堆栈。

模型调用保留原有 ModelFailures 分类和安全 Cause，RAG_GENERATION 负责一次 ERROR；检索失败由 M2 原故障点负责。Parser/引用拒绝只输出固定 WARN。未配置、空响应和截断保留生成阶段的固定分类诊断。单次请求中的 Embedding 失败会在检索阶段结束，不会继续 Chat；既有 M2/FailureLog 测试继续覆盖责任和脱敏。

RagService 对实际执行的 RETRIEVAL、CONTEXT_BUILD、GENERATION、OUTPUT_PARSE、CITATION_VALIDATE 用 System.nanoTime()，finally 记录 elapsedMillis；未执行阶段没有日志。OVERALL finally 恰好尝试一次，包含 status/error、策略、degraded、selectedEvidenceCount、citationCount。程序错误时没有领域结果，状态等为 null，不伪造 FAILED 对象。阶段 INFO 不带正文或堆栈，不复制 M2 的内部阶段。原生成日志保留 promptVersion、安全模型名。日志 RuntimeException 被本地消费，不递归记录；后端不可用时不能保证事件送达，但不改变返回。

## usage

仅使用 ChatResponse metadata 的非 EmptyUsage，不使用 TokenEstimator 替代。超时、null 响应或无元数据保持 null；有元数据的空文本/截断通过 GenerationException 保留。完整候选的成功、拒答、解析失败和引用失败均保留真实 usage；不新增 Chat 调用。

## 离线验证与隔离方法

新增 RagReliabilityTests：MockMvc + 正式 RagService + spy 真实 ContextBuilder + 真实 Parser/Validator + 可控 ChatModel。覆盖完整 HTTP 状态矩阵、单/多证据、跨 KB、空响应、日志后端抛异常、阶段执行/未执行计数、OVERALL 一次、单次 Context/Chat、usage 和正文不回显。复用现有 CitationValidationTests、ContextBuilderTests、AnswerGeneratorTests、RagApiTests 的协议、身份、日志、原因链和参数测试；旧日志断言改为精确筛选 WARN/ERROR，新增 INFO 不应导致误报。

初轮85项有2项旧日志总数断言失败，已收窄到拒绝 WARN；扩展测试中曾缺少 doReturn import，编译失败后补齐。中间93项定向全部通过；最终数量见完成记录。

隔离副本在系统临时目录 ka-m305-verify-h1jcd1iy。复制所有 tracked 文件及新增文件（包含源码、pom、全部资源、迁移、测试、.gitignore），逐文件字节核对；初始化临时 Git 以满足配置保护测试。开发者 application-local.yml 不改动。

首次以 HEAD local 配置运行 clean verify：Surefire333项，331通过、2项 ProfileConfigurationTests 失败，Failsafe 未执行。证据表明 HEAD local 配置自身启用模型，不能把它称为安全默认。第二次仅在隔离副本关闭 enabled 并清空 api-key；其余资源、生产/测试源码、pom 与工作区一致。这是明确的验证配置例外，不代表原开发配置默认断言通过。

命令：`mvn "-Dmaven.repo.local=D:/develop/repository" spotless:check clean verify`，不启用 online-model。测试自行管理既有 Testcontainers，未手动启动基础设施或访问真实供应商。

## 未验证边界

未进行真实 DashScope 联调、供应商 JSON 兼容性或质量评估。合法引用不证明语义支持。未知供应商改写/编码的敏感片段及第三方自主日志仍受既有日志规范限制。日志后端不可用会丢失观测事件。M3-06 在线验证必须独立授权。

## 完成记录

2026-11-11 18:26:08（Asia/Shanghai），隔离默认 `spotless:check clean verify` BUILD SUCCESS，耗时13:25。Spotless、生产/测试编译通过；Surefire342项、Failsafe43项，共385项，失败/错误/跳过均0。最终工作区定向95项全部通过（其中新增完整链路21项），日志 ka-m305-targeted-complete.log 位于系统临时目录。

最终副本源码、全部资源（除明确说明的 local 安全覆盖）、测试、pom 共237个文件与工作区逐文件字节比较一致。配置安全覆盖只发生在临时副本，不提交它，也不宣称开发配置的两项默认断言已通过。verify-final.log 和 JUnit XML 保留在临时副本 target/surefire-reports、target/failsafe-reports；不提交原始日志。

Failsafe 实际结果（测试 / 失败 / 错误 / 跳过）：

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

最终完整 diff 审阅和 git diff --check 通过；工作区 application-local.yml 与基线一致，未 Push/Merge/Rebase。提交 SHA 见交付报告，或通过本文 git log 查询。

结论：READY FOR M3-06，仅表示离线工程可靠性验收通过。真实供应商联调和质量评估尚未执行，需按后续任务单独开展。

