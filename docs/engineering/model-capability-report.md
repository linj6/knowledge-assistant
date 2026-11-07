# M0-06A Chat / Embedding 能力验证

## 状态与方案

2026-11-07：**已通过验收：Chat / Embedding 真实供应商连通性验证完成。**
供应商候选为阿里云百炼 DashScope，使用 Spring AI 官方 `spring-ai-openai:2.0.2`，不使用 Starter。
Maven 实际解析成功；通过实际 JAR 的 javap 核对 Builder API，使用当前 OpenAIClient 构建路径。
不引入 Alibaba 集成：其稳定兼容矩阵对应 AI 1.1.x，2.x 里程碑不能证明兼容本项目。
官方 HTTP API 为备选，本次不额外维护协议客户端；当前 DashScope Endpoint 的 Chat / Embedding 协议兼容已通过真实请求验证。

来源：[Alibaba 版本矩阵](https://www.java2ai.com/en/docs/versions/)、
[DashScope Embedding API](https://help.aliyun.com/en/model-studio/embedding-interfaces-compatible-with-openai)。

## 配置

复用 assistant.model。默认 enabled=false，不创建模型，不要求密钥。
启用时校验密钥、Chat/Embedding 模型 ID、HTTP endpoint、1–300 秒超时。
保留 M0-04 的 ModelProperties 校验，客户端配置增加启用时检查。
base-url 默认为 https://dashscope.aliyuncs.com/compatible-mode/v1；区域与账号匹配由在线操作者确认。
模型 ID 无默认值；dimensions 为可选 Java 配置属性，省略时请求供应商默认值。
SDK maxRetries=0；Chat 输出上限128 tokens。不打印配置、鉴权头或原始错误正文。
ModelFailures 提供安全错误分类；在线入口只抛出无原始 cause 的分类异常。

## 离线已验证范围

- ChatModel / EmbeddingModel 实际构建；本地 HTTP Chat 请求及非空文本响应解析。
- 本地 Embedding HTTP 请求使用 float 编码；故意倒序返回 index，按 index 关联输入。
- Jackson JSON 对象、answer 字符串、citations 字符串数组校验；非法 JSON 和错误类型拒绝。
- 向量数量、维度一致、期望维度、有限浮点数、index 唯一且覆盖输入范围。
- Mock HTTP 401、429、503、非法响应失败；超时异常分类；安全异常不含模拟密钥或供应商正文。
- 离线测试不访问真实模型、PG、ES、MinIO。

## 修复后的真实在线实验（2026-11-07）

技术环境为 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2，使用 Spring AI 官方 OpenAI 集成与 DashScope OpenAI-compatible API。Chat 模型为 `qwen-plus`。当前本地配置与已有可靠记录未能确认 Embedding 模型 ID，因此不推测或补填。

| 阶段 | 结果 | 耗时 | 其他数据 |
|---|---|---:|---|
| CHAT_PLAIN | 成功 | 3272 ms | input=18，output=1，total=19，finish_reason=STOP |
| CHAT_JSON 第 1 次 | 成功 | 813 ms | input=33，output=19，total=52，finish_reason=STOP |
| CHAT_JSON 第 2 次 | 成功 | 878 ms | input=33，output=19，total=52，finish_reason=STOP |
| CHAT_JSON 第 3 次 | 成功 | 787 ms | input=33，output=19，total=52，finish_reason=STOP |
| EMBEDDING_SINGLE | 成功 | 413 ms | count=1，dimensions=1024，tokens=8，indices=[0] |
| EMBEDDING_BATCH | 成功 | 198 ms | count=3，dimensions=1024，tokens=26，indices=[0,1,2] |

修复后的本轮实验共 6 次真实模型请求，全部成功；Maven `BUILD SUCCESS`，`ModelOnlineIT` 1 项测试通过，0 失败、0 错误、0 跳过。该请求数不包含首次失败实验。Chat usage、finish_reason 和模型 ID 均能正确读取；三次 JSON Mode 实际模型均为 `qwen-plus`，返回均符合当前 `answer` 字符串与 `citations` 字符串数组的字段类型约束。

单条和批量 Embedding 实测维度均为 1024。本次维度仅适用于当前模型配置，不能推断其他 Embedding 模型或配置也使用 1024 维。批量响应本次 indices 为 [0,1,2]，应用仍须按 index 关联输入，不能假设供应商响应始终有序。

在线成功路径为普通 Chat 1 次、原生 JSON 3 次、单条和三条 Embedding 各 1 次。每次客户端超时 20 秒，测试总上限 180 秒，不自动重试；失败立即终止。在线不启动 Spring 上下文或基础设施。

## 技术结论与尚未验证的边界

- Spring AI 2.0.2 官方 OpenAI 集成能够通过当前 DashScope Endpoint 完成 Chat 和 Embedding 请求；`qwen-plus` 原生 JSON Mode 已通过基础连通性验证。
- JSON Mode 成功不等于业务 Schema 永远正确。后端仍需校验字段类型、引用 ID 的存在性与归属；尚未完成 JSON Schema 原生能力验证或结构化输出的统计可靠性评估。
- 自定义 dimensions 与默认维度差异尚未实测。1024 维向量已实测，正式数据库维度约束与索引设计仍需结合后续数据模型任务确认；pgvector 兼容性尚未实测，存储维度上限 16000 不代表索引支持该上限。
- 表中耗时为单次实验观测值，不代表性能基准或供应商 SLA。没有账单或真实费用数据，不推算模型费用。
- Rerank、Tokenizer 属于 M0-06B，本次未验证，M0-06B 未标记完成。

## 执行方式

默认：`mvn clean verify`，Surefire 默认不执行 `*IT`。
在线运行前在本地安全设置环境变量，不把密钥放入命令历史、报告或仓库：
ASSISTANT_MODEL_ENABLED=true、ASSISTANT_MODEL_API_KEY、ASSISTANT_MODEL_CHAT_MODEL、ASSISTANT_MODEL_EMBEDDING_MODEL。
可选 ASSISTANT_MODEL_BASE_URL、ASSISTANT_MODEL_DIMENSIONS。

```powershell
mvn '-Dtest=ModelOnlineIT' test
```

未显式启用或缺少配置会失败，不跳过。上述命令为已完成真实实验的执行命令；本次仅归档既有证据，不重新执行。

## 离线验证历史与最终验收

此前离线工程验证中，`mvn spotless:apply`、`mvn spotless:check`、`mvn clean verify`、`git diff --check` 已通过；最初默认测试 30 项，其中新增模型测试 7 项，失败、错误、跳过均为 0。JSON Options 修复后的默认离线测试增至 33 项并全部通过。这些是历史离线结果，与上面的真实在线实验分别记录。

M0-06A **已通过验收：Chat / Embedding 真实供应商连通性验证完成。** 本次归档仅修改文档，不执行 Maven、在线请求或 Docker，不进入 M0-06B。

## 首次在线实验失败与修复排查（2026-11-07）

用户提供的真实记录：qwen-plus 普通 Chat 成功，input=18、output=1、total=19，耗时2994ms，finish_reason=STOP；JSON 阶段报错 `stage=CHAT_JSON kind=OTHER`，Embedding 未执行。此处为用户提供证据，本轮未重跑真实请求。

实际 2.0.2 JAR 的 buildRequestPrompt 仅在 Prompt.options=null 时采用模型默认 Options，不合并非空请求 Options。
离线确认全新 OpenAiChatOptions.builder() 默认模型是 gpt-5-mini；旧 JSON 调用因此丢失已配置的 qwen-plus。
最小修复：从模型当前 Options 派生请求配置，仅修改响应格式：

```java
chat.getOptions()
    .mutate()
    .responseFormat(format)
    .build();
```

该方式保留模型、超时、零重试和 token 上限。
本地 HTTP 回归确认模型、max_tokens=128、response_format.type=json_object；Options 保留超时和零重试，且不修改模型默认 Options。

原真实异常已被脱敏，无法从 OTHER 还原类名或 HTTP 状态，不宣称供应商不支持 JSON Mode。
增加 REQUEST_REJECTED（其他 HTTP 4xx）与 CONNECTION 分类，安全失败消息仅增加异常类名、可得的 HTTP 状态，不输出消息正文或 Header。
离线连接失败实际类型为 OpenAIIoException；不能将该本地实验异常当成原真实失败类型。
本轮 spotless:apply、spotless:check、clean verify、diff --check 通过；33项默认离线测试全部通过。
原始失败异常已被安全处理，无法直接还原 HTTP 状态码；上述根因来自实际 SDK 行为检查与离线回归证据，而非对历史 HTTP 状态的推测。后续第二轮真实实验已确认三次 qwen-plus JSON Mode 与两次 Embedding 均成功，详见前述在线结果表。

## M0-06B-2 Rerank / Token 估算离线实现（2026-11-07）

新增qwen3-rerank原生HTTP适配器与稳定ID协议；使用JDK21 HttpClient和现有Jackson 3，无新增依赖。独立assistant.rerank默认禁用，未启用无需密钥；启用时完整HTTPS Endpoint及API Key由可信部署配置提供，不接受请求参数覆盖，不拼接Chat地址。模型固定为当前支持的qwen3-rerank，超时默认30秒、允许1–300秒。无应用自动重试，不跟随重定向；响应体最多256 KiB，完整响应受总超时限制。

空候选不联网，拒绝重复ID/非法输入/超过500候选；冻结候选快照，发送min(topN,N)且关闭原文返回。按原始index映射ID，严格校验数量、整数索引、唯一性及有限[0,1]分数；全部校验后按分数降序、同分按输入顺序输出。协议错误整次失败，安全异常保留ModelFailures分类但不保留供应商正文或原始cause；不记录原文和密钥。

TokenEstimator为独立本地估算器：Unicode码点数加调用方指定固定开销，再按可配置百分比加余量并向上取整，默认20%。使用long和安全乘加，返回estimatedTokens及exact=false。支持空串、中文、英文、Emoji、代码和长文本；null及非法参数拒绝。20%未经真实usage校准，不保证不会低估；不是模型专用Tokenizer，不实现ContextBuilder或窗口预算。

当前无法精确预检供应商Token限制，不将码点数伪装为4000/120000 Token硬限；供应商超长拒绝分类REQUEST_REJECTED。具体设计细化见[m0-06b-design.md](m0-06b-design.md)。

尚未执行真实Rerank在线验证；账户地域、完整Endpoint、业务空间、模型权限、实际限额、usage与费用均待在线验收。Fake协议通过不能证明真实服务连通性或排序质量，M0-06尚未整体验收。未实现M2/M3链路，未启动Docker、未调用真实模型、未执行全量clean verify。

本轮最终验证：`mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile`、`mvn '-Dtest=RerankTests,ModelCapabilityTests' test`及`git diff --check`通过。RerankTests 6项（内部循环覆盖多种协议/错误样例）、既有ModelCapabilityTests 10项，共16项，失败0、错误0、跳过0。覆盖本地请求路径/鉴权/嵌套JSON/top_n、乱序与同分映射、截断与归一化、非法输入、索引/分数/数量异常、无效JSON与200错误体、401/403/429/400/503、302不跟随、超大响应、连接失败、请求超时和安全异常；估算覆盖Unicode与大整数边界、配置禁用和启用校验。未单独故障注入慢速响应体、TLS/DNS或真实供应商协议差异，也未运行其他测试套件。早期测试编译断言签名及连接失败测试期限问题已修正，以上为最终重跑结果。
