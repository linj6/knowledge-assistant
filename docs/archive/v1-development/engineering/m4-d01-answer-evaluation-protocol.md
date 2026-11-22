# M4-D01 Answer Evaluation V1 协议（V1 已冻结）

本轮完成用户授权的 Gold 最终确认与冻结，不实现 Answer Runner，不执行模型。冻结基线为 `94af1df18d993f38077f114c015348afb6919306`。

## 数据与冻结边界

数据见 [answer-evaluation-v1.json](../../src/test/resources/answer-evaluation-v1.json)：Q01–Q12 原问题不变，45 条 requiredFacts、1 条 optionalFacts（合计 46 条 Fact）；U01–U04 为已确认不可答题。16 Cases / 46 Facts 均为 CONFIRMED，datasetVersion=1，freezeStatus=FROZEN。reviewer=USER，依据本轮 ChatGPT 会话对指定 Q11-F02 修订及其余候选与协议的整体批准，不虚构逐条独立人工审阅。历史 Codex 建议保留于评审准备文档。

来源路径相对仓库根目录，SHA-256 按原文字节计算；sourceRefs 的 evidenceIndexes 指向旧 Gold 同题 goldEvidence 的零基索引，是人工复核定位范围，不表示整段每字都必须出现在答案。qualifications 与 description 共同约束事实。来源 SHA 与已有 Gold 必须一致，来源变化须另建版本，不修改旧冻结输入。

U01/U02 来源为预取实验 results.md，分别询问吞吐率/P99、OS kill 恢复耗时；U03 来源为 chunking-and-context.md，询问缺少真实问题与预算条件时的通用最优 Chunk 精确值；U04 来源为同文档明示的人工模拟、尚未实测排名。它们只要求拒绝提供材料无法确定的目标结果，可以解释已知边界；不要求一字不答。标签适用范围仅为历史 M4-01 八篇文档快照，并非对所有相邻仓库或当前线上知识库作不存在性证明。当前 C03 停车业务 KB 与此快照不匹配，禁止直接评分。

## 评审记录协议

未来每例记录 caseId、datasetVersion/Hash、source hashes、实际 KB/发布版本/snapshotHash、模型/Prompt/参数版本、requested/actualStrategy、degraded、原始输出（受控离线产物）、RagAnswer、错误码、候选正文与身份、实际 SelectedEvidence 正文与身份、人工 reviewer/time/reason。Gold 事实、预期答案和评审标签不得作为模型 Prompt 输入。

人工结果记录 knowledgeAnswerable（TRUE/FALSE/PENDING）、contextAnswerable（TRUE/FALSE/PENDING）、executionStatus（COMPLETED/FAILED/NOT_RUN）、responseType（ANSWER/REFUSAL/NONE）、五维结果、逐 Fact 判断及理由、逐 Claim 到引用集合的支持映射、wholeCasePass。所有尚未执行或尚未判断的结果初始化 PENDING，不能预填 PASS。快照不匹配或标签未确认记 eligibility=NOT_READY，不进入正式质量统计。

Knowledge Answerable 指冻结全体材料足够回答所问目标；Context Answerable 仅依据本次实际进入 Prompt 的正文是否足够，不能用候选池命中、CEC 或相似度代替。可逐 Fact 标注上下文支持，全部必要事实可支持才为 TRUE。材料冲突或身份不明记 PENDING。

## 五维判定

所有维度共用：PASS=已审查满足；FAIL=已审查存在明确违例；N/A=本例没有该维度的评价对象；PENDING=尚未审查或证据不足以判断。N/A 不是通过，PENDING 不是失败。

|维度|PASS|FAIL|N/A|
|---|---|---|---|
|Response Appropriateness|上下文足够时切题回答；不足时明确限制并合理拒绝目标请求|充分上下文下错误拒答；不足时假装有证据给出目标结论；答非所问|执行失败无可评响应|
|Factual Correctness|所有实质事实断言均正确，条件、对象、单位、范围无误|任一错误或臆造断言即整维失败，不用其他正确断言抵消|没有可核验事实断言|
|Answer Completeness|可答题全部必要事实及限定均表达且无矛盾|遗漏任一必要事实或限定；可答题拒答仍失败|Knowledge 不可答的目标事实，不要求编造补齐|
|Citation Validity|所有引用通过同次后端身份校验且回答所需非空引用存在|已观测非法/重复/跨请求引用或回答缺少必需引用|合法拒答无引用，或没有可评输出|
|Citation Semantic Support|人工核验引用正文集合支持全部实质 Claim，且每条引用至少支持一个 Claim|任一 Claim 无正文支持、条件缺失、引用与结论矛盾，或混入无关引用|INSUFFICIENT_EVIDENCE 拒答（无论说明是否包含事实 Claim），或没有可评输出|

事实正确性不通过关键词重合率或相似度自动判断。缺少必要事实属于完整性失败，不自动构成事实错误；无法核实断言记 PENDING，明确捏造或错误才记 FAIL。事实真实但引用不支持时，正确性可以 PASS，语义支持必须 FAIL。拒答时 Citation Semantic Support 固定为 N/A；拒答说明中的事实仍通过 Factual Correctness 核验，拒答理由及边界通过 Response Appropriateness 评价，不因无引用免除准确性要求。

requiredFacts 中所有事实为 REQUIRED / ALL；optionalFacts 为 OPTIONAL，遗漏不导致完整性失败，主动陈述仍需准确且 ANSWERED 的实质 Claim 仍需引用支持。role（显式提供时）必须与所属数组一致，不代表 reviewStatus 已确认。Q03 保留全部列类型、非空、主键及联合索引顺序为必要结构，只有 InnoDB 转为可选。允许等义表达，一条句子可覆盖多个 Fact，逐 Fact 记录判断与答案定位。Q11-F03 仅保留原文示例中调用者最后 join 等待组合结果，不追加已完成分支。

## 引用与执行语义

现有 RagAnswer 是全局 Citation List，不支持逐句引用。人工拆分 Claim，再记录支持它的一个或多个实际引用 ID；多个引用可以联合支撑一个 Claim，一个引用可以支持多个 Claim。只检查被实际引用正文的联合语义，不能拿未引用 SelectedEvidence 或未入选 Gold 补证，也不能要求每条引用独立支持全部答案。

Citation Validity 复用 CitationValidator：Retrieval SUCCESS，同次正式 M2 发布快照候选；E 正整数编号、不重复、存在于 evidenceMap 且实际入选；KB、正数 chunk/document/version ID、合法区间、正文、headingPath 与同次候选完整匹配。仅有数据库 ID 不证明发布身份。StructuredOutputParser 负责严格三字段、类型、非空说明、可答引用非空及拒答引用为空；Schema 失败属于 OUTPUT_INVALID，不是语义不支持。

FAILED（检索、模型、上下文、解析、引用校验等）是执行失败；没有正常质量答案。保留错误码；CITATION_INVALID 时可将已观测引用合法性记 FAIL，其他质量维度无可评对象记 N/A，不虚构被丢弃输出的语义。未捕获异常同样记录执行失败。INSUFFICIENT_EVIDENCE 是完成的证据不足结果，不是执行失败：空检索可返回 null answer；模型拒答必须有非空说明且无引用。不得把超时当合理拒答。

Knowledge TRUE、Context FALSE：合理拒答的 Appropriateness 可 PASS，但 Completeness FAIL，整例不能通过。检查必要证据是否在候选：不在则优先检索问题；在候选但未入选则上下文选择/预算问题；Context TRUE 仍错误拒答、漏答或错误断言则为生成问题。未保存候选/实际上下文时归因 PENDING，不武断归责。Knowledge FALSE 的正确拒答须遵守 allowedAnswerBoundary，不能生成 forbiddenFabrications。Q04 能回答“证据不足以证明提升”及观察数字，因此仍为可答题。

## 整例与有效分母

正式统计只纳入来源/配置适用且全部必要人工标签已确认的样本。Whole-case：执行 FAILED 直接 FAIL；执行完成且所有适用维度 PASS 才 PASS，任一 FAIL 则 FAIL，无 FAIL 但存在 PENDING 则 PENDING。可答题错误拒答或因上下文不足拒答均不能整例 PASS；不可答题正确拒答可在完整性、Citation Validity 和 Citation Semantic Support 为 N/A 时通过。NOT_RUN 为 PENDING。

每维通过率为 PASS/(PASS+FAIL)，分别报告 N/A、PENDING、NOT_READY 和执行失败数量；不以 N/A 填满分。整例通过率为 PASS/(PASS+FAIL)，执行失败包含在整例失败分母，另报执行成功率 COMPLETED/(COMPLETED+FAILED)。同时展示计划数、实际执行数、人工已完成数及待审数，避免排除待审造成虚高。当前 Gold 已冻结，但未执行答案评估，实际质量评分分母仍为 0，不发布质量成功率。

## D01 冻结边界

已整体批准的范围：46 条 Fact 的必要性及拆分粒度；Q03 保留精确列结构及引擎可选的角色划分；Q04 两组数字与否定结论；Q08 单路去重后连续排名；Q09 事务提交与依赖事件限制；Q11 修订后示例范围；U01–U04 快照范围、拒答边界及候选之间覆盖重复性。16 例只是开发集，不代表独立测试集或全库覆盖。

READY FOR M4-D02：是，仅表示 D01 数据和协议准备完成；本轮不开始 D02。未新增 Runner、生产代码或依赖。

## 本轮争议项修订

ANSWERED 必须有合法、非空引用；INSUFFICIENT_EVIDENCE 必须无引用，Citation Semantic Support=N/A。模型拒答须有说明，空检索路径允许 null answer；FAILED 不属于正常拒答。整例通过必须满足全部适用维度，不以 N/A 抵消失败。此口径沿用现有 StructuredOutputParser、RagAnswer 与 CitationValidator，不修改生产协议。

Q01-F03 恢复“可能”；Q03 为7条必要、1条可选；Q11-F03 移除未直接支持的已完成分支；U03 改为缺少问题与预算条件，无法给出通用最优精确值。全部 Fact 和拒答标签现依据用户整体批准确认冻结。

## 最终冻结记录

- datasetVersion：1；协议版本：answer-evaluation-v1。
- 16 Cases（12 可答、4 不可答），46 Facts（45 REQUIRED、1 OPTIONAL，Q03-F08）。
- confirmedAt：2026-11-14T08:34:36Z；reviewer：USER。共同确认元数据由全部 Case/Fact 继承。
- freezeFingerprint：`57c202a788d20e86a2ed166776c77dab6e66bed8b4ae08a1a925806458a5dbca`。
- Q11-F02：两个任务通过执行器调度，有两个空闲线程时可以并行；提交任务不保证一定并行执行。依据同题 E0 及同一 SHA 原文第123行的执行器调度说明（数据集 supplementalEvidence）。

指纹为排除顶层 freezeFingerprint 后，递归排序对象键、保留数组顺序、紧凑 UTF-8 JSON（ensure_ascii=false，无多余空格）的 SHA-256。已存储的 confirmedAt 参与计算，复算不生成新时间；内容、来源身份和确认元数据变化均改变指纹。freezeManifest 保存数量、八篇来源 Hash 和协议版本。

五维评估与 Whole-case Pass 规则保持不变。引用语义支持仍需后续对实际回答及引用正文作人工评审；Gold 冻结不代表模型回答质量已通过，历史评审建议不代表新的待审 Gold 标签。
