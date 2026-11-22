# M4-00 Chunking 质量基线审计

## 范围与环境

2026-11-13，Windows 11 / Java 21.0.10 / Maven 3.9.16。分支 feature/m4-chunking-evaluation。仅离线读取本地两个来源仓库；未修改来源仓库。未连接数据库、ES、MinIO 或模型，未修改生产逻辑、依赖或冻结设计。Architecture 的 Boot 3.x 与 pom 的 4.1.2 存在已知差异，本次以源码为准，不修改该文档。

## 实现与实际数据流

DocumentParserFactory 按扩展名选择 MarkdownDocumentParser；CommonMark AST 决定标题作用域，正文仍保留完整原文（含 Markdown、换行），逐行 Fragment 保存码点范围与标题路径。ParsedDocument 验证连续覆盖及标题位置映射。FixedCodePointSplitter 不使用结构决定窗口边界：版本 1、FIXED_CODE_POINT，application.yml 当前为 800/120，步长 680，末窗覆盖全文即停止；HeadingPath 只取窗口起点所在 Fragment。

ChunkService 从 IndexVersion 的 chunkingConfig 快照切分，保存 content、sourceStart/End 与版本化 HeadingPath JSON；重试逐项核对已有记录。配置可变，800/120 是本次基线及当前默认，并非所有历史版本的已验证配置。

EmbeddingService 将 DocumentChunk::getContent 批量传入向量化，不拼接 HeadingPath。EsChunkDocument.from 只保存正文及身份/顺序字段，KeywordSearchRepository 对 content 做 match；HeadingPath 不作为独立 BM25 字段。原文窗口内出现的标题自然参与两类检索，但窗口外祖先标题不会自动补入。检索回表恢复正文和 HeadingPath；ContextBuilder 按排名去重、完整序列化并检查预算，将 evidenceId、headingPath、content 送入模型上下文。身份及来源位置留在 SelectedEvidence 服务端映射中。因此标题路径确实参与生成，并未独立参与向量化或 BM25。

## 可重复执行

入口：src/test/java/com/lnjecit/knowledge/assistant/ChunkingBaselineTests.java。清单每行一个文件路径，可替换为任意本地 Markdown 文件清单；相对路径以执行目录为基准。样本清单 m4-00-samples.txt 默认要求两个来源仓库位于同级目录。

```powershell
mvn '-Dtest=ChunkingBaselineTests' '-Dchunking.manifest=docs/engineering/m4-00-samples.txt' test
```

输出 target/chunking-baseline/summary.tsv 和 sample-N.txt（完整 Chunk、码点范围、HeadingPath、跨标题候选）。范围统一为零基、左闭右开，Chunk 序号零基。完整正文仅在 target 下生成，不纳入提交。入口断言原文一致、窗口位置、120 码点重叠和全文覆盖；跨标题自动检测仅为 Potential。人工确认案例见下文，不把候选当成语义缺陷。

## 样本与实测统计

六篇取自本地真实仓库原始字节；SHA-256 可验证本地工作副本，不宣称与远端 HEAD 相同。覆盖知识说明、多级标题、SQL、表格、列表、步骤和实验条件/结论，含长短章节。本轮没有 Java 或 YAML 围栏代码，Java 方法完整性未验证；不外推到该类型。

短 Chunk 约定为 <200 码点，仅为本轮阈值。下表中的长度为码点，代码块数包含 Mermaid 与 text。

|样本|仓库/相对路径|SHA-256|全文长度|标题|代码块|Chunk|最短|最长|平均|跨标题候选|
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
|S1|backend-interview-lab/spring/notes/04-transaction-boundaries-and-propagation.md|5441d647f5eee2e17870e9be1ff5bca8add8322ed7a2ba7dbe88219caafe85f9|4462|11|1|7|382|800|740.29|7|
|S2|backend-interview-lab/mysql/notes/01-index-and-sql.md|4fc99c7f82c01fa0c24503815e0353771ad736ef3e4979b2fbe530e8fd2407eb|6676|7|8|10|556|800|775.60|7|
|S3|backend-interview-lab/rabbitmq/labs/06-prefetch-throughput/evidence/results.md|3024ef3ff721d3b8083cbca62299d57067ca08b001200c574b85a59a54dfbe94|1305|3|0|2|625|800|712.50|2|
|S4|ai-application-lab/03-llm-api-function-calling-spring-ai/notes/05-spring-ai-model-client-and-tools.md|e6c8a44e338836c0dd30f544890d3528cbe7ebda795c3ac3ce9d3b298d2f1010|3335|8|2|5|615|800|763.00|4|
|S5|ai-application-lab/04-embedding-vector-search-rag/notes/04-chunking-and-context.md|92dbbea63d4a8ecc88b7dab563198ab02cc76b912a1e7a2f56ea582b235d3bf7|2961|6|0|5|241|800|688.20|4|
|S6|ai-application-lab/04-embedding-vector-search-rag/labs/04-hybrid-retrieval/README.md|6b8efd01cc0056f786efffd604d547a1a35f5bea27d0a24bf6b8b5ea514869f8|9577|9|4|14|737|800|795.50|9|

总计 43 Chunk；全文 28316 码点，含重叠输出 32756 码点，平均 761.77。分布：<200 为 0，200–399 为 2，400–599 为 1，600–799 为 3，800 为 37；短块比例 0%。跨标题 35/43，不能据此认定 35 个语义缺陷。覆盖与重叠断言全部通过，配置重叠不是重复缺陷。

## 可追溯问题与人工复核

以下为审计者直接对照原文与完整 Chunk 的复核，非外部用户验收。严重度表示单块独立消费的结构风险，不代表已测得检索或答案退化。

|编号|文档|Chunk/来源范围|类型与状态|相关原文|影响/严重度|人工确认|
|---|---|---|---|---|---|---|
|C01|S2|0 / [0,800)|Confirmed SQL 围栏截断|[770,1089) 的 `CREATE TABLE order_lab (` 代码块|末端仅保留建表开头；单块不能独立还原 SQL，中|是|
|C02|S1|1 / [680,1480)|Confirmed 代码块内部起始|`M->>D: 执行相应操作` 后接结束围栏；完整块 [237,737)|缺少开围栏与前部逻辑，Markdown 独立渲染不完整，中|是|
|C03|S1|2 / [1360,2160)|Confirmed 表格内部起始|`常返回 \| 代理按正常返回路径请求提交 \|`|首行残缺、缺失该表列标题，中|是|
|P01|S1|0 / [0,800)|Potential 句子截断|`若提交期间连接故`|后续限制条件在相邻块；可能影响只选此块的回答，中|边界已确认，语义影响未验证|
|P02|S1|1 / [680,1480)|Potential 标题作用域不一致|起点属于“一次事务调用到底由谁完成”，正文包含“三步判断事务是否按预期工作”|元数据不覆盖整个块；主题仍同属事务，不能直接认定误导，中|跨节已确认，下游影响未验证|

C01 的完整 SQL 同时被下一窗口包含；C02 完整 Mermaid 在前一窗口内。存在不完整 Chunk 不等于整篇证据永久丢失。Overlap 可以补救部分边界，但不能保证检索入选的是完整窗口。

## 少量证据完整性审查

- S1：默认 RuntimeException/Error 回滚、检查异常默认不触发及 rollbackFor 的说明，在 Chunk 1 内完整；该知识点单块可答。
- S1：提交期间连接故障不一定证明已回滚，Chunk 0 结束于“连接故”，Chunk 1 包含完整限制；单块 0 不足，单块 1 足够。未运行检索，无法判断实际会命中哪块。
- S1：六个传播组合的统一实验前提与结果表需检查联合窗口，不能只根据表格尾部作答。本轮不宣称所有六组均能单块回答。
- S3：预取参数 (1,1)/(4,2)、Unacked 1/8、活跃处理数 1/2 及最终事件数，在 Chunk 0 内同现；实验范围与“未执行性能对照”限制在后部，回答吞吐结论时仍须补充后部材料。

这些是人工证据检查，不是模型答案质量分数。定义与解释、前提与结论不能用标点数量自动判定。

## 结论与最小后续实验

Q1：800/120 在这六篇上有稳定、无丢失、可追溯的机械覆盖，长度受控，但不能据功能正确认定质量达标。真实 SQL、代码围栏与表格出现不完整窗口；重叠能补救部分问题。

Q2：目前最明确证据集中在 Markdown 结构完整性。标题归属局限也已确认；句子和证据完整性存在具体风险，但未验证真实召回与最终上下文，不做影响排名。跨章节本身不等于语义不一致。

Q3：已有足够证据支持一个小范围 Markdown 结构感知对照实验，尚不足以支持替换生产策略或复杂重构。先固定这批文件哈希、补充 Java/YAML 样本，比较现状与按标题/段落/代码块边界切分的简单候选；长结构必须明确回退规则。比较结构破损、证据区间的单块覆盖及块数/长度/重复成本，再决定是否开展检索实验。不能引用其他学习仓库自己的 chunking 实验结果作为本项目策略优胜证据。

## 验证与限制

执行 spotless:apply、spotless:check、mvn -DskipTests test-compile 及定向 ChunkingBaselineTests、DocumentParserTests、DocumentSplitterTests、ChunkingStrategyTests。未执行 clean verify、数据库/Docker/在线模型测试，原因是本次仅增加离线评估。未验证性能、召回率、生成正确率和所有文档类型。Commit SHA 见 Git 提交记录；保留原有未跟踪文件。
