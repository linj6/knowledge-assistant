# M4-02B 检索实验基础设施验证

## 范围与文件

仅新增测试层：EmbeddingSnapshot、EmbeddingSnapshotTests、RetrievalExperimentCorpus、RetrievalExperimentCorpusTests、RetrievalExperimentIT，以及本报告。生产源码、配置、依赖、迁移未修改。

EmbeddingSnapshot 提供 Manifest/Input/Entry 文件协议、严格离线加载和 QueryEmbeddingService 适配。Corpus 读取冻结清单、校验 Gold 中八篇文档 SHA，复用生产 Parser、FixedCodePointSplitter(800/120) 和 M4-01 MarkdownStructureExperiment。IT 使用真实 PG/ES Repository、RetrievalService 和 RRF，不替换检索结果。

## 环境与构建

PG：pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87。
ES：knowledge-assistant-elasticsearch:8.18.0-ik8.18.0。沿用 ElasticsearchIT 的容器与 IK 镜像。两者禁止缺失镜像自动拉取；不连接开发服务。

Spring 启动向独立 PG 应用现有五个 Flyway migration。KB 800000/900000，各自八篇 Document、独立版本及 Chunk ID。ES 使用 ka_chunk_eval_fixed / ka_chunk_eval_structure，生产 EsIndexRepository 创建和验证 Mapping、批量写入、Refresh，并逐项读取比较 ES 内容。

版本按测试层最小 VERIFIED 事实构造（不声称验证完整 M1），随后真实 IndexPublishService.publish 完成活动指针、版本发布和任务完成；真实 PublishedVersionSnapshotService.read 断言八个发布文档。两组默认参数均为 20/20/60/20/5。

## 结果

八篇原文 SHA 均匹配；固定切分 58、结构感知 83。确定性 SYNTHETIC 向量为 1024 维、有限非零，同输入相同；仅驻留测试 Fixture，不保存正式快照，不代表 text-embedding-v4。

容器测试真实执行，VECTOR、BM25、HYBRID_RRF 两组均 SUCCESS，返回 Chunk 归属本 KB；ES 两索引数量分别 58/83。缺失查询返回 FAILED，无在线回退。继承的 analyzerAndBm25 同时执行，因此该 IT 报告为两个测试。

## 文件协议

Manifest：schemaVersion/provider/modelId/dimensions/encoding/configFingerprint/sampleFingerprint/chunkingFingerprint。
Entry：Input 映射与 vector；Input 包含 CHUNK/QUERY、输入 UTF-8 SHA、questionId 或 document SHA/strategy/chunkOrder/SourceSpan。

加载要求与调用方预期 Manifest 完全相等，同时限定 schema=1、text-embedding-v4、1024、FLOAT；输入不 trim、不改换行、不拼标题。校验当前输入集合完整匹配、输入哈希、向量维度、有限性、非零及重复键一致性。模型、配置、样本或切分指纹变化均拒绝。离线查询适配只查询已加载向量，缺失即失败，不持有在线客户端。

后续资源约定：src/test/resources/m4-02/embedding/manifest.json 和 vectors.jsonl；本轮没有创建真实向量文件。协议代码、非敏感 manifest 和经许可的真实向量适合 Git；不重复保存完整原文，不保存密钥或私有 endpoint。target/m4-02b 下日志与 oversize.tsv 是运行产物，不提交。

未来生成入口边界为 Corpus 冻结输入 + Manifest/Input/Entry 协议；本轮不提供可在线执行的生成器。M4-02C 在取得显式模型、预算、输入范围授权后，另行实现独立生成入口、分批原子保存及失败恢复。默认离线测试不得启用模型或自动补齐。

## 800 Code Point 与 Gold

对本次实际重建的结构感知 Chunk 逐块检查：超过 800 Code Point 的数量为 0，对应文档/序号清单为空，输出 target/m4-02b/oversize.tsv。未截断任何内容；本批没有与 M1 输入长度限制冲突。未进行真实模型长度或语义能力验证。

Gold 有十二问、路径/SHA、码点左闭右开 Span、证据文本和预期答案点。下一阶段仍需稳定 evidenceId、必要证据 AND/可替代关系、答案点映射及人工复核状态。区间相交不能当作语义相关。本轮没有 EvidenceEvaluator 或正式质量指标。

## 验证命令

- mvn -q spotless:apply
- mvn -q -DskipTests test-compile
- mvn -q "-Dtest=EmbeddingSnapshotTests,RetrievalExperimentIT" test：3 tests，0 failure/error/skipped。
- mvn -q "-Dtest=EmbeddingSnapshotTests,RetrievalExperimentCorpusTests" test：2 tests，0 failure/error/skipped。
- mvn -q "-Dtest=RetrievalExperimentIT" test：最终 HeadingPath JSON 协议版本复测。
- mvn -q spotless:check
- git diff --check

快照协议一个测试方法覆盖正常、模型/维度/输入哈希错误、非有限值、零向量、缺失和重复冲突；不把内部断言计为独立测试数。未运行全量 clean verify，没有调用 Embedding/Chat/Rerank。

## 限制与下一阶段

当前集成查询只有“事务”，只证明真实管线可运行，不证明十二问检索质量。合成向量没有语义意义。外部兄弟仓库必须存在且 SHA 一致，否则明确失败。Fixture 每次容器生命周期内初始化一次，不设计通用调度。后续需要将真实快照输入和结果指纹冻结，并将正式逐问排名写入独立结果文件。

结论：READY WITH CONDITIONS。真实快照、在线授权、Gold 关系和指标冻结属于 M4-02C 前置条件。未自动提交、推送或合并。
