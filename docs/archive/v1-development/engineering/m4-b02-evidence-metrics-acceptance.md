# M4-B02 Evidence Metrics 验收

范围：测试目录纯计算；未修改生产代码、Gold、Fixture、历史报告。基线 b208a421d3ba4ab2fd1b83624a309aec4aa2f1b1，分支 feature/m4-rag-evaluation；未提交。

## 计算与分母

沿用 B01 JsonNode 数据；SourceSpan 为零基 Unicode 码点左闭右开。相邻区间可连接、空洞不能跨越，重复区间无额外贡献。先核验文档 ID/版本、路径和 SHA，再合并同一版本的区间；同路径多个版本明确拒绝。ALL 的逐题分母为必要 Evidence 数；跨题 Recall 为宏平均。CCR null 表示仅在返回的 depth 内 NOT_REACHED。

SUCCESS 空结果保留执行状态 SUCCESS，质量状态 EMPTY_RESULT，计入零贡献。FAILED/MISSING 排除评分，分别计数。缺失 Gold 为 MISSING_GOLD；显式 answerable=false 为 UNANSWERABLE；PENDING_REVIEW、REJECTED_GOLD 均排除。answerable 是本轮测试入口的显式分类约定，未扩展冻结数据集或修改 B01 协议。空 Evidence 不推断为无答案。

Summary 的 eligibleCases 为 Gold 合格数（含执行失败），scoredCases 为其中执行成功数。successCount 包含空结果，emptyResultCount 为其子集。每个 Metric 保留宏平均贡献和 numerator、正式题数 denominator；无正式题时 value=null。

## 来源独立核验

Fixture Chunk 有 documentId/indexVersionId/sourceStart/sourceEnd，没有直接携带路径与内容 Hash。映射复用 RetrievalExperimentCorpus 的 m4-01-samples.txt 顺序及 RealRetrievalComparisonIT 的 doc=kb+100+d、version=kb+200+d 公式，固定/结构 kb 分别为 800000/900000。测试独立读取本地原文字节计算 SHA-256 并与 Gold 核对，不再从 Gold 复制 Hash 充当验证。该映射仅适用于此冻结实验；不声称 Fixture 自包含来源指纹。未重跑切分或检索。

## MRR 舍入核查

Fixed × HYBRID_RRF，Q01 至 Q12 的 First Rank：1,1,1,未达到,1,1,5,未达到,4,2,1,1。
贡献：1,1,1,0,1,1,1/5,0,1/4,1/2,1,1。合计 159/20=7.95，宏平均 53/80=0.6625。
原始算法无差异。HALF_UP 展示为 0.663；HALF_EVEN 展示为 0.662，与历史报告一致。现有材料不能证明历史脚本具体实现，仅能确认其展示值与 HALF_EVEN 一致。测试对原始 MRR 用精确分数的 double 值比较（1e-12 浮点误差），展示单独按 HALF_EVEN 三位小数核对，移除原宽容差，并冻结该组合逐题排名。

## 六组复算

|切分|策略|CEC@3|CEC@5|Recall@5|MRR@5|
|---|---|---|---|---|---|
|Fixed|VECTOR|0.583|0.750|0.750|0.468|
|Fixed|BM25|0.667|0.833|0.875|0.746|
|Fixed|HYBRID_RRF|0.667|0.833|0.833|0.662|
|Structure|VECTOR|0.833|0.833|0.833|0.736|
|Structure|BM25|0.917|0.917|0.958|0.896|
|Structure|HYBRID_RRF|0.917|0.917|0.917|0.875|

每组 totalCases=eligibleCases=scoredCases=successCount=12；emptyResultCount=failedCount=missingCount=pendingReviewCount=0。以下为各指标分子，分母均为 12；Recall 分子是逐题比例之和，不是微平均证据总数。

|组合|Recall@3|Recall@5|CEC@3|CEC@5|Hit@3|Hit@5|MRR@5|
|---|---|---|---|---|---|---|---|
|Fixed VECTOR|7|9|7|9|7|9|337/60|
|Fixed BM25|8.5|10.5|8|10|9|11|179/20|
|Fixed HYBRID_RRF|8|10|8|10|8|10|159/20|
|Structure VECTOR|10|10|10|10|10|10|53/6|
|Structure BM25|11|11.5|11|11|11|12|43/4|
|Structure HYBRID_RRF|11|11|11|11|11|11|21/2|

## 实际 CCR 示例

直接由测试计算输出（前缀 1 至 5 的完整 Evidence 数）：

- Q04 / BM25 / MARKDOWN_STRUCTURE_AWARE：0,0,0,1,1；必要证据 2；First Evidence Rank=4，MRR=1/4，CCR=NOT_REACHED，depth=5。
- Q08 / BM25 / FIXED_CODE_POINT：0,0,0,0,1；必要证据 1；First Evidence Rank=5，CCR=5。K=3 时未达到。

## 定向验证

EvidenceMetricsTests 四项测试覆盖：单块、多块连接、空洞、不同来源、重复区间、ALL、多证据 First Rank/CCR 差异、K 截断、空结果/失败、无答案/缺失/待审/拒绝 Gold、Hash 错误、未知映射、混合分母、空汇总，以及六组固定 Fixture 与真实 CCR 回归。加上 RetrievalMetricsFixtureTests 共五项。

Spotless apply/check、test-compile、上述五项定向测试和 diff 检查通过。未运行完整 Maven 回归、Docker、PG、ES、MinIO 或在线供应商。Precision/nDCG 未实现；其人工标签限制仍按 B01 保留。READY FOR M4-B03：是，仅表示 B02 纯计算验收完成，不授权进入后续任务。
