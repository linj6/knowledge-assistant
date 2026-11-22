# M4-B03-1 Ranking Metrics 与评审准备

基线：feature/m4-rag-evaluation，HEAD 8f9c52c58a6ce4b6452882d0fde551814d3de0ff，起始工作区干净。未提交。

RankingMetrics 位于测试目录，沿用 JsonNode。Precision 固定 K 分母，等级 2/3 相关；DCG 使用 2^rel-1 和 log2(rank+1)，IDCG 对同题同切分池全部等级降序计算。K 支持任意正整数。非正式评分返回空分数及明确状态；全零池返回 UNDEFINED，保留有定义的 Precision/DCG/IDCG，nDCG=null。

池以既有 m4-02-top5-union-v1 为基础，每题每切分派生 poolId，poolVersion=1；绑定数据集、来源结果 SHA、snapshotHash。候选身份包含 documentId/indexVersionId/chunkId，切分和问题由池隔离。材料额外保存正文 UTF-8 SHA-256、原始来源路径与文件 SHA、码点区间。不同策略的命中仅保留为 hits，共享一次人工标签。新增策略出现池外候选必须新池版本；禁止静默加入或标零。冻结必须全部标签为 0/1/2/3 且 reviewer 非空，再将既有协议 pool.status 置 COMPLETE；当前材料不是正式冻结版本。

运行 `python scripts/prepare-ranking-review.py` 生成 JSON；`--check` 检查精确可重现性。材料保留 Fixture 正文，不猜测或自动修订正文；本地来源路径和 SHA 供人工核查。proposedRelevance、reviewer、rationale 均待填写。正式人工评审与回填留给 B03-2，不修改冻结 Fixture、Gold 或历史实验。

计数：12 问、72 次检索、360 排名位置、24 池、186 候选；186 个 UNJUDGED。未进行人工标注。

验证：Spotless apply/check、test-compile；RankingMetricsTests、EvidenceMetricsTests、RetrievalMetricsFixtureTests 定向测试；生成器 --check；git diff --check。未运行全量回归、在线模型、真实检索或基础设施。

READY FOR M4-B03-2：是，人工评审材料已准备；真实 Precision/nDCG 仍不能正式评分。
