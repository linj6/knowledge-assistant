# M4 评估

## 数据集、Gold 与人工确认

检索实验使用同一来源快照的 8 篇文档和 12 道问题，比较固定代码点切分与 Markdown 结构感知切分。必要证据 Gold 以来源文档和原文区间表示，便于在切分改变时比较覆盖。冻结 fixture 保存两种切分 × 三种策略 × 12 题的 72 条检索记录。每题每种切分取三路 Top-5 并集形成候选池，共 24 池、186 条确认标签。候选相关性按 0–3 分级，标注先由 Codex 提议，再经用户重点复核、修订及整体批准。另有逐题的完整、部分、缺失语义覆盖判定。[证据 Gold](engineering/m4-01-gold-evidence.json) · [相关性标签](engineering/m4-b03-relevance-judgments.json) · [冻结候选与排名结果](engineering/m4-b03-frozen-ranking-evaluation.json)

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

OFFLINE 复算读取冻结候选、Gold 和标签，模型调用为零。需提供冻结路径对应的相邻 `backend-interview-lab` 原文，且原文字节须通过 SHA-256 校验。归档 JSON 可直接审阅，完整复算依赖外部原文。

```powershell
mvn test "-Dtest=RetrievalMetricsFixtureTests,EvidenceMetricsTests,RankingMetricsTests,FrozenRankingEvaluationTests"
```

冻结测试将结果与归档比较。生成 manifest、检索明细、指标和报告的入口见 [OFFLINE Runner](archive/v1-development/engineering/m4-c01-offline-runner.md)。

历史 LIVE 联调在另一个真实知识库运行，BM25、VECTOR、HYBRID_RRF 各成功返回 12 题。向量与融合共享 12 次 Query Embedding；未调用 Chat 或 Rerank。该知识库发布快照与冻结 Gold 不匹配，36 条均无可用质量评分，评分分母为 0。历史 LIVE 产物在本机 `target/` 中，未随 Git 提交。重新运行需要可用的已发布索引、适配当前快照的 Gold 和标签，并在调用真实模型前取得授权。当前评估 HTTP 路由被禁用，命令行入口详见历史运行说明。[LIVE Runner](archive/v1-development/engineering/m4-c02-live-runner.md) · [历史联调验收](archive/v1-development/engineering/m4-c03-evaluation-runner-acceptance.md)
