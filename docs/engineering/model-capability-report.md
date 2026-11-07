# M0-06A Chat / Embedding 能力验证

## 状态与方案

2026-11-07：离线工程验证；真实模型验证未完成，M0-06A 未通过最终验收。
供应商候选为阿里云百炼 DashScope，使用 Spring AI 官方 `spring-ai-openai:2.0.2`，不使用 Starter。
Maven 实际解析成功；通过实际 JAR 的 javap 核对 Builder API，使用当前 OpenAIClient 构建路径。
不引入 Alibaba 集成：其稳定兼容矩阵对应 AI 1.1.x，2.x 里程碑不能证明兼容本项目。
官方 HTTP API 为备选，本次不额外维护协议客户端；真实 DashScope 协议兼容仍待在线关闭。

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

## 已验证范围

- ChatModel / EmbeddingModel 实际构建；本地 HTTP Chat 请求及非空文本响应解析。
- 本地 Embedding HTTP 请求使用 float 编码；故意倒序返回 index，按 index 关联输入。
- Jackson JSON 对象、answer 字符串、citations 字符串数组校验；非法 JSON 和错误类型拒绝。
- 向量数量、维度一致、期望维度、有限浮点数、index 唯一且覆盖输入范围。
- Mock HTTP 401、429、503、非法响应失败；超时异常分类；安全异常不含模拟密钥或供应商正文。
- 离线测试不访问真实模型、PG、ES、MinIO。

## 真实能力记录（全部待验证）

| 项目 | 结果 |
|---|---|
| 真实 Chat / Embedding 模型 ID | 未确认，未填写 |
| 实测维度 / 固定项目维度 | 未确认，暂不固定 |
| 自定义 dimensions / 默认维度差异 | 未实测 |
| 原生 JSON mode / JSON Schema | 未实测；入口显式发送 JSON_OBJECT，区别于仅 Prompt 约束 |
| 结构化可靠性 | 未评估；三次通过也不能证明可靠 |
| usage、延迟 | 未获得真实数据 |
| 批量数量与顺序 | 仅本地协议已验证；供应商实际 index 行为待验证 |
| pgvector 兼容性 | 未获得真实维度；仅限制存储维度上限16000，不代表索引支持该上限 |
| 调用次数 / 成本 | 真实调用0次；没有账单或真实成本数据 |

在线成功路径最多6次请求：普通 Chat 1次、原生 JSON 3次、单条和三条 Embedding 各1次。
每次客户端超时20秒，测试总上限180秒，不自动重试；失败立即终止，不静默成功。
在线不启动 Spring 上下文或基础设施。模型可用性、实际返回模型 ID、费用、dimensions 能力及 pgvector 实测均须后续补录。

## 执行方式

默认：`mvn clean verify`，Surefire 默认不执行 `*IT`。
在线运行前在本地安全设置环境变量，不把密钥放入命令历史、报告或仓库：
ASSISTANT_MODEL_ENABLED=true、ASSISTANT_MODEL_API_KEY、ASSISTANT_MODEL_CHAT_MODEL、ASSISTANT_MODEL_EMBEDDING_MODEL。
可选 ASSISTANT_MODEL_BASE_URL、ASSISTANT_MODEL_DIMENSIONS。

```powershell
mvn '-Dtest=ModelOnlineIT' test
```

未显式启用或缺少配置会失败，不跳过。暂未执行真实在线请求，等待本地密钥。

## 本轮验收结果

mvn spotless:apply、mvn spotless:check、mvn clean verify、git diff --check 已通过。默认测试30项，失败0、错误0、跳过0；其中新增模型测试7项。在线未运行，真实请求0次。本次提交仅包含离线工程实现，真实在线验证待后续完成。
