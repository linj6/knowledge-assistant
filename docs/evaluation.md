# 评估

## 数据集、Gold 与人工确认

检索实验使用同一来源快照的 8 篇文档和 12 道问题，比较固定代码点切分与 Markdown 结构感知切分。必要证据 Gold 以来源文档和原文区间表示，便于在切分改变时比较覆盖。冻结 fixture 保存两种切分 × 三种策略 × 12 题的 72 条检索记录。每题每种切分取三路 Top-5 并集形成候选池，共 24 池、186 条确认标签。候选相关性按 0–3 分级，标注先由 Codex 提议，再经用户重点复核、修订及整体批准。另有逐题的完整、部分、缺失语义覆盖判定。[证据 Gold](evaluation/m4-01-gold-evidence.json) · [相关性标签](evaluation/m4-b03-relevance-judgments.json) · [冻结候选与排名结果](evaluation/m4-b03-frozen-ranking-evaluation.json)

回答 Gold v1 含 12 道可答题和 4 道不可答题，共 45 条必要事实和 1 条可选事实。事实、限定条件与拒答边界经用户整体批准冻结。实际回答另按适切性、事实正确性、完整性、引用有效性和引用语义支持五维评审，再判定整例。适用维度的质量分母只计 PASS 与 FAIL。目前仓库有协议、评分和工作台验证记录，未发布正式回答通过率。[回答 Gold](../src/main/resources/evaluation/answer-evaluation-v1.json) · [回答评审协议](archive/v1-development/engineering/m4-d01-answer-evaluation-protocol.md) · [评分口径](archive/v1-development/engineering/m4-d02-answer-metrics.md)

## 冻结检索结果

Evidence Recall 是逐题必要证据覆盖比例的宏平均，CEC 统计全部必要证据都被覆盖的题目比例。Hit 统计命中任一证据，MRR 衡量首次命中排名，CCR 记录首次完整覆盖所需的排名。Precision 以相关性 ≥ 2 为命中，分母固定为 K。Pooled nDCG 使用同一候选池内的理想排序归一化。语义完整率来自人工判定。

| 切分 | 策略 | Evidence Recall@5 | CEC@5 | MRR@5 | Precision@5 | Pooled nDCG@5 | 语义完整率@5 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 固定 | VECTOR | 0.750 | 0.750 | 0.468 | 0.550 | 0.722 | 0.750 |
| 固定 | BM25 | 0.875 | 0.833 | 0.746 | 0.617 | 0.818 | 0.750 |
| 固定 | HYBRID_RRF | 0.833 | 0.833 | 0.662 | 0.650 | 0.846 | 0.750 |
| 结构 | VECTOR | 0.833 | 0.833 | 0.736 | 0.467 | 0.725 | 0.750 |
| 结构 | BM25 | 0.958 | 0.917 | 0.896 | 0.533 | 0.785 | 0.833 |
| 结构 | HYBRID_RRF | 0.917 | 0.917 | 0.875 | 0.567 | 0.842 | 0.833 |

结构切分在这批题目中提高了三种策略的 Evidence Recall@5，但三种策略的 Precision@5 均下降。区间覆盖和人工语义覆盖也可能分离，例如 Q11 的 Gold 区间覆盖完整，人工仍判为部分覆盖。样本量、候选池深度与语料限制了外推范围。冻结实验未覆盖 Rerank、回答质量、延迟或吞吐量。[B04 冻结汇总与逐题分析](archive/v1-development/engineering/m4-b04-retrieval-evaluation-summary.md)

## 复现与验证边界

OFFLINE 复算读取冻结候选、Gold 和标签，模型调用为零。从仓库根目录运行。两个来源仓库的八篇原文已按原始字节放入 [评估语料目录](../evaluation/corpus/README.md)，读取时映射冻结来源路径并校验 SHA-256，复算无需相邻仓库。

```powershell
mvn test "-Dtest=RetrievalMetricsFixtureTests,EvidenceMetricsTests,RankingMetricsTests,FrozenRankingEvaluationTests"
```

冻结测试将结果与归档比较。使用以下入口生成 manifest、检索明细、指标和报告，输出目录为 `target/evaluation/m4-c01-offline-v1`。

```powershell
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.lnjecit.knowledge.assistant.evaluation.runner.OfflineEvaluationRunner" "-Dexec.classpathScope=test"
```

输出目录已存在时，可显式追加 `"-Dexec.args=--overwrite"` 重算。

历史 LIVE 联调在另一个真实知识库运行，BM25、VECTOR、HYBRID_RRF 各成功返回 12 题。向量与融合共享 12 次 Query Embedding；未调用 Chat 或 Rerank。该知识库发布快照与冻结 Gold 不匹配，36 条均无可用质量评分，评分分母为 0。历史 LIVE 产物在本机 `target/` 中，未随 Git 提交。重新运行需要可用的已发布索引、适配当前快照的 Gold 和标签，并在调用真实模型前取得授权。当前评估 HTTP 路由被禁用，命令行入口详见历史运行说明。[LIVE Runner](archive/v1-development/engineering/m4-c02-live-runner.md) · [历史联调验收](archive/v1-development/engineering/m4-c03-evaluation-runner-acceptance.md)

## P2 长期记忆

MEM-05A 在六道固定样本上得到 Macro Precision 54.17% 和 Macro Recall 90.00%。全局规则和子串匹配会引入无关记忆，关键词未覆盖同义表达时也会漏召回。[离线召回样本与报告](../evaluation/mem-05a/README.md)

MEM-05B 使用冻结证据完成 6 个配对、12 次 Chat。AI 辅助盲评解盲后，无记忆组略优 2 组，基本相当 4 组，有记忆组略优 0 组。相对无记忆组，有记忆组输入 Token 增加 22.60%，总 Token 增加 20.09%。这次小样本未观察到明显回答质量收益，每组每题仅运行一次，无法由此判断统计显著性或真实检索链路表现。[在线执行与盲评结论](../evaluation/mem-05b/online-report.md)

### MEM-05C 工程验收

| 测试套件 | 总数 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Java Surefire | 497 | 494 | 0 | 0 | 3 |
| Java Failsafe | 51 | 51 | 0 | 0 | 0 |
| JavaScript | 215 | 215 | 0 | 0 | 0 |
| 合计 | 763 | 760 | 0 | 0 | 3 |

Surefire 的两项分块评估需要本地样本清单，一项历史 LIVE 复算需要本地历史归档，三个条件均未满足。真实供应商测试入口不在默认套件中。默认 `mvn clean verify` 使用 Docker Desktop 验证 Testcontainers 启动路径，以及 Flyway V9、V10 新建与升级。Spotless、前端格式和 Git diff 检查均通过。真实后端的记忆管理、刷新恢复和跨账号隔离也通过浏览器验收。

### 真实在线问答 E2E

2026-11-26，用户通过 IntelliJ IDEA 启动项目，使用 qwen-plus，以同一账号、同一知识库和同一道 MySQL 二级索引问题，在两个独立新会话中分别关闭和开启 Memory。两轮均通过结构化输出解析和引用校验。

| 指标 | Memory OFF | Memory ON |
| --- | ---: | ---: |
| memoryEnabled | false | true |
| enabledCount | 0 | 3 |
| candidateCount | 0 | 3 |
| selectedCount | 0 | 3 |
| injectedCount | 0 | 3 |
| budgetTrimmedCount | 0 | 0 |
| Memory 估算 Token | 0 | 320 |
| Memory Recall 耗时 ms | 2 | 9 |
| Prompt | rag-answer-v1 | rag-answer-v3 |
| 检索策略 | HYBRID_RRF | HYBRID_RRF |
| 检索降级 | false | false |
| Query Rewrite | SKIPPED | SKIPPED |
| Retrieval 耗时 ms | 7614 | 301 |
| Generation 耗时 ms | 4555 | 7020 |
| RAG Overall 耗时 ms | 12208 | 7326 |
| 选中证据数 | 3 | 3 |
| 引用数量 | 2 | 2 |
| 最终状态 | ANSWERED | ANSWERED |

数据库人工检查确认两条助手消息分别落在两个会话，状态均为 ANSWERED。关闭组的 `memory_trace` 为 `injectedCount=0`、`memories=[]`。开启组包含 LEARNING_GOAL、ANSWER_PREFERENCE、BACKGROUND 各一条，记忆 ID 为字符串，Trace 无记忆正文，注入条数与生成日志一致。

用户在浏览器确认实时 Trace 展开后的类型和数量正确，刷新后历史 Trace 恢复，切换两个会话时 Trace 对应各自回答，引用可打开对应的知识证据。本表耗时均为单次观测，仅用于工程验收。表中记忆 Token 是本地估算，MEM-05B 单独记录供应商 Token 和质量盲评。

### 已知局限

- 问答正文的 Markdown 渲染体验仍可改进。
- 部分日志重复输出 `requestId`。
- 关键词召回会误命中，并可能漏掉语义相关的同义表达。
- 小样本 A/B 未观察到明显回答质量收益。
