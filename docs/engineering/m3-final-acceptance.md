# M3 最终综合验收与合并准备

## 目标、范围与阶段成果

M3 完成工程化单轮 RAG 问答。M3-01 建立 API 与流程骨架；M3-02 完成证据选择和身份映射；M3-03 接入固定 Prompt 与 Chat；M3-04 加入严格解析和引用校验；M3-05 完成状态、异常、阶段日志及离线回归；M3-06 完成真实知识库与 Qwen 联调。M4 的系统语义评估不属于本次验收。

## 实际主链路与职责

`RagController` 接收请求，`RagService` 每次请求仅调用一次 `RetrievalService.search`，复用 M2 对发布快照的验证。`ContextBuilder` 按排名去重并在预算内选取完整 Chunk，生成稳定的 E1、E2 编号；服务端 `SelectedEvidence` 保留 KB、文档、版本、Chunk 和来源位置，模型只接收编号、标题与正文。构建成功后，`AnswerGenerator` 以 System 消息发送 `rag-answer-v1` 合约，以 User JSON 发送 question 和 evidence，只调用一次 Chat。`StructuredOutputParser` 严格解析结果，`CitationValidator` 仅接受本次 Context 中且与同次检索候选完整身份一致的引用。校验完成后才构造 `RagAnswer`。

## 协议和信任边界

Prompt 把问题及文档指令视为数据，要求模型只依据 Evidence 回答。输出必须是仅含 `answerable`、`answer`、`citations` 的 JSON；拒绝重复或额外字段、错误类型、空说明、可答无引用及拒答有引用。模型只能给出 E 编号，不能自报数据库身份。引用校验无额外数据库查询，依赖同次正式 M2 检索的发布快照边界；身份合法不证明语义支持。

`RagAnswer` 有 ANSWERED、INSUFFICIENT_EVIDENCE、FAILED 三态。空检索和合法拒答属于证据不足；检索、构建、模型、解析或引用失败返回分类后的 FAILED；参数错误及未预期程序异常走既有 HTTP 异常处理。模型输入预算和 Context 预算均为本地 Token 估算，不是供应商严格上限；usage 仅来自 Chat 响应元数据，缺失时不补造。

## 日志、异常与安全

RequestId 贯穿主链路。已执行的 RETRIEVAL、CONTEXT_BUILD、GENERATION、OUTPUT_PARSE、CITATION_VALIDATE 阶段分别记录耗时，OVERALL 在 finally 中尝试一次。正常拒答与故障状态分开；未处理程序错误由全局异常处理器诊断。阶段日志不记录问题、证据、Prompt、模型原文或密钥；日志后端 RuntimeException 不改变业务结果。系统消息约束和严格引用身份校验降低文档注入与伪造引用风险，但不能保证回答事实正确。

## 代码审查与合并差异

比较基线为本地及 origin/main 的 `8c7c25179d9c28c4bdd89e2cf8c99fda7e8aa73e`，共同基线相同；审查起点 `de4c3aac14efe171f5308f39b472e846617af6f4` 领先 main 12 提交、落后 0 提交。M3 变更为 28 个文件、2436 行新增、4 行删除；没有 pom、Migration、`application-local.yml` 或临时日志变更。未执行 fetch，远端状态仅代表本地跟踪引用。M3-05 验收代码提交 `cfb0edb` 之后，至审查起点仅两份工程文档变化，生产及测试源码一致。审查未发现 BLOCKER 或 MAJOR。ACCEPTED：引用身份合法不能证明语义支持；本地 Token 估算非供应商精确计量。MINOR：README 此前仍称检索与 RAG 未实现，本次已最小修正。

## 验收证据与未验证事项

M3-05 在隔离副本、安全配置覆盖下执行 `spotless:check clean verify`：Surefire 342、Failsafe 43，共 385 项，失败、错误、跳过均为 0；原 HEAD local 配置触发的两项 ProfileConfigurationTests 失败已在 [M3-05 记录](m3-05-rag-reliability.md)说明，不能视作默认配置通过。当前源码与该完整回归对应源码一致，故复用完整回归证据。

M3-06 的五次授权真实问答均 HTTP 200，四次 ANSWERED、一次 INSUFFICIENT_EVIDENCE；四次 BM25、一次 HYBRID_RRF；请求与实际策略一致、degraded=false。五次均有六阶段日志及唯一 OVERALL，模型 qwen-plus、Prompt rag-answer-v1，已验证真实 Chunk 引用映射。详见 [M3-06 记录](m3-06-online-rag-verification.md)。供应商侧精确请求次数没有独立证据，A 场景 usage 未记录；本次未重复真实调用。

## M4 前置问题与结论

M4 应建立固定数据集，评估回答事实正确性、引用语义支持、拒答质量及不同检索策略的效果，并明确成本和 usage 观测口径。M3 的工程验收门槛是单轮 RAG 主链路及引用身份闭环，而非语义质量达到生产标准。按当前本地 Git 基线与已有验收证据，结论为 **READY TO MERGE**；合并、推送及远端同步均不在本次执行范围。
