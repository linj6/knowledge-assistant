# RAG Package 重构记录

## 结构调整

原 RAG 实现类集中在 `rag` 根包，Controller 位于 `rag/api`。现将领域响应移至 `rag/model`，上下文构建移至 `rag/context`，生成移至 `rag/generation`，解析与引用校验移至 `rag/validation`。`RagService`、`RagController` 及请求 DTO `RagAnswerRequest` 保留原包。

## 移动清单

- `model`：RagAnswer、RagCitation、RagStatus、RagError
- `context`：ContextBuilder、ContextProperties、AssembledContext、SelectedEvidence
- `generation`：AnswerGenerator、RawGenerationResult
- `validation`：StructuredOutputParser、CitationValidator

生产引用调整涉及 RagService、RagController 与各子包类型依赖；测试引用调整涉及 AnswerGeneratorTests、CitationValidationTests、ContextBuilderTests、RagApiTests、RagReliabilityTests。旧类路径检索无残留。

## 行为与装配审查

除 package、import 和移动路径外，生产类型方法体与成员保持一致；未改变调用顺序、异常处理、日志、Prompt、预算、解析/校验规则及 HTTP/JSON 类型结构。未改变成员可见性。Spring 组件仍在 `com.lnjecit.knowledge.assistant` 根扫描范围内，`@Component`、`@Service`、`@ConfigurationProperties` 声明及模型条件配置未改。

## 验证

- `spotless:apply` 后 `spotless:check` 通过。
- `compile test-compile` 通过（生产 143 个、测试 74 个源文件）。
- RAG 与 Retrieval 定向测试：至少 130 项通过，0 失败/错误/跳过（按相关 Surefire 报告汇总）。
- `git diff --check` 通过。
- 未调用真实模型，未读取或修改 `application-local.yml`。未执行完整 Spring Profile 配置回归；包改动通过定向测试和编译验证。
