# M4-B04 Retrieval Evaluation Report

## 范围与来源

基线 `feature/m4-rag-evaluation` / `2cbebb29b0dfbc73eb35e14d6e1329728038e967`，起始工作区干净。本轮仅整合冻结结果，不改变生产代码、Gold、候选或标签。

权威输入：[B02 验收](m4-b02-evidence-metrics-acceptance.md)、[B03 验收](m4-b03-ranking-evaluation-report.md)、[B03 原始精度归档](m4-b03-frozen-ranking-evaluation.json)、[人工语义记录](m4-02-retrieval-comparison-report.md#最终人工语义复核)及 [Fixture](../../src/test/resources/retrieval-metrics-v1.json)。已有 EvidenceMetrics / RankingMetrics 和冻结入口足够，无需新增计算代码或机器汇总副本。

Evidence Recall、CEC、Hit、MRR、CCR 沿用 SourceSpan 并集算法；Recall 是逐题必要 Evidence 覆盖比例的宏平均。Precision 沿用 relevance≥2、固定 K 分母；Pooled nDCG 沿用池内 IDCG。Semantic Complete Coverage 只从历史 C/P/M 人工标签计数，绝不从 SourceSpan 推导。所有小数 HALF_EVEN 三位，先汇总后舍入；原始 double 及逐题结果完整保留在归档，B02 精确分子保留于验收记录（包括 MRR 53/80=0.6625）。

## 六组指标

Fixed=FIXED_CODE_POINT；Structure=MARKDOWN_STRUCTURE_AWARE。每格依次 @3 / @5；MRR 仅 @5。

|切分 / 策略|Evidence Recall|CEC|Evidence Hit|First Evidence MRR@5|Precision|Pooled nDCG|Semantic Complete Coverage|
|---|---|---|---|---|---|---|---|
|Fixed VECTOR|0.583 / 0.750|0.583 / 0.750|0.583 / 0.750|0.468|0.611 / 0.550|0.711 / 0.722|0.667 / 0.750|
|Fixed BM25|0.708 / 0.875|0.667 / 0.833|0.750 / 0.917|0.746|0.667 / 0.617|0.774 / 0.818|0.750 / 0.750|
|Fixed HYBRID_RRF|0.667 / 0.833|0.667 / 0.833|0.667 / 0.833|0.662|0.694 / 0.650|0.771 / 0.846|0.667 / 0.750|
|Structure VECTOR|0.833 / 0.833|0.833 / 0.833|0.833 / 0.833|0.736|0.611 / 0.467|0.728 / 0.725|0.750 / 0.750|
|Structure BM25|0.917 / 0.958|0.917 / 0.917|0.917 / 1.000|0.896|0.722 / 0.533|0.773 / 0.785|0.833 / 0.833|
|Structure HYBRID_RRF|0.917 / 0.917|0.917 / 0.917|0.917 / 0.917|0.875|0.639 / 0.567|0.785 / 0.842|0.833 / 0.833|

## 分母、版本与身份

|组合|Question / 有效 Evidence / Precision / nDCG / Semantic|FAILED / MISSING / NOT_READY|
|---|---|---|
|FIXED_CODE_POINT VECTOR|12 / 12 / 12 / 12 / 12|0 / 0 / 0|
|FIXED_CODE_POINT BM25|12 / 12 / 12 / 12 / 12|0 / 0 / 0|
|FIXED_CODE_POINT HYBRID_RRF|12 / 12 / 12 / 12 / 12|0 / 0 / 0|
|MARKDOWN_STRUCTURE_AWARE VECTOR|12 / 12 / 12 / 12 / 12|0 / 0 / 0|
|MARKDOWN_STRUCTURE_AWARE BM25|12 / 12 / 12 / 12 / 12|0 / 0 / 0|
|MARKDOWN_STRUCTURE_AWARE HYBRID_RRF|12 / 12 / 12 / 12 / 12|0 / 0 / 0|

六组每个 K 均成立；72 次 SUCCESS、360 个 Top-5 位置，无空结果、待审 Gold、INVALID_POOL 或 UNDEFINED。Semantic 的 MISSING 是质量标签，不能当作执行 MISSING 排除；所有 PARTIAL/MISSING 都在 12 题分母内。

Gold 为 retrieval-metrics-v1 内嵌 schemaVersion=1，12 题均 CONFIRMED / ALL；路径、sourceSha256、querySha256 与 Evidence 区间逐题绑定。Fixture 来源 `target/m4-02d/results.jsonl`，SHA-256 `1a01936c26383fb6451f4a93bf5e1e3156d0f419ff20bf193d7f9f947b71571e`，来源 baseline `4f37beabadba537c00956f8a3a265513c39083bc`。原始 target 文件不是可移植复算的必要评分输入；来源原文字节核验仍要求相邻 backend-interview-lab 仓库。

Judgement Pool：24 个同题同切分池，186 个候选，poolVersion=1，全部 COMPLETE / CONFIRMED；ID 前缀 `m4-02-top5-union-v1/`，后接 questionId/chunking，三策略共享同池。各池 snapshotHash、文档版本、正文/原文 Hash、确认人及池指纹见归档 frozenPool（24 池清单亦见 B03），不重复复制。全局冻结指纹 `4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c`。

真实检索来自历史 M4-02 快照：8 篇 Markdown，Fixed/Structure 为 58/83 块，两套独立 KB；已有 text-embedding-v4 1024 维 FLOAT 快照，未重新请求模型。输入指纹 `d8080d19731b571a32985c3889c398138fb1c661bfef780bc8994c54398492d8`；配置指纹 `46126af9b308e9c300da290642ef34e8822575b3652a1028cdedf9fc5fe641f9`。逐 run snapshotHash 与池绑定，不能与全局输入指纹互换。

## CCR 逐题诊断

CCR 为首次完整覆盖全部 Gold 的前缀排名；NR=depth=5 内 NOT_REACHED，不表示全库不可达。CCR≤3 即 CEC@3 通过，≤5 即 CEC@5 通过。列顺序与上表一致。

|问题|Fixed VECTOR|Fixed BM25|Fixed HYBRID_RRF|Structure VECTOR|Structure BM25|Structure HYBRID_RRF|
|---|---|---|---|---|---|---|
|Q01|1|1|1|1|1|1|
|Q02|1|1|1|1|1|1|
|Q03|1|1|1|1|1|1|
|Q04|NR|NR|NR|NR|NR|NR|
|Q05|1|1|1|1|1|1|
|Q06|3|1|1|3|1|1|
|Q07|NR|4|5|1|2|1|
|Q08|NR|5|NR|NR|1|1|
|Q09|5|NR|5|1|1|1|
|Q10|5|1|2|2|1|2|
|Q11|2|1|1|1|1|1|
|Q12|3|1|1|1|1|1|

## 典型失败与解释边界

|案例|观察事实|可能原因|尚未证明|
|---|---|---|---|
|Q04|六组 CEC@3/@5 全失败；所有 P@3=0。Structure BM25 第4名为3级，P@5=0.200；Fixed BM25 P=0 而 nDCG=0.765|候选缺两组参数 Unacked/活跃数，1级背景仍获得 DCG 增益|不能由 Top-5 缺失断言全库没有证据，亦未证明更深召回或重排能补齐|
|Q08|VECTOR 两池 P@3/@5=0，语义 MISSING；Fixed BM25 第2名已有完整公式，Semantic@3 COMPLETE，CCR=5；Fixed RRF 第3名公式截断，Semantic PARTIAL、CEC 失败|宽 Gold 区间与必要事实支持范围不同；Structure BM25/RRF 第1名完整公式带来局部收益|未证明所有公式或跨块问题都受益，也不能把单候选3级等同整题完整|
|Q09|两池 P/nDCG 均为1；Fixed Top-3 语义 PARTIAL，VECTOR 第4、RRF 第5补齐；Fixed BM25 Top-5仍 PARTIAL；Structure 三路第1即完整|等级相关性不衡量必要事实互补，多个高相关候选仍可能重复支持同一事实|不能据此宣称排序算法没有价值，也未证明切分是唯一因果|
|Q11|六组 CEC 通过但 Semantic 全 PARTIAL；Structure VECTOR/BM25 第4名为1级，P@5=0.800、nDCG@5=0.875|Gold 未明确“已完成无需等待”条件，区间覆盖无法补出该条件|不能把全部 PARTIAL 归咎检索或模型；本轮不修订 Gold|
|Q07、Q10|人工语义原已 COMPLETE，但 Structure 改善部分 CEC|Fixed 候选已表达必要事实，却未覆盖宽 Gold 全区间|不能将代理指标增量计为语义收益|

其他题与全部 CCR 见上表；Q04、Q08 的低 Precision 和低排序分值须同时阅读 B03 的逐题等级序列，避免只看宏平均。Q04 Fixed BM25 的高相对 nDCG 是弱池内排序，不是充分回答能力。

## 阶段结论与后续建议

- 六组在本样本均有可评分结果。Fixed BM25 的 Evidence Recall/MRR 优于 Fixed VECTOR/RRF；Fixed RRF 的 P@5=0.650、nDCG@5=0.846 为 Fixed 三路最高，但语义完整率仍仅0.750。Structure BM25 的 Recall@5=0.958、MRR=0.896、P@3=0.722较突出；Structure RRF 的 nDCG@3/@5=0.785/0.842 较突出，语义完整率与 BM25 同为0.833。VECTOR 在 Q08 两池均失败，融合也没有保证逐题改善。
- Structure 的六项区间指标整体较好，语义收益主要集中 Q09 及 Q08 RRF；其 P@5 三路都低于 Fixed。两个切分的评审池及 IDCG 不同，不能据绝对 nDCG 排出全库切分优劣。尚无充分证据替换 FixedCodePointSplitter，生产 Chunking 保持不变。
- TopK=5 提升 Fixed VECTOR/RRF 的语义完整率（0.667→0.750），BM25 的区间覆盖提高却语义仍0.750；Structure 三组语义 @3/@5相同，新增位置主要未增加完整题数。所有组合 P@5 均低于 P@3。Top-5 增加上下文和候选成本，不能一律替换 Top-3；本轮不改变生产 TopK。
- 12问、8篇已知开发 Markdown 不是独立测试集；人工批量确认未测标注者一致性；Q11 有 Gold 边界限制。24池仅三策略 Top-5 并集，候选池截断使未进入池的证据不参与 IDCG，无法推断全库召回上限。历史单轮、固定顺序耗时不证明性能优劣。
- 后续 Retrieval Runner 宜复用现有身份核验、指标与状态协议，持久化来源/配置/快照指纹和逐题分母，分开输出执行失败、不可评分与质量失败；在独立未见样本上做配对比较，扩深候选池并审查新增标签，再评估切分、融合或重排。以上仅建议，本轮不实施。
- Answer Evaluation 应分别记录检索证据充分性、答案必要事实完整性、引用支持与无支持断言，保留 Q04/Q08/Q09/Q11 作为诊断样例；不得用检索 nDCG 或 CEC 代替答案正确率。后续若修订 Gold，另立版本并保留本次冻结结果。

## 复现与本轮验收

既有复算入口：`mvn test "-Dtest=RetrievalMetricsFixtureTests,EvidenceMetricsTests,RankingMetricsTests,FrozenRankingEvaluationTests"`；冻结测试生成 `target/m4-b03-ranking-evaluation.json` 并与归档全量比较。运行需相邻来源仓库的相同原文字节；本轮未运行 Maven，该命令是复现指引，不是本轮通过记录。

本轮以只读离线汇总核对72条归档、六组各12个唯一问题、全部 SCORED，读取原始 evidence3/evidence5 生成 CCR，复用 groups 的未舍入排名均值；以 B02 分子及 B03 表格交叉核对展示值，Semantic 按历史12题 C/P/M计数。审阅新增报告完整内容并执行 Git diff 空白检查。无新增计算实现、无必要新增定向测试；未运行全量回归、真实检索、Docker、在线模型或供应商请求。

**M4-B04 阶段验收：通过。READY FOR NEXT M4 MILESTONE：是。** 仅表示冻结检索评估汇总及边界明确，不表示生产切分调整获准或答案评估已通过；后续实施需另行授权。本轮仅新增本报告，无自动 Commit、Push、Merge；Commit SHA 无新增，HEAD 保持基线。
