# M4-C03 Evaluation Runner 阶段验收

## 结论与基线

**C01 OFFLINE、C02 LIVE 阶段验收通过；无本阶段阻断问题。READY FOR M4-D：是。** 仅表示 Runner 执行、归档和评分适用性边界已验收，不表示当前 LIVE 知识库质量通过，也不授权实施 Answer Evaluation。

分支 `feature/m4-rag-evaluation`，HEAD / C02 Commit `7908cbc75c56bf93d7e275f0f6594924b94c71c3`。本轮仅新增本文，保留用户已有 `src/main/resources/application-local.yml` 修改，未读取或归档密钥。未 Commit、Push、Merge。

## 运行产物与来源

以下目录均在本轮只读核验，四文件 manifest.json、retrieval-results.jsonl、metrics.json、report.md 全部存在；每份 manifest 的三个非 manifest 产物 SHA-256 均按实际字节匹配，共 9/9。manifest 不自引用。target 产物是本机证据，不随 Git 归档，清理后不能仅凭本文恢复 LIVE 结果。

|模式 / 本机目录（仓库根目录相对路径）|实际结果|模型调用|运行状态|
|---|---|---|---|
|OFFLINE `target/evaluation/m4-c01-offline-v1/`|72 条历史检索，24 池，186 确认标签|0|SUCCESS|
|LIVE `target/evaluation/m4-c02-live-bm25-v1/`|BM25：SUCCESS 12 / EMPTY_RESULT 0 / FAILED 0；52 个返回位置|Embedding 0|COMPLETED|
|LIVE `target/evaluation/m4-c02-live-vector-rrf-v1/`|VECTOR 与 HYBRID_RRF 各 SUCCESS 12 / EMPTY_RESULT 0 / FAILED 0，各 60 个返回位置|共享 Query Embedding 12，成功 12、失败 0|COMPLETED|

依据：[C01 说明](m4-c01-offline-runner.md)、[C02 说明](m4-c02-live-runner.md)、[B02](m4-b02-evidence-metrics-acceptance.md)、[B03 原始归档](../../../evaluation/m4-b03-frozen-ranking-evaluation.json)、[B04](m4-b04-retrieval-evaluation-summary.md)，以及 Runner / 定向测试。C02 说明中的“真实联调未执行”描述代码交付时状态；后续已获授权的 BM25 和向量/混合联调由本验收补充，不改写历史说明。

既有本机日志：`target/m4-c02-live-bm25-validation.log`、`target/m4-c02-live-vector-rrf-validation.log`，均 BUILD SUCCESS，Maven 总耗时分别 19.874 / 47.244 秒。两次真实运行均使用 local Profile 和非 Web Spring 上下文；未执行 Chat/Rerank。Embedding 次数依据单次服务调用路径、零重试配置和逐题共享 Diagnostics 核对，未独立取得供应商计费账单或 HTTP 审计；两策略各有一份 EMBEDDING 阶段记录，合计 24 条记录不等于 24 次请求。

## 一致性与可复现性

- C01 原始 72 条 JSONL 与 Fixture 完全一致；24 池共 186 标签均 CONFIRMED。六组 Recall/CEC/Hit @3/@5、MRR@5 的精确分子及 12 题分母与 B02 一致；移除新增 Evidence 汇总后，整个 metrics.json 与 B03 归档相等。输入文件 SHA 亦与 manifest 匹配。
- 全局冻结指纹保持 `4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c`。沿用原指标引擎，无第二套指标公式；JSON 不提前舍入，报告 HALF_EVEN 三位展示。
- C01 重复生成四文件逐字节一致，已由历史 `OfflineEvaluationRunnerTests.deterministicArtifactsAndFrozenRanking` 验证；既有最终日志 `target/m4-c02-final-validation.log` 显示 C01 3 项、C02 4 项共 7 项通过。本轮审阅断言及日志，不重跑 Maven；测试临时目录未作为永久副本保留。
- LIVE 真实 KB `2120893400476848130`，发布文档/版本 `2120893451093708802 / 2120893451093708803`；联调时核对 PG/pgvector 发布事实、16 个 1024 维向量及 ES IK/BM25。SnapshotHash 为 `e9a99b6a4c71eca3995dd83675ed75c5a8aca54b8fa444f0fbd9a57290452fad`；本轮按该发布身份重新计算并与全部 36 条记录匹配。Snapshot 仅绑定版本身份，不能替代内容指纹。
- 联调时已逐位置与 PG 核对 Chunk 正文/来源区间。本轮不连接数据库，进一步核对两批共 172 个返回位置的排名、文档版本和跨运行内容/区间一致性，共涉及 16 个唯一 Chunk。VECTOR 与 HYBRID_RRF 每题前两个阶段（Embedding/Vector）一致；RRF 分数按既有排名贡献核对通过。
- LIVE 共 36 条均为 Evidence `GOLD_SNAPSHOT_MISMATCH` / Ranking `INVALID_POOL`。当前停车业务知识库与 B03 冻结来源不同，拒绝沿用旧标签符合预期；逐题评分分母及汇总分母均为 0，宏平均 value 为 null，无虚假质量分数。不修改旧 Gold 或人工标签获取分数。

## 输出协议与失败覆盖

|层次|共同含义及模式差异|
|---|---|
|产物协议|均有四文件、schemaVersion / runnerVersion / dataset / mode / status / artifactSha256；指标保留逐题与汇总，报告只展示关键结果。两种模式有共同语义，但并非相同 JSON 布局。|
|Runner 状态|OFFLINE 成功为 SUCCESS，输入/冻结/计算/输出异常不发布完整成功产物；LIVE 为 COMPLETED / PARTIAL_FAILURE / FAILED，仅描述执行完成情况，不代表质量通过。|
|Retrieval 状态|C01 保留原 Fixture status；LIVE 原样保留 SUCCESS / EMPTY_RESULT / FAILED、请求/实际策略、参数、Snapshot、Chunk 和 Diagnostics。空结果仅在适用性核验后适配指标引擎的空 SUCCESS，原始状态不被改写。|
|Evidence 状态|C01 位于 runs.evidence3/evidence5 的 executionStatus / qualityStatus；LIVE 另有 evidenceStatus / evidenceDenominator，只有资格通过才提供计算值。|
|Ranking 状态|C01 位于 runs.at3/at5.status；LIVE 使用 rankingStatus / rankingDenominator 及 ranking3/5。未知候选 PENDING_REVIEW，身份或池不一致 INVALID_POOL，不能当作 relevance=0。|
|OFFLINE 特有|inputSha256、frozenPool、原始历史 runs；不启动 Spring。|
|LIVE 特有|configuration、frozenInputs、questionSource/SHA、sourceContentHashes、snapshots、retrievalStatuses、metricStatuses；Query、实际策略及阶段耗时。|

定向覆盖已审阅：C01 缺失输入、非法参数、输出失败及拒绝覆盖；FrozenRankingEvaluationTests 的未确认标签 NOT_READY、池外身份/跨切分/Hash 错误；C02 的 EMPTY_RESULT、FAILED、部分/全失败报告、授权门禁、Gold 来源/Query/Snapshot 不匹配、未知 Chunk 和正文变化。真实两批均无 EMPTY_RESULT/FAILED，本轮不人为制造故障，也不将 Mock 通过表述为真实故障验证。

## 验证边界与后续

本轮仅使用本地既有产物、代码、归档和日志做只读数据核对，审阅本文及执行 `git diff --check`、新增文件空白检查。未运行 Maven、在线 Embedding/Chat/Rerank、数据库查询、Docker、迁移、导入或重新索引；未修改生产代码、模型配置或历史评估数据。

已验证固定 v1 OFFLINE 的可复算性，以及当前已发布 KB 的三种 LIVE 检索链路和不兼容评分拒绝。未验证 LIVE 匹配 Gold/确认池的真实质量评分、其他数据集、全库召回、性能统计或答案质量；LIVE 耗时及模型响应本身不承诺逐字节可复现。C01 显式覆盖不提供旧产物事务恢复，来源原文字节仍依赖相邻仓库。以上为已知边界，不阻断本阶段验收；M4-D 需另行明确授权。
