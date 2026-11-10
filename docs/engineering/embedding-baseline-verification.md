# Embedding 模型基线验证

## 当前结论

2026-11-08 归档：**PASS，V1 基线已冻结**。依据操作者于 2026-11-07 在 IntelliJ IDEA 实际运行取得的四组在线结果，解除 Embedding API 基线前置阻断。证据由操作者提供，非 Codex 本次执行；本次未调用真实 API。冻结参数见 [配置基线](configuration-baseline.md)。

历史报告的 1024 维实验没有可靠模型 ID，不能用作本次模型冻结证据。

## 候选与接口依据

- Provider：阿里云 DashScope。
- 地域：华北2（北京）；实验固定使用 https://dashscope.aliyuncs.com/compatible-mode/v1，完整请求路径为 /embeddings。账号与密钥地域尚待操作者确认。
- 请求模型：text-embedding-v4；显式 dimensions=1024、encoding_format=float。
- 距离：cosine，为已冻结的检索选择，不是本实验请求参数，也未进行检索质量评估。
- 集成：Spring AI 2.0.2，Spring Boot 4.1.2，Java 21。
- 单次超时20秒，SDK maxRetries=0，串行执行，首次失败停止，最多4次请求。

[DashScope 官方接口说明](https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai) 明确支持该模型、dimensions=1024、float 编码；北京旧域名仍可使用，官方同时推荐业务空间专属域名。本实验不自动迁移端点。

## 历史代码准备与最小调整（配置重构前）

现有 ModelConfiguration 已将模型和维度写入 OpenAiEmbeddingOptions，关闭 SDK 重试，无需修改业务链路。通用 ModelOnlineIT 会调用 Chat，不能直接用于本次四请求额度，因此新增独立 EmbeddingBaselineOnlineIT。

原有环境变量为 ASSISTANT_MODEL_ENABLED、ASSISTANT_MODEL_API_KEY、ASSISTANT_MODEL_BASE_URL、ASSISTANT_MODEL_CHAT_MODEL、ASSISTANT_MODEL_EMBEDDING_MODEL；原在线测试另读 ASSISTANT_MODEL_DIMENSIONS 和 ASSISTANT_MODEL_ONLINE_AUTHORIZED。application.yml 未显式声明 dimensions 的环境变量占位符。

本实验模型、维度、北京端点均固定在独立测试中，不受模型或端点环境变量覆盖；复用配置校验所需 Chat 占位值，但不创建或调用 Chat 客户端。

## 样本与在线结果

| 样本 | 输入 | 状态 |
|---|---|---|
| E01 | 如何排查 RabbitMQ 消息堆积？ | PASS |
| E02 | How does PostgreSQL MVCC work? | PASS |
| E03 | Spring Boot 使用 pgvector 实现 RAG 检索 | PASS |
| E04 | E01、E02、E03 批量输入 | PASS |

操作者于 2026-11-07 在 IntelliJ IDEA 实际运行 EmbeddingBaselineOnlineIT，提供以下结果：

| 样本 | requestedModel | responseModel | dimensions | count | elapsedMs | indices | requestId |
|---|---|---|---|---|---|---|---|
| E01 | text-embedding-v4 | text-embedding-v4 | 1024 | 1 | 3075 | [0] | unavailable |
| E02 | text-embedding-v4 | text-embedding-v4 | 1024 | 1 | 333 | [0] | unavailable |
| E03 | text-embedding-v4 | text-embedding-v4 | 1024 | 1 | 230 | [0] | unavailable |
| E04 | text-embedding-v4 | text-embedding-v4 | 1024 | 3 | 699 | [0,1,2] | unavailable |

本轮成功验证总计四次真实请求（3 次单条、1 次批量，共返回 6 个向量），不是 6 次请求。此前配置失败实际请求 0 次；本次归档请求 0 次。未提供逐次精确时间戳，不补造；requestId=unavailable，不编造供应商标识。

已验证：上述样本的 API 连通性、请求与响应模型一致、返回数量、非空 1024 维有限浮点向量、index 唯一且覆盖输入及批量对应关系（按现有测试检查逻辑）。耗时仅为这四次观测，不构成性能承诺。

未验证：pgvector 存储及 cosine 检索、文档与查询端到端一致性、RAG 检索质量、召回率、生成质量、长文本/容量/并发/稳定性及其他地域或模型。API 连通性通过不等于 RAG 检索质量通过；未实施或验收 M1-01。

实验逐次检查数量、非空向量、1024维、有限浮点数及 index 唯一且覆盖输入；按 index 关联输入，不依赖数组顺序。仅打印样本编号、时间、模型标签、维度、数量、耗时和索引，不打印向量。Spring AI 当前实验未提取供应商请求标识，输出 requestId=unavailable，不伪造标识。响应模型使用白名单标签过滤。失败复用 ModelFailures 安全分类；模型不支持归入 REQUEST_REJECTED，不输出供应商原始正文。

## 离线证据

以下命令实际成功：

- mvn spotless:apply
- mvn spotless:check
- mvn -DskipTests test-compile（仅编译，不代表测试通过）
- mvn -Dtest=EmbeddingBaselineTests,ModelCapabilityTests,OnlineAuthorizationTests test：12项，0失败、0错误、0跳过。

新增本地 HTTP 测试验证实际请求 JSON 的模型、1024维、float 编码和输入样本；复用能力测试验证倒序 index、无效向量及安全异常分类。未运行全量、Docker 或在线测试。

## 历史在线授权检查点（归档前）

必须先取得人工明确确认，并由操作者在运行进程环境安全配置 API Key，不粘贴到日志或文档。授权后所需门禁为 ASSISTANT_MODEL_ENABLED=true、ASSISTANT_MODEL_ONLINE_AUTHORIZED=true、ASSISTANT_EMBEDDING_BASELINE_AUTHORIZED=true。

仅在获授权后执行：

```powershell
mvn -Dtest=EmbeddingBaselineOnlineIT test
```

本入口不加入默认 Surefire/Failsafe 或原 online-model Profile，避免默认构建意外计费。真实四次检查全部通过后，记录实际证据，再决定冻结 provider=DashScope、model=text-embedding-v4、dimension=1024、distance=cosine。本次不修改正式配置基线，不解除 M1-01 阻断，不实施数据库功能。

## 历史配置失败记录（2026-11-07，后由上述成功证据解除）

用户已明确授权本次最多4次 Embedding 请求，模型、维度及端点不变。执行前确认分支为 feature/m1-ingestion-indexing，HEAD 为 1233a5cfea7cb8ac66bf1f023ecd5caf968e6af6，工作区干净。审阅入口确认仅构造 Embedding 客户端，SDK maxRetries=0、串行调用、首次失败停止。

当前执行进程的 ASSISTANT_MODEL_API_KEY 未配置（仅检查是否存在，未输出密钥）。设置本次授权所需的三个布尔门禁后，实际执行：

```powershell
mvn -Dtest=EmbeddingBaselineOnlineIT test
```

结果：BUILD FAILURE；1项测试，0断言失败、1错误、0跳过。在客户端配置校验阶段停止，没有进入请求循环。安全输出：

```text
time=2026-11-07T14:19:36.464611800Z status=FAIL attempted=0
Model request failed: stage=CONFIGURATION kind=CONFIGURATION exception=java.lang.IllegalStateException
```

此为配置阻断，不是供应商返回的鉴权或模型错误。预计最多4次，实际 API 请求0次；未调用 Chat、Rerank 或其他模型，未重试、未切换端点。请求候选仍为 text-embedding-v4 / 1024维；由于没有真实请求，实际请求模型、响应模型、实际维度、数量、索引对应、请求标识与 API 耗时均无在线证据。E01–E04 均未发送。

当时结论为 **BLOCKED，未冻结**，不能解除 Embedding 前置阻断。此前“等待人工授权”的原因现已消除，剩余阻断为执行进程缺少 API Key。下一步由操作者安全配置该环境变量后再继续；不需要将密钥粘贴到对话、日志或文档。本次不更新正式配置基线，不实施 M1-01。

本次 Maven 执行中的 Spotless 检查通过，编译阶段成功（代码已是最新）。仅修改本报告，未重跑此前12项离线测试，也未运行 Docker 或全量测试。
