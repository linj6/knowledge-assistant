# M3-04 结构化输出解析与引用校验

## 范围与协议

基线 feature/m3-rag / 6ad8e6d6e9e466cd015adb6494866c127f8f33a6。保留并完善已有 StructuredOutputParser、CitationValidator 草稿，接入 RagService，不修改检索算法、数据库、预算、依赖版本或本地敏感配置。

模型输出必须是单个 JSON Object，恰好包含 answerable（Boolean）、answer（非 null String）、citations（非 null 字符串数组）。使用项目现有 Jackson 3 JsonMapper、StreamReadFeature.STRICT_DUPLICATE_DETECTION、DeserializationFeature.FAIL_ON_TRAILING_TOKENS；逐节点判断实际类型后读取，不使用 DTO 自动转换。拒绝重复属性、额外字段、非 Object 根、代码围栏、前后额外文本。允许 JSON 自身前后空白。

解析前限制最多32768个 Java UTF-16 code unit；没有自动修复或二次模型请求。answer 必须非空白；answerable=true 必须至少一个引用，false 必须零引用。Jackson 原始异常不进入对外异常或日志，统一 OUTPUT_INVALID。

## CitationValidator 信任边界

引用编号为 E[1-9][0-9]*，实际白名单是同次生成使用的 AssembledContext.evidenceMap。引用不得重复，映射 key 与 evidenceId 一致，证据同时存在于 selectedEvidence。KB 与请求一致，Chunk/Document/IndexVersion 为正 Long，sourceStart>=0、sourceEnd>sourceStart。证据身份、正文、标题及位置还必须匹配同次 RetrievalResult 中的候选。任一失败整体 CITATION_INVALID，不返回部分引用。

发布归属依赖正式 M2 查询时的既有保证：PublishedVersionSnapshotRepository 校验活动版本与文档关系；VectorSearchMapper.xml 通过 KB、活动版本 JOIN、文档与版本成对快照约束筛选；KeywordSearchRepository 和 KeywordChunkRepository 验证 ES 候选并用 PG 权威元数据回填，校验 KB、版本文档归属与快照成员。融合/重排保留候选身份。

RetrievalResult/RankedChunk 的公开构造器及 snapshotHash 本身不能证明发布归属。调用者必须传入正式 RetrievalService 在同次请求得到的结果，不能把任意构造的 ID 或 hash 当成发布证明。测试固定候选模拟这个信任前提，并验证篡改 Context 身份会被拒绝；实际 M2 约束沿用既有回归。本次不重复读取 PG 或快照，也不承诺生成结束时重新核验发布状态。

RagCitation 仅由 SelectedEvidence 构造，按模型引用顺序返回，Long 沿用全局字符串序列化，不接受模型自报数据库身份。

## 编排、状态与诊断

完整保留 RetrievalResult，构建一次 Context、调用一次 AnswerGenerator，保留 RawGenerationResult 后解析和校验。没有第二次生成、重新编号或检索。

- ANSWERED：非空白答案、至少一条通过校验的引用、error=null。
- INSUFFICIENT_EVIDENCE：检索为空时 answer=null；模型合法拒答时保留说明；引用恒为空、error=null。
- FAILED：answer=null、citations=[]、error 非空。解析错误 OUTPUT_INVALID；引用错误 CITATION_INVALID；检索、Context、M3-03 模型故障分类保持不变。

RagAnswer 构造器约束三态字段关系。移除 GENERATION_NOT_VALIDATED。保留 Result、requestId、策略及 API 序列化。diagnostics.usage 最小透传模型报告的输入/输出/总 Token 数；不可取得时 null，不以 Context 估算替代 usage。不暴露 rawText、Prompt、Context、模型 JSON 或 Throwable。

FailureLog.rejected 以固定阶段及分类记录一次 WARN 和 requestId，不接收原始异常或模型文本；Context 原有安全 ERROR 与生成故障日志责任保持不变。

## 离线验证

新增 CitationValidationTests 使用真实 Jackson、真实 CitationValidator、固定 Context/检索候选和 Fake ChatModel。覆盖严格协议正反例、超长响应、未知/重复/未入选引用、跨 KB 与异常身份/位置、来源映射及顺序、完整 Long 字符串、单次 Context/Chat、合法拒答、解析/引用日志安全和 usage。不 mock CitationValidator。M3-01～M3-03 与必要 M2 继续回归。

最终命令与数量在完成记录补充。

## 未验证边界

引用结构与身份合法不代表答案事实正确或每条引用语义支持结论。本次未实现语义判断、M4 评估、Agent、Memory、多轮或 Streaming；未调用真实供应商，不验证在线生成质量或供应商 JSON 协议兼容性。Architecture Spec 的 Boot 3.x 与当前 pom 4.1.2 差异沿用既有记录，未修改冻结文档。

## 完成记录（2026-11-11）

- `mvn "-Dmaven.repo.local=D:/develop/repository" spotless:apply test-compile -DskipTests`：Spotless apply/check、生产及测试编译通过。
- 最终定向：`mvn "-Dmaven.repo.local=D:/develop/repository" spotless:apply test "-Dtest=CitationValidationTests,AnswerGeneratorTests,ContextBuilderTests,RagApiTests,ModelCapabilityTests,FailureLogTests,Retrieval*Tests,KeywordRetrievalTests"`：126项全部通过，0失败/错误/跳过，其中新增 CitationValidationTests 43项。最初测试源码存在两处 Java 字符串转义错误，Spotless 拒绝格式化；修正后首轮124项通过，再补充两项，最终126项通过。
- 默认 `mvn "-Dmaven.repo.local=D:/develop/repository" spotless:check verify`：Surefire 321项，319通过、2失败、0错误/跳过。仅 ProfileConfigurationTests.commonAndLocalDefaults、embeddingConfigurationCanBeOverriddenOffline 的既有默认关闭断言失败；未读取或修改 application-local.yml，未放宽断言。
- Surefire 失败导致 Failsafe 未执行；没有 Failsafe 验收结论，未构造隔离验证副本。默认 Surefire 的已有 Testcontainers 测试自行使用容器，未手动启动基础设施。没有运行 online profile 或真实供应商 API。
- Maven 原始日志仅留系统临时目录 ka-m304-compile.log、ka-m304-targeted-final.log、ka-m304-verify.log，不纳入提交。
- 完整审阅任务相关生产、测试、新增文件和文档，git diff --check 通过。仅10个任务文件，未改 Migration、检索、依赖或冻结文档。提交 SHA 见最终交付报告，也可通过本文 git log 查询。
