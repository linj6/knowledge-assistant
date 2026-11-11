# M3-01 RAG API 与编排骨架

接口：`POST /api/v1/chat/answers`，沿用 MVC `/api` 前缀与 `Result<T>`、requestId、Long 字符串序列化及公共异常处理。

```json
{"knowledgeBaseId":"9007199254740993","question":"索引何时可检索？","strategy":"HYBRID_RRF"}
```

question 非空，最多4000个 Java UTF-16 code unit；knowledgeBaseId 为正数 Long。strategy 缺省为 HYBRID_RRF，只接受单个枚举。retrievalParameters 整体缺省复用 defaults；提供时五个参数均须满足既有范围及策略候选池约束，不改变 M2 请求语义。

## 状态与边界

- EMPTY_RESULT → INSUFFICIENT_EVIDENCE，answer=null、citations=[]、error=null。
- FAILED → FAILED / RETRIEVAL_FAILED，diagnostics.retrievalError 保留安全检索错误枚举。
- SUCCESS → FAILED / GENERATION_NOT_IMPLEMENTED，绝不以候选存在冒充 ANSWERED。

以上合法请求均返回 HTTP 200、Envelope code=0，领域失败由 data.status/error 表达。非法请求沿用 HTTP 400 / 40000；知识库不存在由原检索服务及公共异常处理返回404。

RagService 只负责请求校验、默认值、单策略检索和状态映射。成功分支明确标出 ContextBuilder → AnswerGenerator → CitationValidator 的后续接入位置；不预建空接口或无实现 Bean。API 请求 DTO 独立，复用不可变 RetrievalParameters 值对象，响应领域对象不包含持久化实体。诊断只含检索状态、错误枚举、快照 hash，不复制完整候选诊断或正文。

引用协议预留 evidenceId、chunkId、documentId、indexVersionId、sourceStart/sourceEnd，Long ID 继续使用全局字符串序列化。目前不构造任何引用或答案。不实现上下文预算、Prompt、Chat 调用、JSON 解析、引用校验、降级、Memory 或 Agent。

## 验证

定向首轮 RagApiTests、RetrievalApiTests、RetrievalServiceTests：34项通过，随后补充超长问题和候选池校验。最终默认回归结果见任务完成报告。不读取或修改 application-local.yml，不调用真实模型。

最终验证：Spotless apply/check、test-compile、git diff --check 通过。默认 `mvn spotless:apply spotless:check verify` 执行257项，255通过、2失败、0错误/跳过；失败均为 ProfileConfigurationTests 的模型启用状态与默认关闭断言不符。保留开发者验收配置，不修改测试断言。新增 RagApiTests 10项全部通过，M2 API/Service 测试通过。Surefire 失败导致 Failsafe 未执行，不声称容器集成全量通过。默认发现范围为 pom 的 *Tests 与显式16类 Failsafe IT；在线模型及额外性能/持久化专项不属于默认名单。本轮未请求真实供应商、未手动启动基础设施。
