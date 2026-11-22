# M4-B03 Ranking Metrics 最终验收

基线：`feature/m4-rag-evaluation`，`bf2a1576f01e32bde0bd850dfa812a29fab5f4d2`；起始工作区干净。本次不提交、不进入 M4-B04。

## 冻结与复算

唯一标签来源为 `m4-b03-relevance-judgments.json`：12 问、24 个池、186 个唯一候选，全部 CONFIRMED；72 次 SUCCESS，360 个 Top-5 位置。原始 Fixture、Gold、人工标签和历史结果均不变。

`FrozenRankingEvaluation` 在测试目录严格核对确认状态、整数等级、reviewer/confirmationBasis/confirmedAt、池版本和快照、完整候选集合、文档版本映射、正文与原始来源文件 SHA-256；任何缺失、重复或池外候选均拒绝冻结。未确认返回异常状态 NOT_READY，身份/Hash/集合错误为 INVALID_POOL，不自动修复。冻结后在 Fixture 深拷贝中适配字符串 relevance 和 COMPLETE，复用 RankingMetrics；不覆盖原始文件。

冻结视图及全部逐题原始 double 分值归档为 [m4-b03-frozen-ranking-evaluation.json](../../../engineering/m4-b03-frozen-ranking-evaluation.json)。对象键递归排序、数组顺序保留、紧凑 UTF-8 JSON，无时间戳；SHA-256 输入为未添加自身 fingerprint 字段的对象。全局指纹绑定原 Fixture、评审材料、确认文件及各池内容。

全局冻结指纹：`4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c`。

复算命令：`mvn test "-Dtest=FrozenRankingEvaluationTests"`，同时生成 `target/m4-b03-ranking-evaluation.json`，与归档 JSON 全量比较。需保留 m4-01-samples.txt 指定的相邻来源仓库及一致原文字节；来源变化会拒绝重冻结。

## 六组正式指标

Precision 阈值为 2，单题固定 K 分母；nDCG 使用 2^rel−1、log2(rank+1)，IDCG 来自同题、同切分、同快照完整池，三策略共享。逐题 Macro Average；原始精度见 JSON，以下采用十进制 HALF_EVEN 保留三位小数，不对逐题中间值先舍入。

|切分|策略|P@3|P@5|Pooled nDCG@3|Pooled nDCG@5|有效题数|不可评分|
|---|---|---:|---:|---:|---:|---:|---:|
|Fixed|VECTOR|0.611|0.550|0.711|0.722|12|0|
|Fixed|BM25|0.667|0.617|0.774|0.818|12|0|
|Fixed|HYBRID_RRF|0.694|0.650|0.771|0.846|12|0|
|Structure|VECTOR|0.611|0.467|0.728|0.725|12|0|
|Structure|BM25|0.722|0.533|0.773|0.785|12|0|
|Structure|HYBRID_RRF|0.639|0.567|0.785|0.842|12|0|

每组 K=3/5 均为 12 个 SCORED；NOT_READY、INVALID_POOL、UNDEFINED、执行失败/缺失均为 0。所有池 IDCG>0。全零池 IDCG=0 时，既有算法保留 Precision/DCG/IDCG，nDCG=null，排除 nDCG 均值并单列 UNDEFINED，绝不补 0 或 1。精度与 nDCG 的分母分别统计，原始算法额外诊断状态保持不变。

以下为宏平均贡献之和（展示三位），每列正式分母均为 12；Precision 的位置相关数另列，完整分子保留在 JSON。

|切分/策略|ΣP@3|ΣP@5|ΣnDCG@3|ΣnDCG@5|相关位置@3 / 36|相关位置@5 / 60|
|---|---:|---:|---:|---:|---:|---:|
|Fixed VECTOR|7.333|6.600|8.535|8.668|22/36|33/60|
|Fixed BM25|8.000|7.400|9.292|9.816|24/36|37/60|
|Fixed HYBRID_RRF|8.333|7.800|9.248|10.151|25/36|39/60|
|Structure VECTOR|7.333|5.600|8.735|8.700|22/36|28/60|
|Structure BM25|8.667|6.400|9.277|9.415|26/36|32/60|
|Structure HYBRID_RRF|7.667|6.800|9.425|10.108|23/36|34/60|

## 等级与池身份

全部候选等级分布（0/1/2/3）：[51, 52, 12, 71]。每题每切分唯一计数，三策略不重复计入池分布。

|切分|策略|返回位置等级 0/1/2/3（共60）|
|---|---|---|
|Fixed|VECTOR|13/14/5/28|
|Fixed|BM25|14/9/5/32|
|Fixed|HYBRID_RRF|7/14/6/33|
|Structure|VECTOR|8/24/3/25|
|Structure|BM25|15/13/4/28|
|Structure|HYBRID_RRF|7/19/2/32|

池 ID 均以 `m4-02-top5-union-v1/` 为前缀，后缀为 questionId/chunking；以下 F=FIXED_CODE_POINT、M=MARKDOWN_STRUCTURE_AWARE，poolVersion 均为 1，状态均 COMPLETE。完整 snapshotHash、确认来源及指纹在归档中。

|问题/切分|候选数|等级 0/1/2/3|池 SHA-256|
|---|---:|---|---|
|Q01/F|5|0/1/0/4|`7c21471a64740717226242fa74c294ad52f33629fa79d39b5803a9462f632c58`|
|Q01/M|8|0/4/0/4|`166f235c8997a09ad77293fa6ef1628c8b2a85c6ac8190dda835cc354bd85f69`|
|Q02/F|6|0/2/1/3|`6ad9db799fa6c7a64681e95db1818eb575d383ebe84cfe3d47c00e8a5a41fd7e`|
|Q02/M|8|0/4/1/3|`b11ab2f541f5685b2a3546fbd4917b5f573be1fdc4dd44a813e237da11f63bdc`|
|Q03/F|6|0/1/1/4|`e753a11b6e7c7b54f144a3f5030ace49c45df4c420229fe40920fc5ae186c32f`|
|Q03/M|7|0/3/1/3|`b7e14fe56a509fc383e45f467225c1af2090f0b604beaa8725cd1ec824142165`|
|Q04/F|12|9/3/0/0|`e4b88e0e178bb87fd88e648f6acbac91751fd6fcbf28d4c6d507c798375df613`|
|Q04/M|10|7/2/0/1|`2027ac37b45cc985d1d8e4884f70a4317c3dc3b18b0b2ba43011d5c25c9923bf`|
|Q05/F|9|4/3/0/2|`092ca386c680ae47b7b3277148954c045e8aece4893674c56a1be82746041f39`|
|Q05/M|8|2/3/0/3|`f4212e151c9420576b1cff14b83971279976b089f10a3d3fca36bb9d03305a0c`|
|Q06/F|8|3/1/2/2|`69dd0cb5bf78de36a3936643dffc06ca3eb12f20ffd0bb93e31bfc464afbc97d`|
|Q06/M|8|3/1/2/2|`24384b99cd01b6a12aa7be82e8535d6410b845d627cb60f5157bd7dce819b89c`|
|Q07/F|6|1/2/1/2|`359e89bb7a98de91b4e4943e2cd501172f3edc5fcaa5b83580c49ab458806dc0`|
|Q07/M|7|2/3/1/1|`b54e73a3ff70684f03068515e320eb4ee1e5fd55de112cfb4bc1d9f420fae7c8`|
|Q08/F|8|1/4/0/3|`fe2ebb1afe75b651242777276179e79cbe85d7102979bcaa00878b2dbac3f3fc`|
|Q08/M|10|3/5/0/2|`58b0f3df49ad77164f97a156ed65ffa20519f9f005a67d3d881d8cf9296264d6`|
|Q09/F|6|0/0/0/6|`f59edd802fc36b0839774d845537e2b882e09145df2e57a9519dd2c072227b8d`|
|Q09/M|5|0/0/0/5|`51b3981303fbc59ccdecf6aa92634f5fb62ebe243602102955c8865313fcd775`|
|Q10/F|8|3/0/1/4|`44ef1d22f48150dbd46908fe1dff84248cd0fa646998589232f2dd3d4fb9fab3`|
|Q10/M|10|4/2/1/3|`56253c7b47ca3621f0c94bcfae28ab76a571aef0ed7cbdf45c3ce7679f2ab31f`|
|Q11/F|5|0/0/0/5|`e7b593ef10dbfbd5c869a67ce26228744810592b3a6389b7be28a56e5bc4ede5`|
|Q11/M|7|0/2/0/5|`191c36431279433fcab52aa229a6bd02cdeb5f5f5ad0acacdc6605611fef3436`|
|Q12/F|10|5/3/0/2|`bbdb158b678a2f5073978f8bf45476c9e22073bd8d47ad846509cbd62b219c6c`|
|Q12/M|9|4/3/0/2|`295a7fbf16251c0fc3aa261082276bcc38fea592dbcb1d2a6e6213911f3a99d8`|

## Q04、Q08、Q09、Q11 诊断

每行等级序列按实际 Top-5 排名；CEC 为 SourceSpan 完整覆盖（由 EvidenceMetrics 独立计算），不能当作语义覆盖。

|问题|切分/策略|Top-5 等级|P@3/@5|nDCG@3/@5|CEC@3/@5|
|---|---|---|---|---|---|
|Q04|Fixed VECTOR|0,0,0,0,0|0.000/0.000|0.000/0.000|0/0|
|Q04|Fixed BM25|1,1,0,0,0|0.000/0.000|0.765/0.765|0/0|
|Q04|Fixed HYBRID_RRF|0,0,0,1,1|0.000/0.000|0.000/0.384|0/0|
|Q08|Fixed VECTOR|1,1,1,0,1|0.000/0.000|0.143/0.160|0/0|
|Q08|Fixed BM25|1,3,0,3,3|0.333/0.600|0.363/0.708|0/1|
|Q08|Fixed HYBRID_RRF|1,0,3,1,1|0.333/0.200|0.302/0.338|0/0|
|Q09|Fixed VECTOR|3,3,3,3,3|1.000/1.000|1.000/1.000|0/1|
|Q09|Fixed BM25|3,3,3,3,3|1.000/1.000|1.000/1.000|0/0|
|Q09|Fixed HYBRID_RRF|3,3,3,3,3|1.000/1.000|1.000/1.000|0/1|
|Q11|Fixed VECTOR|3,3,3,3,3|1.000/1.000|1.000/1.000|1/1|
|Q11|Fixed BM25|3,3,3,3,3|1.000/1.000|1.000/1.000|1/1|
|Q11|Fixed HYBRID_RRF|3,3,3,3,3|1.000/1.000|1.000/1.000|1/1|
|Q04|Structure VECTOR|0,1,0,1,0|0.000/0.000|0.078/0.131|0/0|
|Q04|Structure BM25|0,1,0,3,0|0.000/0.200|0.078/0.448|0/0|
|Q04|Structure HYBRID_RRF|1,0,0,1,0|0.000/0.000|0.123/0.176|0/0|
|Q08|Structure VECTOR|1,1,1,1,0|0.000/0.000|0.179/0.201|0/0|
|Q08|Structure BM25|3,0,1,3,0|0.333/0.400|0.629/0.826|1/1|
|Q08|Structure HYBRID_RRF|3,1,0,1,1|0.333/0.200|0.640/0.663|1/1|
|Q09|Structure VECTOR|3,3,3,3,3|1.000/1.000|1.000/1.000|1/1|
|Q09|Structure BM25|3,3,3,3,3|1.000/1.000|1.000/1.000|1/1|
|Q09|Structure HYBRID_RRF|3,3,3,3,3|1.000/1.000|1.000/1.000|1/1|
|Q11|Structure VECTOR|3,3,3,1,3|1.000/0.800|1.000/0.875|1/1|
|Q11|Structure BM25|3,3,3,1,3|1.000/0.800|1.000/0.875|1/1|
|Q11|Structure HYBRID_RRF|3,3,3,3,3|1.000/1.000|1.000/1.000|1/1|

- Q04：两个池均缺两组参数 Unacked/活跃数的直接证据，CEC 始终为 0。Structure BM25 第4名 903002 为3级，仅支持未做性能对照；Top-5 P=0.200，仍不能完整回答。Fixed 池仅0/1级，但1级增益为1，BM25 nDCG=0.765且 P=0；相对池内排序好不等于有实质答案证据。
- Q08：VECTOR 两池 P=0，候选只有术语背景。Fixed BM25 第2名806008已有完整公式与规则，语义 COMPLETE，但宽 Gold 区间到第5名才被完整覆盖。Fixed RRF 第3名806009保留重复ID规则而公式截断，获3分仍不能补全整题，历史语义 PARTIAL；Structure BM25/RRF 将906010排第1，公式完整。池内其他3级候选未靠前，故即使关键块第1，nDCG也不必为1。
- Q09：两池候选均3级，三策略 P/nDCG 都为1，指标无法区分不同必要事实的互补性。Fixed Top-3仍缺“同步调用独立事务 Service”，VECTOR第4、RRF第5才补齐，BM25 Top-5仍语义PARTIAL；Structure三路907004第1完整支持配置及失败阻断。高相关并不保证整题完整。
- Q11：Fixed Top-5均3级，P/nDCG为1；Structure VECTOR/BM25第4名为1级，Top-5 nDCG=0.875，RRF全3级为1。候选支持提交、调度或等待的至少一个事实，但历史语义全部PARTIAL：未明确“已完成时无需等待”条件，Gold本身有边界限制。CEC覆盖原有区间不能补出该条件，本轮不改Gold或历史标签。

## 与 Evidence Metrics 的关系及局限

EvidenceMetrics 的 Recall/CEC/First Evidence MRR 检查已确认 Gold 区间并集，RankingMetrics 检查人工候选等级与位置；本轮不把两者相互转换。3级仅支持至少一个必要事实，Precision不计事实覆盖或重复证据的边际价值；nDCG允许1级主题相关增益。两个切分的候选池和理想排序不同，绝对 nDCG 不能用于公平比较全库切分质量。

12问、8篇Markdown属于已知开发样本；池仅为三策略Top-5并集，非全库穷尽标注。未见问题、其他格式和生产分布未验证；人工批量确认继承原建议边界，未测标注者一致性。离线指标不代表生成答案正确率或在线性能，不据此调整生产检索策略。

## 验证与结论

Spotless apply/check、test-compile；RetrievalMetricsFixtureTests、EvidenceMetricsTests、RankingMetricsTests、FrozenRankingEvaluationTests 定向验证。新增覆盖完整映射/24池、同池IDCG、未确认/池外/跨切分/Hash拒绝、宏平均排除空值、确定性复算及归档回归。已有 RankingMetricsTests 覆盖全零 UNDEFINED。最终定向测试共 11 项，0 失败、0 错误、0 跳过；Spotless apply/check、test-compile 与 git diff --check 通过。独立离线算式交叉核对 DCG、IDCG、Precision、nDCG 通过。

未运行全量 Maven 回归、基础设施或供应商请求，未重跑检索。原始数据与生产服务不变。

**READY FOR M4-B04：是**，仅表示 M4-B03 离线评估与验收就绪，本次不进入 M4-B04。
