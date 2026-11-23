# Knowledge Assistant · M4 RAG 评估指标学习手册（审阅稿）

> 目标读者：首次接触 RAG 的开发者、需要解释评估报表的后端工程师，以及未来项目使用者。  
> 状态：**已归档的学习手册，保留审阅稿性质**。  
> 依据：`EvidenceMetrics.java`、`RankingMetrics.java`、`AnswerMetrics.java`、`m4-b04-retrieval-evaluation-summary.md`、`m4-d01-answer-evaluation-protocol.md` 的当前项目实现与冻结协议。  
> 本文将**项目实际规则**与**教学示例**分开表述。教学数字不代表项目实测。

## 1. 为什么需要评估？

RAG（Retrieval-Augmented Generation，检索增强生成）可以简化为：

**用户问题 → 文档切分 → 检索与排序 → 选取上下文 → 大模型回答 → 引用校验**。

问题可能发生在不同位置：

- **切分问题**：定义或条件被分散到不同 Chunk。
- **检索问题**：关键证据没有进入 Top-K。
- **排序问题**：相关 Chunk 找到了，却排得太靠后。
- **上下文问题**：检索候选有证据，但实际 Prompt 没有选中。
- **生成问题**：模型看到了足够证据，却答错、漏答或错误拒答。
- **引用问题**：引用 ID 合法，但被引用的正文并不支持回答。

所以不能只用一个“答案正确率”代表全部 RAG 能力。M4 把指标分为**检索与排名**、**回答与引用**两组，并且保留每一项可评估的前提。

## 2. 先理解八个核心名词

| 名词 | 通俗含义 | 本项目的特殊含义 |
|---|---|---|
| Question / Case | 一道评估问题 | 检索 Gold 12 题；Answer Gold 16 题（12 可答 + 4 不可答） |
| Chunk | 被切开的文档片段 | 带文档、发布版本、来源起止位置、正文 |
| Top-K | 检索排名前 K 条 | 当前冻结对照主要用 @3 / @5 |
| Gold Evidence | 人工确认的目标证据 | 对应原文**字符/代码点区间**，可能一题多段 |
| SourceSpan | 证据或 Chunk 在原文中的区间 | 连续覆盖区间才算一段 Gold 被完整覆盖 |
| Relevance Grade | Chunk 对问题的人工相关性等级 | 0、1、2、3；**≥2 才算 Precision 的相关项** |
| Claim | 模型答案中的一个实质性事实断言 | 人工判断真假及由哪些引用支持 |
| REQUIRED Fact | 正确完整回答必须包含的事实 | Answer Gold V1 共 45 条；另有 1 条 OPTIONAL |

**注意“Gold”不是“答案”。** 检索 Gold 描述应找到的证据；Answer Gold 描述答案必须满足的事实与拒答边界。它们相互关联，但不能混用。

### @3、@5 是什么？

`Recall@3` 只看排在前 3 位的 Chunk，`Recall@5` 看前 5 位；`@K` 指**观察深度**，不是一个独立算法。通常 K 增大能增加证据覆盖机会，但也可能带来无关文本、上下文成本。对于固定 K 的 Precision，还可能因为增加的结果不相关而降低。

## 3. 一道题贯穿理解检索指标

**以下全部是教学示例，非项目实际测量。** 假设问题需要三段 Gold：`G1`、`G2`、`G3`。检索返回前五个 Chunk：

| 排名 | 人工相关等级 | 该 Chunk 对 Gold 的覆盖情况 |
|---:|---:|---|
| 1 | 3 | 完整覆盖 G1 |
| 2 | 2 | 仅覆盖 G2 前半段 |
| 3 | 0 | 无目标证据 |
| 4 | 1 | 完整覆盖 G2 的剩余半段，并与第 2 条连续拼接 |
| 5 | 3 | 完整覆盖 G3 |

在这个例子中：

- **@3**：只完整覆盖 G1，G2 尚未完整，G3 未出现。
- **@5**：G1、G2、G3 都完整覆盖；G2 是第 2 与第 4 条来源区间的并集共同补齐的。
- 一条 Chunk 的相关性等级是人工语义标注；一段 Gold 的完整覆盖是来源区间计算。**两者不是同一件事。**

## 4. 检索与排名指标：定义、公式、示例

### 4.1 Evidence Recall@K —— 必要证据召回比例

**一句话**：前 K 条 Chunk 完整覆盖了该题几段人工指定的 Gold Evidence？

单题：

\[
\operatorname{Recall@K}=\frac{\text{已完整覆盖的 Gold 段数}}{\text{该题全部 Gold 段数}}
\]

教学示例中有三段 Gold：@3 只覆盖 G1，`Recall@3=1/3≈33.3%`；@5 覆盖三段，`Recall@5=3/3=100%`。

**项目实现**：通过 `EvidenceMetrics.calculate()` 按文档身份、来源版本及 `sourceStart/sourceEnd` 验证；多个 Chunk 的连续区间可并起来覆盖同一段 Gold。报告对**各题 Recall 先计算，再做宏平均**，不是将全体 Gold 段数直接加总求比值。

**局限**：这是 SourceSpan 区间完整覆盖，不保证该段文本一定提供了充分的语义回答条件；也不是“全库所有相关文档的召回率”。

### 4.2 CEC@K —— 全部必要证据是否完整覆盖

CEC = **Complete Evidence Coverage**，关注的不是“覆盖多少”，而是“一道题要求的 Gold 是否**全部**覆盖”。

单题取值只有 `0` 或 `1`：

\[
\operatorname{CEC@K}=\begin{cases}1,&\text{该题全部 Gold 段完整覆盖}\\0,&\text{否则}\end{cases}
\]

例子：三段 Gold 只覆盖两段，Recall 为 66.7%，但 CEC = 0；三段全覆盖，CEC = 1。12 道有效题有 9 道 CEC=1，宏平均为 9/12=75%。

**适合回答**：“到前五名为止，有几道题拿齐了全部标注的目标来源区间？”

**不适合回答**：“模型能正确回答几道题？”源区间覆盖不等于语义完整，更不等于模型答对。

### 4.3 Evidence Hit@K —— 是否命中至少一段完整证据

单题：只要至少**一段** Gold Evidence 被前 K 条完整覆盖，就记 1；否则记 0。再按有效题目做平均。

教学示例：@3 已完整覆盖 G1，所以 `Hit@3=1`；但是 `CEC@3=0`。

**适合回答**：“检索是否至少抓到一条目标证据？”

**局限**：Hit=1 对需要多段互补证据的问题远远不够。

### 4.4 First Evidence MRR@5 —— 首次完整命中证据的排名

MRR = **Mean Reciprocal Rank**，本项目的“相关”不是通常泛指的相关文档，而是**第一段完整覆盖的 Gold Evidence**。

逐题先找首次完整覆盖任意 Gold 的前缀排名 `r`，得分为 `1/r`；如果前五条都没有，记 0；再对题目做平均。

| 首次完整覆盖 Gold 的排名 | 单题得分 |
|---:|---:|
| 1 | 1.000 |
| 2 | 0.500 |
| 3 | 0.333… |
| 5 | 0.200 |
| 前 5 名都没有 | 0 |

教学示例的 G1 在第 1 名完整覆盖，所以 MRR 单题=1。

**局限**：MRR 高说明第一段证据靠前，不代表所有需要的证据已经到齐。历史报告中的 `MRR@5` 只评价前五条。

### 4.5 CCR —— 首次完整覆盖全部证据的位置（诊断值）

CCR = **Complete Coverage Rank**，项目报告用它回答：“读到第几条时，全部 Gold Evidence 首次齐了？”

教学示例中：到第 4 名才补齐 G2，但 G3 第 5 名才出现，所以 `CCR=5`。若前 5 条仍未完整覆盖，记 `NOT_REACHED / NR`，而不是无穷大或“全库都找不到”。

它与 CEC 的关系：`CCR≤3` 则 `CEC@3=1`；`CCR≤5` 则 `CEC@5=1`。CCR 常用于逐题定位证据补齐发生在第几名。

### 4.6 Precision@K —— 前 K 个位置中“高相关 Chunk”比例

项目先由人工给每个候选 Chunk 一个等级：0、1、2、3；**≥2 视为相关**。

\[
\operatorname{Precision@K}=\frac{\#(\text{前 K 条中 relevance}\ge2)}{K}
\]

等级 `[3,2,0,1,3]`，`Precision@5=3/5=60%`。

**项目特别规则**：分母固定为 K，不能因实际结果数量少于 K 就改用返回数；成绩源于**冻结、完整人工标注的候选池**，不能替未知 Chunk 猜等级。

**局限**：一条高相关 Chunk 不一定独自包含完整回答；多个 3 级 Chunk 也可能重复提供同一个事实。Precision 高不等于 CEC 或答案正确率高。

### 4.7 DCG、IDCG、nDCG@K —— 排序质量

这一组较容易抽象，记住：**不仅要找对，还要把高相关结果排在前面。**

**DCG（Discounted Cumulative Gain，折损累计收益）**：按相关等级给收益，排名越靠后，同等相关性的收益越低。

\[
\operatorname{DCG@K}=\sum_{i=1}^{K}\frac{2^{grade_i}-1}{\log_2(i+1)}
\]

等级 0、1、2、3 分别得到收益 0、1、3、7；实现只对真实返回的前 K 条求和。

**IDCG**：将同题、同切分、同快照下**冻结完整候选并集池**的人工等级从高到低理想排列，再按同一公式算理论最佳 DCG。不是在无限全库中寻找理想结果。

**nDCG**：`nDCG@K = DCG@K / IDCG@K`。如果 `IDCG=0`，本项目记 `UNDEFINED / null`，不能记 0%。

教学例子：候选等级为 `3、2、0`，但当前实际排序是 `[2,3,0]`，取 K=3：

- 实际 `DCG=3 + 7/log₂3 ≈ 7.4165`。
- 理想 `IDCG=7 + 3/log₂3 ≈ 8.8928`。
- `nDCG≈7.4165/8.8928≈0.834`，即约 83.4%。

**特别注意**：不同切分方式的 Chunk 和候选池可能不同，其 IDCG 也不同。因此 Fixed 的 0.84 与 Structure 的 0.85 **不能当作完全同一基准下的优劣差值**。nDCG 不代表完整回答比例。

### 4.8 Semantic Complete Coverage —— 人工语义完整覆盖率

它不是 `EvidenceMetrics` 自动算出的 SourceSpan 指标。历史 M4 报告另有人工语义判断，区分**语义 COMPLETE / PARTIAL / MISSING**。

给定 Top-K：逐题人工判断相关材料的正文是否足以支持问题所需的必要事实。汇总时用 `COMPLETE 题数 / 有效题数` 表达完整率，PARTIAL/MISSING 不算 COMPLETE。

**最容易混淆的结论**：

- `CEC=1`：项目标注的来源区间被完整覆盖。
- `Semantic COMPLETE`：经人工看过正文，判断回答所需的事实语义已足够。
- `Answer Correctness=PASS`：实际模型生成的事实断言正确。

三者不能互相推导。历史 Q11 就出现**CEC 通过但人工语义仍 PARTIAL**的情况；历史 Q07/Q10 又存在语义已经 COMPLETE、CEC 却未覆盖完整宽区间的情况。

## 5. 真实历史结果：怎样读一张评估表？

以下来自 **M4-B04 历史冻结检索对照**，12 道问题、两种切分、三个策略。数值是实际报告，不是演示数据。

| 切分/检索 | Precision@5 | nDCG@5 | Evidence Recall@5 | CEC@5 | Semantic Complete@5 |
|---|---:|---:|---:|---:|---:|
| 固定切分 / VECTOR | 55.0% | 72.2% | 75.0% | 75.0% | 75.0% |
| 固定切分 / BM25 | 61.7% | 81.8% | 87.5% | 83.3% | 75.0% |
| 固定切分 / HYBRID_RRF | 65.0% | 84.6% | 83.3% | 83.3% | 75.0% |
| 结构切分 / VECTOR | 46.7% | 72.5% | 83.3% | 83.3% | 75.0% |
| 结构切分 / BM25 | 53.3% | 78.5% | 95.8% | 91.7% | 83.3% |
| 结构切分 / HYBRID_RRF | 56.7% | 84.2% | 91.7% | 91.7% | 83.3% |

阅读时应这样问：

1. **P@5 高吗？** 前五条高相关 Chunk 的占比如何？
2. **nDCG@5 高吗？** 在本冻结候选池内，高相关结果是否靠前？
3. **Recall/CEC 高吗？** Gold 来源区间是否被找到、是否找齐？
4. **语义 Complete 高吗？** 真正足够解释问题吗？
5. **能否据此改生产切分？** 还不能。历史对照中 Structure 对部分覆盖指标有提升，但 P@5 下降、语义收益有限，且不同候选池存在口径限制；因此生产仍保留 Fixed 方案。

**真实案例**：Q04 在全部六组的 CEC@5 都没有达到完整覆盖；这不等于全库没有证据。Q09 在某些 Fixed 组合里排名指标很好，但人工语义仍不完整，说明“高相关 Chunk”可能缺少互补条件。

## 6. Answer Evaluation：五维回答质量指标

检索指标只检查“模型可能看到了什么”，不能替代“模型实际说得对不对”。M4-D01 冻结了 16 道 Answer Gold（12 可答、4 不可答），总共 **45 REQUIRED Facts + 1 OPTIONAL Fact**。M4-D02 的 `AnswerMetrics` **只汇总人工评审标签**，不会自动理解答案含义。

### 6.1 Response Appropriateness —— 回答/拒答方式是否合适

**问题**：当前 Prompt 证据能支持回答时，模型有没有回答？不能支持时，是否适当拒绝给出目标结论？

- **PASS**：上下文充分且切题回答；或者证据确实不足而合理说明限制。
- **FAIL**：有足够上下文却错误拒答；没有足够证据却编造目标结论；或答非所问。
- **PENDING**：上下文可答性尚未查明，不能判断行为是否合适。

必须分开：`Knowledge Answerable`（冻结材料总体上能答）与 `Context Answerable`（**本次真正进入 Prompt 的证据**能答）。前者为 TRUE、后者为 FALSE 时，模型合理拒答，Appropriateness 可以 PASS，但这道**端到端可答题仍未完成**，Completeness 为 FAIL。

### 6.2 Factual Correctness —— 事实正确性

**问题**：模型实际说出的每一个实质事实 Claim，是否真实、条件准确、没有臆造？

- 一条明显错误的 Claim 就使整维 **FAIL**；不能用另外几条正确断言抵消。
- 某个必要 Fact 没提到，**只影响 Completeness，不自动意味着 Factual Correctness 错误**。
- Claim 没有完成审查、Claim 清单不全时，通常是 **PENDING**。
- 没有可核验实质 Claim 时，可为 **N/A**，不是 PASS。

教学示例：问“提交异常是否一定回滚”，模型答“不一定”，但没有提及连接故障核实。这句话可能**事实正确 PASS**，同时**必要事实不完整 FAIL**。

### 6.3 Answer Completeness —— 必要事实完整性

**问题**：可答问题的 REQUIRED Facts 是否全部正确表达？

对单道可答题，逐条核验该题自己的 Required Fact：

- 全部覆盖 → PASS。
- 任意一条确认遗漏 → FAIL。
- 尚有未完成的 Fact 判断且无明确失败 → PENDING。
- 不可答题的目标事实 → N/A；不得因为没编造不存在的数值而扣分。

**项目细节**：45 是整份 Gold 的 REQUIRED 总数，**不是每道题的分母**；OPTIONAL 不参与完整性计算。一句话可以同时覆盖多个 Fact，不要求固定措辞。

### 6.4 Citation Validity —— 引用是否合法

**问题**：引用编号和证据身份是否合法、是否确实来自本次 Prompt 选中的证据？

正式 `CitationValidator` 检查 E 编号、去重、是否入选、KB/Chunk/文档/版本、来源区间、正文及 headingPath 等身份一致性。

- 正常 `ANSWERED` 必须有**合法且非空**的 Citation 集合。
- `INSUFFICIENT_EVIDENCE` 必须**没有 Citation**；合法拒答的 Validity 记 N/A。
- 如果引用校验失败导致执行 `FAILED`，不允许把它当成一个正常可评答案；可在有明确 `CITATION_INVALID` 证据时单列 Validity FAIL。

**注意**：`E1` 合法，不代表 E1 的正文支持答案，这属于下一维。

### 6.5 Citation Semantic Support —— 引用是否真正支持答案

**问题**：实际 Citation 所包含的正文，能不能支持答案里所有实质性 Claim？

人工把回答拆成 Claim，再记录每个 Claim 由哪些**实际引用**共同支持：

| Claim | 人工事实判断 | 引用集合 | 人工支持判断 |
|---|---|---|---|
| 提交异常不必然说明回滚 | 正确 | E1 | 支持 |
| 提交期间连接故障可能需核实最终状态 | 正确 | E1 | 支持 |
| 所有提交异常都会自动重试 | 错误 | E1 | 不支持 |

规则：

- 某条 Claim 不受引用正文支持 → Support FAIL。
- 混入完全无关的引用 → Support FAIL。
- 多个引用**合起来**支持同一个 Claim 是允许的。
- 不能使用未被引用的 SelectedEvidence 或未入选的 Gold 给模型“补证”。
- 拒答无 Citation，因此 Support=N/A；但拒答解释是否真实仍要检查 Correctness 和 Appropriateness。

当前 `RagAnswer` 返回的是**整份答案的 Citation 列表**，不是逐句引用。人工的 Claim→Citation 映射是评审记录，不等于正式 API 已支持逐句引用。

## 7. Whole-case Pass —— 一道题到底算不算通过？

对执行完成且身份可评的 Case：**所有适用维度均 PASS，整例才 PASS**；任一维 FAIL，则整例 FAIL；没有 FAIL 但还有 PENDING，整例 PENDING。没有评价对象的 N/A 不视为失败，也不能冒充一次 PASS。

| 情况 | Appropriateness | Correctness | Completeness | Validity | Support | 整例 |
|---|---|---|---|---|---|---|
| 正确、完整、有支持的回答 | PASS | PASS | PASS | PASS | PASS | PASS |
| 事实正确，漏了必答条件 | PASS | PASS | FAIL | PASS | PASS | FAIL |
| 回答正确，但引用不支持 | PASS | PASS | PASS | PASS | FAIL | FAIL |
| 不可答题，合理拒答且解释真实 | PASS | PASS（如含事实说明） | N/A | N/A | N/A | PASS |
| 知识库能答、上下文不足，模型合理拒答 | PASS | PASS 或 N/A | FAIL | N/A | N/A | FAIL |
| 检索或生成调用失败 | N/A | N/A | N/A | N/A* | N/A | FAIL |

`*` 已记录 `CITATION_INVALID` 时可单列 Validity=FAIL。以上都是**规则演示**，不是模型真实得分。

## 8. 统计分子、分母和状态

### 8.1 为什么不能只显示百分比？

假设 16 道题里只有 2 道完成评审且均 PASS，其余 14 道 PENDING。屏幕显示“100%”会严重误导。

项目每维显示：

- **PASS**：已确定通过。
- **FAIL**：已确定失败。
- **N/A / NA**：该维不适用。
- **PENDING**：未完成判断。
- **NOT_READY**：Gold 指纹/来源身份/必要标签等不合格，不允许评分。

单维或 Whole-case 的已评通过率：

\[
\operatorname{PassRate}=\frac{PASS}{PASS+FAIL}
\]

例：PASS=2、FAIL=1、PENDING=13，已评通过率=2/3≈66.7%，但必须同时展示“仅 3/16 进入有效分母”。**当 PASS+FAIL=0，值为 `null / UNDEFINED`，不是 0% 或 100%。**

`AnswerMetrics` 还汇总 `executed`、`failed`、`reviewed`、`pending`、`notReady`。执行 `FAILED` 进入 Whole-case 的失败分母，不能通过剔除异常请求美化质量结果。执行成功率另外可按 `COMPLETED/(COMPLETED+FAILED)` 看。

### 8.2 几个必须分清的状态

| 状态 | 含义 | 是否能当作 0 分？ |
|---|---|---|
| `SUCCESS` / `ANSWERED` | 检索或回答流程执行成功 | 不等于质量通过 |
| `EMPTY_RESULT` | 检索确实返回空候选 | 身份合格时可按相应检索规则计零贡献 |
| `INSUFFICIENT_EVIDENCE` | RAG 正常拒答或检索无证据 | 需区分合理拒答与错误拒答 |
| `FAILED` | 检索/上下文/模型/协议执行失败 | 不得伪装为合理拒答 |
| `PENDING_REVIEW` | 尚缺人工标注 | 不得猜测分数 |
| `INVALID_POOL` | 排名候选池不符合评分前提 | 不可评分，不是低质量 0 |
| `GOLD_SNAPSHOT_MISMATCH` | 本次知识库与冻结 Gold 快照不同 | 不可按此 Gold 正式评分 |
| `UNDEFINED` | 指标数学分母为零等 | 显示 N/A/未定义，不写 0% |

## 9. 观察数字后，下一步怎么排查？

| 现象 | 优先检查 | 不应直接下的结论 |
|---|---|---|
| Recall 低、Hit=0 | Gold 来源、查询术语、分块、检索候选 | “模型不会回答” |
| Hit=1、CEC=0 | 是否需要多段互补证据、Top-K 深度 | “检索完全成功” |
| Precision 高、CEC 低 | 是否多条高度相关但内容重复、缺关键条件 | “相关性高所以能答对” |
| nDCG 高、语义 Complete 低 | 候选池整体质量和必要事实覆盖 | “排序指标高等于语义充分” |
| Context Answerable=FALSE | 检索候选有无必要证据、ContextBuilder 是否筛掉 | “一定是模型幻觉” |
| Correctness PASS、Support FAIL | 实际引用的正文与 Claim 逐项核验 | “引用 ID 合法就没问题” |
| Whole-case FAIL 且执行 FAILED | 错误码、超时、校验、运行配置 | “检索或模型质量一定很差” |

建议实际工作台分析路径：**先看评分可用性 → 看汇总指标 → 点进具体题目 → 对照 Gold 与 Top-K → 看真正进入 Prompt 的证据 → 看回答与 Citation → 最后做归因**。

## 10. 本文未包含哪些“看似相关但不是评估质量”的值？

- **BM25 原始分数**：ES 对关键词匹配排序的内部得分，不可与余弦距离直接相加或跨查询简单比较。
- **向量距离/相似度**：用于候选排序，不是“答案有 85% 概率正确”。
- **RRF 融合分数**：由不同检索路的排名倒数累加产生，不是质量指标；原 M4 实验还默认 `rrfK=60`。
- **Token 用量、阶段耗时、费用**：属于成本/性能观察值，不代替事实正确率或证据覆盖。
- **Chunking 策略名**：`FIXED_CODE_POINT`（固定长度切分）与 `MARKDOWN_STRUCTURE_AWARE`（结构感知切分）是实验条件，不是评分本身。
- **Retrieval Strategy 名**：`VECTOR`（向量语义检索）、`BM25`（关键词检索）、`HYBRID_RRF`（RRF 混合检索）是实验条件；本轮冻结对照没有把 `HYBRID_RERANK` 作为第四个已确认比较组。

## 11. 已完成与尚未证明的事情

**已经完成**：M4-B 检索指标算法与人工标注冻结；M4-C 离线/真实检索 Runner；M4-D01 16 道 Answer Gold 冻结；M4-D02 人工标签离线汇总算法；M4-D03-A 检索评估工作台的初版展示。

**尚未完成**：对 16 道题的真实 RAG 回答进行逐 Fact、逐 Claim、逐 Citation 的正式人工评审。因此**目前不能发布 Answer Correctness、Citation Support 或 Whole-case Pass 的实际成功率**。

历史 C02 针对停车业务知识库的 LIVE 检索与 M4 历史技术文档 Gold 不匹配，虽然真实执行成功，也不能据该 Gold 得出正式质量百分比。

## 12. 从哪里找到对应实现与原始结果？

| 要追踪的内容 | 代码或文档 |
|---|---|
| Recall / CEC / Hit / MRR / CCR | `src/test/java/com/lnjecit/knowledge/assistant/evaluation/metrics/EvidenceMetrics.java` |
| Precision / DCG / IDCG / nDCG | `src/test/java/com/lnjecit/knowledge/assistant/evaluation/metrics/RankingMetrics.java` |
| 五维回答评估 / Whole-case / 分母 | `src/test/java/com/lnjecit/knowledge/assistant/evaluation/metrics/AnswerMetrics.java` |
| 历史六组检索对照结果 | [m4-b04-retrieval-evaluation-summary.md](m4-b04-retrieval-evaluation-summary.md) |
| 回答评估冻结协议 | [m4-d01-answer-evaluation-protocol.md](m4-d01-answer-evaluation-protocol.md) |
| 检索 Gold 与人工 Ranking 标签 | `src/test/resources/retrieval-metrics-v1.json` 及 M4-B03 评审文件 |
| Answer Gold | `src/test/resources/answer-evaluation-v1.json` |
| M4-C01 历史输出 | `target/evaluation/m4-c01-offline-v1/`（如已运行生成） |
| 工作台本地运行记录 | `data/evaluation/{runId}/`（本地数据，Git 忽略） |

---

### 一分钟复习

- **Recall**：找到了多少段目标证据？
- **Hit**：至少找到一段没有？
- **CEC**：目标证据全部找齐没有？
- **MRR / CCR**：第一段 / 全部证据分别在第几名才到齐？
- **Precision**：前 K 条有多少条是较高相关的 Chunk？
- **nDCG**：高相关 Chunk 排得是否靠前（在本冻结候选池内）？
- **Semantic Complete**：人工判断语义证据是否足够？
- **Appropriateness**：该回答还是该拒答？
- **Correctness**：说出的事实对不对？
- **Completeness**：必要事实说全没有？
- **Citation Validity**：引用是否合法、来自本次上下文？
- **Citation Support**：引用正文真的支持所说的事实吗？
- **Whole-case**：所有适用维度是否全部通过？

**最重要的一句话：检索到相关内容 ≠ 找齐必要证据 ≠ 证据语义充分 ≠ 回答正确 ≠ 引用支持回答。**
