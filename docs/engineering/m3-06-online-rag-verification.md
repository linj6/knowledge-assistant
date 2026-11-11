# M3-06 真实 RAG 端到端联调记录

## 结论

**READY FOR M3-07**：5 次授权问答全部 HTTP 200，4 次 ANSWERED、1 次正常拒答；开发者提供的 IDEA 运行日志摘录确认六阶段日志、模型、Prompt、检索与 Context 数量以及唯一 OVERALL 摘要。未观察到 M3-06 功能阻断。没有重试、Rerank 或追加请求。应用由开发者在 IDEA 启动，监听 8080。此结论仅表示真实 RAG 链路验收通过，不替代 M4 语义质量评估。

## 环境与知识库

分支 `feature/m3-rag`；起始 HEAD `e4fff78642f4aede4a5e77f5e59b3070532b47f7`。Java 21.0.10、Maven 3.9.16。M3-05 隔离回归记录为 Surefire 342 项、Failsafe 43 项全部通过。现有 PostgreSQL、Elasticsearch、MinIO 容器均 healthy。未启动第二个 Worker，未修改或重建基础设施。

使用已有知识库 `2120052899884367873`：2 个已发布文档，7 个当前版本且有向量的 Chunk。正式知识库 API 返回 200；正式 BM25 检索 API 返回 200、SUCCESS、4 个候选，实际策略 BM25。ES `ka-chunks-v1` 共 11 条文档，与两个现有知识库的 11 个当前版本 Chunk 数量一致。本次没有写入数据。

## 真实场景

| caseId | 策略 / 实际 | HTTP / 状态 | 结果与引用 | usage（输入/输出/总） |
|---|---|---|---|---|
| A | BM25 / BM25 | 200 / ANSWERED | “MinIO 保存原始文件。” E1→Chunk `2120055097183719427` | API 首次取样未记录 |
| B | BM25 / BM25 | 200 / ANSWERED | PostgreSQL 保存知识库、导入任务和向量；ES 使用中文分词。E1→`2120055097246633986`、E2→`2120055097183719427`、E3→`2120055097183719426` | 1883/46/1929 |
| C | BM25 / BM25 | 200 / INSUFFICIENT_EVIDENCE | 明确表示证据未提及 RabbitMQ ACK 超时时间；无引用 | 1880/27/1907 |
| D | BM25 / BM25 | 200 / ANSWERED | ES 使用中文分词。E1→`2120055097183719427` | 1874/28/1902 |
| E | HYBRID_RRF / HYBRID_RRF | 200 / ANSWERED | 双索引验证后发布版本。E1→`2120055097183719426` | 1872/27/1899 |

上述引用均属于同一知识库、已发布文档 `2120055079429230593`、当前版本 `2120055079517310977`。A/D 的 E1 来源位置 680–1480；B 的 E1/E2/E3 分别为 1360–2160、680–1480、0–800；E 的 E1 为 0–800。问题和完整模型原文未写入普通日志或提交记录；表中仅保留非敏感的结果摘要。

## 调用与诊断边界

应用 API 实际问答 5 次。根据执行路径、响应和开发者提供的阶段日志，推算供应商请求为 Chat 5 次、Embedding 1 次、Rerank 0 次；未取得供应商侧独立请求账单或网络追踪，不能将该推断当作供应商侧精确计数。没有虚构费用。

Prompt 合约版本由运行代码固定为 `rag-answer-v1`，开发者提供的运行日志摘录也确认相同版本与模型 `qwen-plus`；API 本身不返回 Prompt 版本、模型名或阶段耗时。A 的 usage 在首次取样时读取了错误字段，未记录，不能补造。M3-05 离线测试覆盖阶段日志，本次真实运行日志摘录补足在线诊断证据。

## 最终证据收口（只读）

此前只读查找未在项目目录或系统临时目录发现运行日志文件，且 Codex 的 Computer Use 本机 UI 管道不可用。以下新增日志证据来自**开发者提供并人工核对的 IDEA 控制台摘录**；Codex 未独立访问或采集 IDEA 控制台。五个 requestId 来自此前各次 HTTP 响应，用于对应场景。

| 场景 | requestId | 请求/实际策略 | candidateCount | selectedEvidenceCount | citationCount | OVERALL(ms) | status |
|---|---|---|---:|---:|---:|---:|---|
| A | `9db4e65e-537c-4be8-8836-c4521977eb18` | BM25 / BM25 | 4 | 3 | 1 | 3864 | ANSWERED |
| B | `d4be9511-dc2d-45fa-8e8d-468c701a51ab` | BM25 / BM25 | 7 | 3 | 3 | 1498 | ANSWERED |
| C | `daf730ea-a8da-4dfb-af74-59938207c4b2` | BM25 / BM25 | 4 | 3 | 0 | 1009 | INSUFFICIENT_EVIDENCE |
| D | `5b711f29-6e2f-426f-8e5a-a89916f26a80` | BM25 / BM25 | 7 | 3 | 1 | 1143 | ANSWERED |
| E | `3fd5af22-c5fc-4024-8cda-90aec9ef71bd` | HYBRID_RRF / HYBRID_RRF | 7 | 3 | 1 | 1691 | ANSWERED |

五次请求均有 RETRIEVAL、CONTEXT_BUILD、GENERATION、OUTPUT_PARSE、CITATION_VALIDATE、OVERALL 六阶段日志，各有唯一 OVERALL 摘要；模型均为 `qwen-plus`，Prompt 版本均为 `rag-answer-v1`，Generation error 均为 null，degraded 均为 false。E 的检索内部阶段为 EMBEDDING 517ms、VECTOR 78ms、BM25 63ms、FUSION 2ms，无 Rerank 阶段。阶段日志、候选数、Context 入选数、状态和总耗时的诊断证据缺口已由开发者摘录关闭。

证据分类：HTTP 状态、RagStatus、策略、引用和 B～E usage 为当次 API 直接证据；上述阶段字段为开发者提供的运行日志证据；Chat 5、Embedding 1、Rerank 0 为执行路径与日志支持的推断。仍未取得供应商侧独立精确计数，A usage 未记录，合法引用也不证明语义质量；这些边界不构成 M3-06 的功能阻断。

## 安全与验证

未读取、修改、删除、覆盖或输出 `application-local.yml`；未输出凭据，未修改现有业务数据。只修改工程记录，执行完整 diff 审阅及 `git diff --check`；本次未重跑 Maven 测试，沿用 M3-05 已记录的离线回归。没有发现需要代码修复的缺陷。
