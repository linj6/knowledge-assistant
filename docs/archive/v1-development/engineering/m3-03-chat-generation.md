# M3-03 Prompt Contract 与 Chat Generation

## 范围与文件

基线分支 feature/m3-rag，包含 M3-02 补充提交 af66cfb9c9a8f59d5496e5274bfe80b1192ec465，修改前工作区干净。

新增 AnswerGenerator、RawGenerationResult、prompts/rag-answer-v1.txt、AnswerGeneratorTests 和本文；修改 RagService、RagError、FailureLog、ContextBuilderTests、RagApiTests。没有实现输出解析、Schema 校验、CitationValidator 或 M4，没有修改依赖、迁移、检索算法或 application-local.yml。

## Prompt 与 Spring AI

固定版本 rag-answer-v1，资源路径与首行版本声明双重约束；加载失败或版本声明不符立即拒绝。SystemMessage 承载固定规则，UserMessage 是 question 与 evidence 两字段 JSON。问题由 Jackson 转义，Evidence 使用 ContextBuilder 的模型可见 JSON，不发送服务端来源身份、Gold Evidence 或人工答案。文档及问题明确为不可信数据，指令不能提升为系统规则。测试只证明消息和数据边界，不证明在线模型能抵抗所有 Prompt Injection。

实际 pom 为 Spring AI 2.0.2、Boot 4.1.2；Architecture Spec 的 Boot 3.x 差异沿用既有记录，未改冻结文档。读取本地 2.0.2 JAR 的 javap 签名并通过真实 OpenAiChatModel + 本机 HTTP Stub 验证：ChatModel.call(Prompt)，ChatResponse.getResult().getOutput().getText()，响应 metadata.getModel()/getUsage()，Generation metadata.getFinishReason()。SDK 对 stop 返回 STOP，原始 finish reason 保留；length 按大小写不敏感识别截断。

生产调用复用条件 ModelConfiguration 的 OpenAiChatModel，以 ObjectProvider 延迟取用；模型关闭时不要求 Chat Bean 或 API Key，生成返回 MODEL_NOT_CONFIGURED。既有 ModelProperties 对显式启用但无 API Key 的启动校验仍保留；本次不更改全局配置校验协议。Bean 获取期间配置类故障按既有 ModelFailures 分类。

请求 options 从 openAi.getOptions().mutate() 派生，保留模型、地址、密钥与 timeoutSeconds 超时，覆盖 temperature=0.1、maxTokens=1024、maxRetries=0；应用不做重试。不可用新 builder 默认值替代现有配置。Fake 可直接替换 ChatModel，无附加适配层。HTTP Stub 验证实际 max_tokens、temperature、模型及消息角色，基础客户端默认128不被请求修改。

不强制 JSON Mode：本次通过 Prompt 要求严格 JSON，没有把 SDK 对 response_format 的支持等同于供应商实际兼容性。M0 的本地 JSON Mode 协议测试继续回归；不执行真实模型验证。

## 输入预算与原始结果

REQUEST_BUDGET=12000 是本地静态估算配额。发送前对完整 System 与 User 文本（含问题 JSON 转义、Evidence、字段名及括号）使用 TokenEstimator(64)，64为两条消息封装的固定估算，追加既有20%余量，再预留1024输出。超限 INPUT_BUDGET_EXCEEDED，不调用模型。ContextBuilder 的4000/16000限制不变。exact=false；未经 Qwen Tokenizer 或真实 usage 校准，不提供模型窗口、计费量或严格 Token 上限保证；实际窗口须由部署配置与后续授权验证。

RawGenerationResult 包含 rawText、modelId、promptVersion、可选 usage 输入/输出/总数、可选 finishReason 与 generationElapsedMillis。没有 usage 时保留 null（EmptyUsage 也视为缺失），不伪造计费量。toString 不含候选文本。结果仅供后续 M3-04 使用，不进入 API。成功生成仍 FAILED/GENERATION_NOT_VALIDATED，answer=null、citations=[]。

## 失败与日志

MODEL_NOT_CONFIGURED、MODEL_TIMEOUT、MODEL_UNAVAILABLE、MODEL_RATE_LIMITED、MODEL_AUTHENTICATION_FAILED、MODEL_RESPONSE_EMPTY、MODEL_RESPONSE_TRUNCATED、GENERATION_FAILED、INPUT_BUDGET_EXCEEDED 分别表达配置、超时、连接/服务故障、限流、鉴权、空响应、明确截断、其他故障及输入预算。沿用 ModelFailures 的分类，不把模型故障映射为证据不足。GenerationException 保留原始 Cause，客户端只接收 RagError。

调用异常只在生成边界经 FailureLog 记录一次完整诊断；SDK HTTP 状态及供应商错误码沿用原工具提取。增加请求内 payload 排除列表，覆盖问题、固定协议、完整 Context、单条证据正文和标题，对 cause/suppressed 同样生效，不全局保存业务数据。普通连接异常信息及栈保留。每次生成 INFO 记录 requestId、耗时、安全模型标识、版本及失败枚举，不输出输入或原始响应。现有凭据脱敏规则继续适用；任意供应商改写、编码或片段回显仍无法由字符串替换绝对识别，不能宣称完整匿名化。

## 验证记录

首轮 Spotless apply/check、生产/测试编译和定向81项通过，覆盖生成、Context、RAG API、M0 模型能力、FailureLog 与必要 M2 回归。

扩展真实 SDK Stub 后首轮默认 test：278项、3失败，其一是测试误以为 SDK 保留小写 stop，实际返回 STOP，已改为不区分大小写断言；另外两项为已知 ProfileConfigurationTests 默认关闭断言。未读取或修改本地敏感配置，未放宽默认关闭断言。

最终验证记录见下文。默认发现范围为 pom 的 *Tests；未执行 online-model、付费请求或手动启动基础设施。默认 test 中仓库已有 Testcontainers 测试可能自行使用容器；没有把 Surefire 与未执行的 Failsafe 混算。

### 最终完成记录（2026-11-11）

- 扩展测试修正后默认 `mvn "-Dmaven.repo.local=D:/develop/repository" spotless:apply test`：278项，276通过、2失败、0错误/跳过，仅 ProfileConfigurationTests 的既有默认关闭冲突。没有执行 Failsafe，没有宣称全量通过。
- 此后仅补强请求内日志脱敏和 SDK 无效响应消息隐藏，并明确 Mockito imports；最终源码执行 `mvn "-Dmaven.repo.local=D:/develop/repository" spotless:apply test "-Dtest=AnswerGeneratorTests,ContextBuilderTests,RagApiTests,ModelCapabilityTests,FailureLogTests,Retrieval*Tests,KeywordRetrievalTests"`：83项全部通过，0失败/错误/跳过，耗时1:09。包含新增生成7项、Context14项、RAG API10项以及M0/M2/日志回归。真实 SDK 本机 Stub 的429/401各只产生一次 HTTP 调用，无自动重试。
- 最终 Spotless apply/check、生产和测试编译通过，git diff --check 通过。最终源码最后的日志补强由83项定向验证覆盖，未再重复278项默认回归。
- Maven 原始日志在系统临时目录 ka-m303-*.log，不提交日志。未构造隔离验证副本，未读取、修改或输出 application-local.yml。
- 已审阅全部任务文件及 diff；本提交 SHA 由交付报告给出，也可通过本文 git log 查询。
