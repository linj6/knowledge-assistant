# M4-02C-1 准备进度（未完成）

## Git 与范围

分支 feature/m4-chunking-evaluation，HEAD 308399308626a6e3a0d2f7f38ebdce6936d3e5a5。
保留原有未跟踪文件；没有修改生产逻辑、依赖或配置，没有提交、推送或合并。
本次没有调用真实模型，也没有启动 Docker 或执行检索质量对照。

## 实际文件

- m4-02c1-gold-protocol.json：以 questionId 关联原始 Gold，增加稳定 evidenceId、sourceSha256、SourceSpan、ALL 关系及 PENDING 状态。
- GoldPreparationTests.java：验证十二问映射、ID 唯一、关系、状态、原文 SHA 和码点区间内容。
- 本报告。

原始十二问、证据和 expectedAnswerPoints 保留在 m4-01-gold-evidence.json，未改写。
Q01—Q12 全部 PENDING：人工仍需逐证据确认语义支持性、必要性及答案点对应关系。

## 指标协议

Complete Evidence Coverage@3/@5：Top-K Chunk 集合共同完整支持全部必要证据的问题数除以问题总数。
Evidence Recall@5：每问被 Top-5 完整支持的必要证据点数量除以该问必要证据点总数，再对问题宏平均。
MRR@5：首个独立支持至少一个必要证据点的 Chunk 倒数排名，未命中为零，再对问题宏平均。
当前全部采用 ALL，无替代组；如以后引入 ANY，须在执行前显式冻结组成员和分母口径。
原文区间覆盖、证据点覆盖、语义支持分别记录；区间相交不能算命中。跨 Chunk 区间并集完整也不自动证明语义支持。
PENDING 或 REJECTED 数据不得作为已人工确认的正式质量结论。

## M4-02B 复用结论

Corpus 已重建八篇原文并验证 SHA 与 58/83 数量。Snapshot 已提供 Manifest/Input/Entry、完整集合及向量验证；缺失输入拒绝加载。合成向量 IT 接入真实 RetrievalService，只证明管线能力。

## 未完成与阻断

独立 DRY_RUN/EXECUTE 入口、实际客户端配置校验、授权与预算、批次 Index 校验、原子保存与恢复、Fake EmbeddingModel 测试均尚未实现。
因此未执行 DRY_RUN，不能提供去重数量或批次规划，也没有生成正式向量快照。
没有在线成功证据。预算尚未实现，不允许执行真实在线生成。
M4-02C-2 前需补齐以上实现、通过定向测试、人工复核 Gold，另行获得明确在线授权及预算并生成验证通过的完整快照。

结论：BLOCKED（本次交付未完成，不能进入在线授权或 M4-02C-2）。

## 实际验证结果

- mvn -q spotless:apply：成功。
- mvn -q spotless:check：成功。
- mvn -q -DskipTests test-compile：成功。
- mvn -q '-Dtest=GoldPreparationTests,RetrievalExperimentCorpusTests,EmbeddingSnapshotTests' test：3 tests，0 failures/errors/skipped。
- git diff --check：成功（新增未跟踪文件不属于 Git diff 的检查范围）。
- 未运行全量 clean verify、PG/ES 集成测试或 Fake 生成器测试。

# 补充实施：Embedding 快照生成器（本节取代上文“未完成”状态）

## 范围与文件

起始分支 feature/m4-chunking-evaluation，HEAD 308399308626a6e3a0d2f7f38ebdce6936d3e5a5。
新增测试目录内 EmbeddingSnapshotGenerator.java、EmbeddingSnapshotGeneratorTests.java，更新本报告。
保留之前所有未提交、未跟踪文件；Gold 数据、关系、状态和生产代码均未修改。
没有真实 Embedding/Chat/Rerank 请求，没有启动数据库、索引或容器，没有 Commit/Push/Merge。

## 入口与运行模式

独立 Java Main：com.lnjecit.knowledge.assistant.EmbeddingSnapshotGenerator，使用 Maven test classpath，从项目根目录运行。
IDE 可直接运行该 Main；默认 DRY_RUN。系统属性 snapshot.target 指定目标目录，snapshot.batchSize 默认 10，snapshot.mode 默认 DRY_RUN。
Main 不属于 JUnit 测试、不随 Spring Boot 启动，不会在默认 test/verify 中调用在线客户端。
Fake 控制流程测试属于默认离线测试；实际样本 DRY_RUN 测试也不创建客户端。

DRY_RUN 复用 RetrievalExperimentCorpus、两种既有切分和 Snapshot 协议，验证原文 SHA、58/83 数量、Chunk 原文回取及 UTF-8 SHA；读取原始十二问，保留 Query 原文。冻结完整映射指纹，输入变化立即拒绝。
没有 trim、截断、标题拼接或新切分算法。

实际 DRY_RUN 输出摘要：

- logical=153（58+83+12）
- unique=153，existing=0，pending=153
- batchSize=10，requests=16（15 批各 10，最后一批 3）
- target=D:\develop\workspace\idea\individual\knowledge-assistant\target\m4-02c1\embedding
- inputFingerprint=a73688e965e06dbe888c1a42bbb9bdcbcf09e5b0afe4f60c071020fd53285f30
- configFingerprint=46126af9b308e9c300da290642ef34e8822575b3652a1028cdedf9fc5fe641f9

## 授权和预算边界

核心 run 的 EXECUTE 在创建客户端之前要求 Budget：显式 authorized=true、明确模型 text-embedding-v4、非空 API Key、最大新增文本数、最大请求数、最大 Token 数及每个输入哈希对应的已核验 Token 计数。待生成文本总数、计划请求数、Token 总数均不得超过授权预算；缺计数或无预算拒绝。批次范围 1—10；无自动重试。

executeOnline 为测试包内专用程序化入口，复用现有 ModelConfiguration.embeddingModel，不实现 HTTP 客户端或启动 Spring 上下文。它要求固定 DashScope compatible endpoint、模型、维度和一致 API Key；run 再校验实际客户端 options 中模型、1024 维、FLOAT、maxRetries=0。

重要限制：Budget 的逐输入计数是调用方提供的可信前置数据，生成器能校验哈希关联和合计，不能证明外部计数器符合供应商 tokenizer。仓库目前没有已验证的 text-embedding-v4 本地 Token 计数能力。因此 Main 的 EXECUTE 始终以 VERIFIED_TOKEN_COUNTER_REQUIRED 拒绝，不能仅凭用户填写数字或 API Key 放行。程序化入口只能在独立核验计数依据后使用；Fake 测试中的计数不是供应商计费证明。
没有声称 UTF-8 字节数等于 Token 数，没有虚构费用。后续需提供可信计数依据或可验证费用上界后，才可评审在线执行入口。本轮没有执行 executeOnline。

## 保存与恢复

继续使用 Manifest 和 Entry JSONL，没有新向量文件格式。首次授权执行写 manifest.json，每批先完整验证响应数量、唯一 Index、维度、有限值及非零，再按 Index 映射文本。将当前所有成功映射写入同目录临时文件，以 ATOMIC_MOVE/REPLACE_EXISTING 替换 vectors.jsonl；文件系统不支持原子移动则失败，不降级到不可靠覆盖。

恢复先以已有 Entry 集合作为期望集合复用 EmbeddingSnapshot.load，校验 Manifest、输入和所有向量，再按文本 SHA 复用；相同文本不同逻辑映射保存为各自 Entry。冲突向量和损坏文件拒绝继续。每次运行重新检查缺失输入和整次预算。
失败批次不写向量，保留之前成功批次，failure.json 只记录 STOPPED_UNCERTAIN 和当前运行尝试请求数，不记录输入或供应商异常原文。重新运行需要重新授权和核算预算；失败请求可能已计费，不将本地缺失等同于供应商未处理。

正式消费者必须沿用全量输入集合调用 EmbeddingSnapshot.load；部分 JSONL 会因 SNAPSHOT_MISSING 被拒绝。没有在线回退、没有完成标记，也没有把不完整结果提升为正式快照。

## Fake 验证

使用 Mockito 提供 Fake OpenAiEmbeddingModel；实际模型工厂从未调用。覆盖 DRY_RUN 零调用、未授权/缺 API Key/错误模型/输入或请求或 Token 预算不足拒绝、实际客户端配置不匹配、成功批次保存、错误 Index 和零向量批次拒绝、失败前成功数据保留、恢复只调用缺失文本、完成后零新增调用、乱序响应映射、损坏检查点拒绝，以及正式加载器拒绝部分快照。
Fake 结果仅证明控制流程，不证明在线调用、供应商 Token 计数或模型语义能力。

## 人工复核与下一阶段

Gold Q01—Q12 仍为 PENDING，本轮未修改任何状态。人工需确认必要证据的语义支持、答案点对应及指标协议。
M4-02C-2 前置条件：评审本实现；补齐供应商 Token 计数可信依据或可核验费用上界；明确模型、输入指纹、目标位置和本次新增输入/请求/Token 预算授权；在另一次授权任务生成并完整加载真实快照；完成人工 Gold 复核。

结论：READY WITH CONDITIONS。离线生成与恢复控制流程已实现；命令行真实执行仍因可核验 Token 预算依据缺失而关闭。本轮不进入 M4-02C-2。

## 补充实施最终验证

- mvn -q spotless:apply、mvn -q spotless:check：成功。
- mvn -q -DskipTests test-compile：成功。
- mvn -q '-Dtest=EmbeddingSnapshotGeneratorTests,EmbeddingSnapshotTests' test：生成器 5 tests + 协议 1 test，共 6 tests，0 failures/errors/skipped。
- git diff --check：通过；对本轮两个新增 Java 和更新报告另用 git diff --no-index --check 检查，未发现空白错误（no-index 返回 1 表示存在新增内容）。
- 未运行全量回归、GoldPreparationTests、PG/ES/MinIO 集成测试或真实在线请求。Gold 本轮没有修改。
- 编译早期曾出现 lambda 捕获可变引用和编辑期间格式检查失败，修正后上述最终完整定向命令链退出码为 0。
- Git HEAD 未变化，无新增 Commit SHA。

# Gold 最终修订与冻结（本节为当前有效状态）

此前章节的 PENDING、Gold 待人工复核和旧输入指纹均为历史记录，已由本节取代；旧指纹不得用于新快照生成或正式加载。在线预算限制仍然有效。

## 最终数据与人工确认

Q01、Q02、Q03、Q04、Q05、Q06、Q07、Q08、Q09、Q10、Q11、Q12：全部 CONFIRMED。
确认依据为用户逐题语义审查及本次最终修订授权，不是 SourceSpan 自动校验。没有发现必须扩展证据才能支持的新增答案点。

- Q01：补充提交异常不必然证明回滚，将正常返回限定为目标方法。
- Q02：补充受检异常默认不回滚；限定目标内部捕获且异常未到达对应事务代理。
- Q05：问题改为版本、接口和模型系列；答案明确 Spring Boot 3.5.15、Spring AI 1.1.8、百炼兼容接口、Qwen 与 OpenAiChatModel 类名含义。
- Q08：明确各路 1/(k+rank) 贡献求和、rank 从 1 开始、实验默认 k=60、单路首次去重后连续排名、跨路同 ID 累加、不加原始分数。
- Q09：问题限定单队列消费端，答案保留配置、同步独立事务提交后 ACK 和失败阻断依赖事件；不将此作为完整端到端保序条件。
- Q11：区分提交与立即执行/并行，限定 Future 尚未完成时 join 等待。
- Q03/Q04/Q06/Q07/Q10/Q12：问题、答案及证据保持不变。

12 条来源路径与 SHA、全部 EvidenceId、SourceSpan、标注原文和 ALL 关系保持不变。Q04 的两个区间没有扩大或缩小。已与 HEAD 的原始 Gold 数据逐项比较来源及证据不变量。
Evidence Review 新增独立的 structuralValidation、semanticReview、coverageAuditNote；原 baseline/structureAware 覆盖结果保持原样。原文位置正确、单块支持、多块联合支持、语义充分性仍分别判断；CONFIRMED 不保证任何特定检索结果会覆盖证据。

## Query 与整体输入冻结

协议新增每问 querySha256，生成器校验 questionId 与实际文本 UTF-8 SHA 的映射。仅 Q05/Q09 的 Query 文本发生变化，其余十条 Query SHA 不变。

| 输入 | 旧 SHA（失效，仅迁移审计） | 当前 SHA |
|---|---|---|
| Q05 | 30d8666523ebf274e962a6a5d26c29023a820ebfc6178cbc6c93c2e14659b3c0 | 4b45b00377685e73088c947fbb4f23c9e1b4b374f0a929f5e73ac343225516da |
| Q09 | 56fced6f14a0e98c337119043b4adb3b1eb1025593566737eed2daadc84998a2 | 7052634c199e8f0dae43dbcee6d68957673b4efd80dce0534c18b2a20cb60ff2 |

整体输入指纹由全部 153 个 Input 映射的 Jackson JSON UTF-8 字节 SHA-256 得到：

- 当前有效：d8080d19731b571a32985c3889c398138fb1c661bfef780bc8994c54398492d8。
- 旧值失效，仅作迁移追溯：a73688e965e06dbe888c1a42bbb9bdcbcf09e5b0afe4f60c071020fd53285f30。
- 模型配置指纹保持 46126af9b308e9c300da290642ef34e8822575b3652a1028cdedf9fc5fe641f9。

生成器现只接受新整体指纹；旧 Manifest 不会被静默接受。实际输入映射清单输出为 target/m4-02c1/inputs.json，不含向量或原文正文，属于本地运行产物。答案要点修订不改变 Embedding 输入；两条 Query 修订改变对应输入 SHA 及整体指纹。

本轮实际修改：m4-01-gold-evidence.json、m4-01-evidence-review.json、m4-02c1-gold-protocol.json、GoldPreparationTests.java、EmbeddingSnapshotGenerator.java、EmbeddingSnapshotGeneratorTests.java、本报告。

## 在线执行条件

本次没有生成任何真实向量，没有调用 Embedding/Chat/Rerank，也没有启动 PG/ES/MinIO。
仍需可信的 text-embedding-v4 Token 计数依据或可核验费用上界、明确在线授权、API Key、模型身份、目标位置，以及本次新增输入/HTTP 请求/Token 预算。命令行 EXECUTE 仍因计数依据缺失拒绝执行；人工 Gold CONFIRMED 不等于在线授权。程序化预算的外部计数依据仍需独立核验。
不进入 M4-02C-2，不 Commit/Push/Merge；HEAD 仍为 308399308626a6e3a0d2f7f38ebdce6936d3e5a5。

## 本次冻结的实际验证结果

- spotless:apply、spotless:check、test-compile：成功。
- mvn -q '-Dtest=GoldPreparationTests,EmbeddingSnapshotGeneratorTests,EmbeddingSnapshotTests' test：7 tests，0 failures/errors/skipped；包含真实样本 DRY_RUN 与 Fake 控制流程，未调用真实模型。
- DRY_RUN：12 Query + 58 FIXED_CODE_POINT + 83 MARKDOWN_STRUCTURE_AWARE = 153；唯一输入 153、已有 0、待生成 153；批量 10、计划 16 次请求（15×10 + 3）。目标 target/m4-02c1/embedding。
- 输入清单 target/m4-02c1/inputs.json 的实际字节 SHA 与新整体指纹相同；数量独立复核通过。
- git diff --check 通过；对未跟踪的本次修改文件另做 no-index 空白检查。
- 冻结更新前的一次 DRY_RUN 被旧指纹保护拒绝，显示新候选指纹；授权更新冻结值后最终定向命令链全部成功。没有绕过指纹校验。
- 未运行全量 Maven 回归、PG/ES/MinIO 或在线模型验证。

最终状态：Gold 已完成本轮人工确认与最小修订冻结；在线执行仍为 READY WITH CONDITIONS，等待预算依据与明确在线授权。
