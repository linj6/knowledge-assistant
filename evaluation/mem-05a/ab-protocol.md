# MEM-05A 在线 A/B 方案（待 Review）

## 案例

两组共用逐题问题和历史。下表历史为空时使用空列表；非空时按所列用户、助手顺序提供一轮。A 不传记忆，B 启用固定样本的九条记忆，使用默认上限 10 条、估算预算 1000 Token。仅 AB06 使用独立副本追加一条故意错误的记忆，详见表后。各行的知识证据来自链接所指的冻结语料章节。

| 案例 | 问题与历史 | 证据与预期技术事实 | 个性化观察点与禁止错误 |
| --- | --- | --- | --- |
| AB01 技术背景 | RabbitMQ 中 TTL 重试为什么可能打乱同一订单的处理顺序？历史为空。 | [TTL + DLX 如何破坏顺序](../corpus/backend-interview-lab/rabbitmq/notes/05-ordered-messages.md)与开头 E1/E2 时序。E1 离队等待，E2 可先提交，E1 到期后回流。 | 观察是否用初学者能跟上的订单时间线。禁止把 FIFO 等同于提交顺序或声称 TTL 保证保序。 |
| AB02 学习目标 | MySQL 二级索引什么时候需要回表，怎样从执行计划看出来？历史为空。 | [聚簇索引、二级索引、回表和覆盖及读真实执行计划](../corpus/backend-interview-lab/mysql/notes/01-index-and-sql.md)。覆盖可用索引满足所需列，增加未覆盖的 amount 后需要完整行。 | 观察是否对照实验 A/B，并让用户能按计划标志判断。禁止把 Using index condition 当作覆盖或当作回表次数。 |
| AB03 Java 偏好 | 请给出 Java CompletableFuture 组合两个独立任务的最小可读代码。历史为空。 | [为什么要组合及返回值类型](../corpus/backend-interview-lab/juc/notes/06-completable-future.md)。thenCombine 合并两个已有异步结果。 | 观察显式类型、Java 21 可用性和无 Lombok。禁止把独立任务写成有前后依赖的链，或承诺非 Async 回调另开线程。 |
| AB04 无关记忆 | 用两点说明 RAG 的 Chunk 过大和过小各有什么风险，并以“跑步笔记”为例。历史为空。 | [Chunk 太大或太小各有什么风险](../corpus/ai-application-lab/04-embedding-vector-search-rag/notes/04-chunking-and-context.md)。大块混入无关信息、占预算；小块可能拆散限定条件。 | 观察 109 若实际注入，回答能否只把跑步笔记当作用户指定的例子。禁止提及私人里程图或编造跑步数据。 |
| AB05 当前要求优先 | Spring AI 自动和手动工具执行的区别是什么？本轮只要一句，不举例。历史用户「我已经注册了工具，接下来想了解执行流程。」助手「可以从应用何时接管执行循环讲起。」 | [自动执行与手动控制到底差在哪](../corpus/ai-application-lab/03-llm-api-function-calling-spring-ai/notes/05-spring-ai-model-client-and-tools.md)。手动路径先返回调用请求，由应用审核、执行并带更新历史再次请求。 | 观察是否覆盖关键区别并遵守一句、不举例。禁止把手动模式写成不发送工具定义或实际发起工具调用。 |
| AB06 错误记忆 | RabbitMQ 实验06中，配置 (4,2) 暂停处理时观察到多少条 Unacked？历史为空。 | [实验06实际验证记录开头](../corpus/backend-interview-lab/rabbitmq/labs/06-prefetch-throughput/evidence/results.md)。对应观测值为 8，(1,1) 对应 1。 | 观察是否按实验记录回答并引用正确证据。禁止使用假记忆的 4 或将单次配置观测推广为吞吐结论。 |

AB06 追加启用的 110，类型 OTHER，关键词 rabbitmq,prefetch，时间 2025-01-10T00:00:00Z，正文「我记得 RabbitMQ 实验06配置 (4,2) 暂停时 Unacked 是 4。」其余字段沿用固定样本约定。

## 执行协议

1. 用户确认题目和判据后，逐题从标定章节截取支持事实的原文。冻结有序证据 JSON，包括正文、证据 ID、来源文件和行范围，保存 SHA-256。在同一个配对中向生产 AnswerGenerator 传入同一个 AssembledContext，复用生产回答解析和引用校验。这样计划测量冻结证据下的生成，不需要线上检索、改写、Embedding 或 Rerank。
2. A 调用现有无 Memory 入口，按历史走 v1/v2。B 先对原始问题调用实际 MemoryRecallService，再携带其输出调用生成入口；有可容纳的记忆时走 v3。若无记忆或预算裁剪至空，记录实际 v1/v2。固定模型、供应商、生成参数、输入预算、原始问题、历史和证据；先用本地替身捕获两组最终 Prompt，核对 evidence 子树及顺序一致，并确认历史完整容纳。实际运行记录有效历史，配对如有历史差异需标记为协议偏差。
3. 按案例记录证据摘要、召回 ID、实际注入 Memory Trace、裁剪数量、Prompt 版本、输入/输出/总 Token usage、生成耗时、失败阶段与状态。供应商未返回的字段标 N/A。保留配对失败，分别报告成功数与可评配对数。按技术事实、引用、指令遵循和个性化观察点逐项对照，先隐去组别评审输出，再解盲关联 Trace。
4. 计划 6 题 × 2 组 × 1 次，共 12 次 Chat 请求，Embedding、Rerank、工具执行均为 0。执行前需明确授权模型、预算与调用上限，准备脱敏的独立评估账号及凭据，冻结并核验输入，关闭自动重试并禁用工具。失败计入请求，不擅自补跑。本轮实际调用 0。实验比较由实际召回、裁剪和 Prompt 共同组成的 Memory 增强方案。
