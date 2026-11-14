# M4-B01 指标协议与固定 Fixture

协议：`retrieval-metrics-v1`。仅测试资源和离线一致性测试；不引入 Evaluation 生产模块。

## 数据与来源

`src/test/resources/retrieval-metrics-v1.json` 固定 12 问 × 2 切分 × 3 策略，72 次 SUCCESS、360 个返回位置、186 个 query/chunking/chunk 唯一评审项。来自本地真实 `target/m4-02d/results.jsonl`，原文件 SHA-256 与基线提交写入 source。只保留实际 Top-5、原始来源区间、正文、HeadingPath、版本身份、排名及各类分数，不重跑检索。阶段候选与耗时不属于本 Fixture。

Gold 原样嵌入 m4-02c1-gold-protocol.json（测试检查相等）；问题、答案要点和原文仍以 m4-01-gold-evidence.json 为准，人工证据确认仍以 m4-01-evidence-review.json 为准，不修改这些文件。semantic 的 3/5 标签逐格摘自收尾报告首张人工确认表；不能转换成 Chunk 相关性。

复用现有 Jackson JsonNode 解析，沿用实验 chunks 字段，避免新增平行 DTO。所有字段在本版本均必需，未知分数保留 null。run 身份为 questionId/chunking/strategy；Chunk 身份包含 chunking、documentId、indexVersionId、chunkId，不能仅凭数字跨实验拼接。sourceSpan 为零基 Unicode 码点左闭右开；匹配 Gold 时须先校验来源路径、内容 SHA 和文档映射，禁止仅比较数值区间。当前文档 ID 对应实验 Corpus 的文档顺序，后续实现必须显式核对该映射。

## 指标口径（本轮不实现计算）

仅 CONFIRMED 且非空的 ALL Gold 适用当前协议。每条 Evidence 在同一来源版本上被 Top-K 区间并集完整覆盖才算命中；相交不算，多个 Chunk 可联合覆盖。Recall@3/5 的问题内分母为 Evidence 数，跨问题取宏平均；CEC 为全部覆盖的 0/1；Evidence Hit 为至少一条完整覆盖的 0/1。First Evidence MRR@5 为首次完整覆盖任意 Evidence 的前缀排名倒数，未命中为 0。Complete Coverage Rank 为首次覆盖全部 Evidence 的前缀排名，未在已保存深度内完成返回 null，并同时返回观测深度 5，不能声称全排名无覆盖。

Precision@3/5 使用独立人工等级 0/1/2/3，2/3 为相关，分母固定 K；不足 K 的空位置按不相关。Pooled nDCG@3/5 使用 2^rel-1 增益、log2(rank+1) 折扣；IDCG 来自同一问题同一切分冻结评审池全部已评块的理想排序。池有 UNJUDGED 时该问题 ranking 指标为 NOT_READY，不能当 0；全 0 导致 IDCG=0 时为 UNDEFINED，单列数量并排除均值。等级定义如下，必须由人工结合答案要点确认：

- 3 DIRECT：Chunk 本身能够直接、准确、充分地支持至少一个明确的必要答案事实，且保留该事实所需的条件。
- 2 SUPPORTING：对必要答案事实提供实质性支持，但仍缺少关键条件、完整推导或明确结论。
- 1 TOPICAL：主题相关，但不能提供有实质作用的必要答案证据。
- 0 IRRELEVANT：与问题无关、明显误导，或无法提供有效信息。

单个 Chunk 获得 3 分不表示整道题的全部必要证据已覆盖；不得要求 3 分块支持整道题全部必要事实。建议与人工确认分离，不依据 Gold 区间重叠、检索分数或排名确定等级。

池 ID 为 m4-02-top5-union-v1，身份及范围已固定，为每问题每切分三策略 Top-5 并集；不得静默扩池。judgments 使用 UNJUDGED、reviewer=null，pool.status=PENDING。后续人工确认时填写 reviewer，等级改为字符串 0/1/2/3；全部评完才改 COMPLETE。改池需新 ID。当前无四级人工标签，Precision/nDCG 不可计算。

Semantic Complete Coverage@3/5 仅 COMPLETE 计 1，PARTIAL/MISSING 计 0；PENDING 单独计数并排除人工指标分母，不由区间代理推导。Q11 全 PARTIAL 及既有 Gold 限制保留。

执行状态允许 SUCCESS、FAILED、MISSING；失败须记录 error，缺失须记录原因，不制造空成功。FAILED/MISSING 排除质量均值并公布计划数、成功数、失败数、缺失数；SUCCESS 空结果计零命中。比较仅用同一批成功且具备对应标签的问题交集，公开实际分母。当前各组合均为 12。不同切分共享问题、Gold 和来源指纹，可比较 Evidence/语义；Chunk 标签与池不得跨切分复用。Pooled nDCG 仅为各池内排序诊断，不能把两个不同切分池的绝对值作为公平的全库排序比较。

## 验收与补齐

本地分支 feature/m4-rag-evaluation，HEAD 与 main 均为 4f37beabadba537c00956f8a3a265513c39083bc，初始工作区干净。已知架构文档 Boot 3.x 与 POM 4.1.2 差异保持不动。

没有缺失的 Top-5 运行结果；四级标签全部缺失。后续人工按冻结池标注并保留评审者身份，再由 B02 实现算法；不得根据分数或 C/P/M 自动生成等级。离线测试检查 Gold 不变、计数、执行状态、唯一身份、连续排名、区间与池引用。READY FOR M4-B02：是，算法可基于本协议开发；真实 Precision/nDCG 验收仍受人工标注阻塞。

实际验证：spotless:apply、spotless:check、test-compile 与 RetrievalMetricsFixtureTests 定向测试（1 项，0 失败/错误）完成；git diff --check 通过。未运行全量回归、基础设施或在线模型。未创建提交。
