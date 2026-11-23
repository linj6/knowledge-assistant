# M4-01 Markdown 结构感知切分离线对照

## 范围与可重复执行

2026-11-13，Windows 11 / Java 21.0.10 / Maven 3.9.16。分支 `feature/m4-chunking-evaluation`，起始 HEAD `c51b7c9916211de28b7ad93ee0757ea9ac79c2f1`。保留用户已有未跟踪文件。仅新增测试代码与实验数据/报告；没有修改生产协议、切分、索引或 RAG 行为，没有新增依赖，没有访问模型、网络服务、数据库或 Docker。未 Push/Merge；Commit SHA 见 Git 提交记录。

阅读了生产 MarkdownDocumentParser、DocumentParserFactory、ParsedDocument、SourceSpan、UnicodeOffsets、DocumentSplitter、FixedCodePointSplitter、ChunkingConfig/Strategy 及 M4-00 入口、清单和报告。MarkdownDocumentParser 用带源码范围的 CommonMark AST 决定作用域，逐行 Fragment 保存码点范围。SourceSpan 是左闭右开码点区间，extract 用 offsetByCodePoints 回取。实验直接复用这些类型，不改变正文。项目只有 CommonMark core，没有 GFM 表格扩展；默认 Parser 不识别表格。

M4-00 文件读取、原文断言和统计方式沿用到专门的对照测试，保持原基线入口不变。A 直接调用生产 FixedCodePointSplitter 800/120；B 为测试目录内 MarkdownStructureExperiment，不加入生产枚举或 Spring。

```powershell
mvn '-Dtest=ChunkingComparisonTests,DocumentParserTests,DocumentSplitterTests,ChunkingStrategyTests' '-Dchunking.comparisonManifest=docs/evaluation/m4-01-samples.txt' test
```

两个来源仓库默认位于项目同级目录，可替换清单路径；输出目录通过 `chunking.comparisonOutput` 指定，默认 `target/chunking-comparison`。输出每篇/每策略完整 JSON（连续原文、范围、路径、辅助信息、原因、overlap），statistics.tsv 和 evidence-candidates.tsv。完整原文只留在 target；提交产物只含简短必要证据片段、清单、统计和审查结果。未指定清单时只跳过真实样本测试，合成边界测试仍执行。

## B 的实际规则

- `MARKDOWN_STRUCTURE_AWARE`：target=800、softMax=1200 CP。按 AST 顶层块、列表项建立连续区间，以后继结构起点为前一结构末端，空行和分隔符始终有归属。无标题前言正常处理。
- H1–H6 使用生产 HeadingPath 的作用域，不跨不同路径合并，父正文、子正文、相邻短章节分开。完整标题行不脱离原文；只有标题而无父正文时会形成短块，这是严格章节隔离的成本。
- 普通段落尽量完整；同路径相邻结构在总长不超过800时贪心合并。801–1200完整单元独立保留。结构前标题可能单独留下，当前不做回溯装箱。
- 普通围栏完整保留，语言写在独立 auxiliary；超长围栏按完整原始行降级，reason 为 LONG_FENCE_BOUNDARY。不会复制围栏或声称降级片段仍是完整围栏。
- 标准管道表格：Paragraph 第一行含管道、第二行匹配标准分隔行、所有行含管道，才标 TABLE；普通表格整体保留，超长表格按数据行边界降级。表头两行独立存入 auxiliary，不混入 sourceContent。未实现转义管道/列数校验、容器内 GFM 表格、复杂多行单元格，不声称这些格式被可靠保护。
- 顶层列表拆到完整 ListItem，缩进和多行项保留；引用块整体。超长项/引用按行降级，不实现复杂嵌套语义。嵌套代码不单独做方法级保护。
- 超长普通段落按句末/换行选择边界；句末规则为简单中英文句末标点，不代表语义理解。极端长句使用800/120局部窗口，overlap显式标记。单个代码/表格行等不可安全行单元超过1200时保留并记 OVERSIZE_SINGLE_LINE_*，不会丢弃正文。
- 每个 sourceContent 严格等于 SourceSpan.extract(originalSource)，没有补写标题、语言或表头。只有极端长句局部窗口可重复来源，硬性检查按区间并集覆盖，重叠必须恰为120且附原因。

### 发现的生产解析边界与实验适配

原 Parser 对围栏内空行可能给出空路径，例如 S2 [3048,3050)、[4298,4300)，S8 [864,866)。首轮直接按 Fragment 路径分组产生 CRLF 独立块，属于实验错误，已修复并重新执行：实验 scopedDocument 只将已由 AST 确认的 FENCE 内空路径继承该围栏起始路径。正文和范围保持原样，生产 Parser 不变。

本次跨标题指标对 A/B 都使用同一适配后的作用域，因而与原始 Fragment 指标存在口径差异。六篇旧文原始基线候选按 M4-00 逐行表相加实际为33，旧报告总述35是算术不一致，本次明确报告而不改写旧报告；本次八篇适配后的 A 为45，不能直接用35作差。此适配不是新的切分生产协议。

## 固定样本

S1–S6 复用原样本，字节 SHA-256 与 M4-00 一致。S7/S8 补充本地真实 Java/YAML 混排。没有修改来源仓库，不宣称本地字节等于远端 HEAD。

|样本|来源相对路径（仓库为路径首段）|SHA-256|选择理由|
|---|---|---|---|
|S1|backend-interview-lab/spring/notes/04-transaction-boundaries-and-propagation.md|5441d647f5eee2e17870e9be1ff5bca8add8322ed7a2ba7dbe88219caafe85f9|事务解释、传播条件及结果表|
|S2|backend-interview-lab/mysql/notes/01-index-and-sql.md|4fc99c7f82c01fa0c24503815e0353771ad736ef3e4979b2fbe530e8fd2407eb|SQL建表/查询、表格、长章节|
|S3|backend-interview-lab/rabbitmq/labs/06-prefetch-throughput/evidence/results.md|3024ef3ff721d3b8083cbca62299d57067ca08b001200c574b85a59a54dfbe94|实验条件、观察与验证限制|
|S4|ai-application-lab/03-llm-api-function-calling-spring-ai/notes/05-spring-ai-model-client-and-tools.md|e6c8a44e338836c0dd30f544890d3528cbe7ebda795c3ac3ce9d3b298d2f1010|多级标题、模型版本、代码图|
|S5|ai-application-lab/04-embedding-vector-search-rag/notes/04-chunking-and-context.md|92dbbea63d4a8ecc88b7dab563198ab02cc76b912a1e7a2f56ea582b235d3bf7|表格及跨段证据关系|
|S6|ai-application-lab/04-embedding-vector-search-rag/labs/04-hybrid-retrieval/README.md|6b8efd01cc0056f786efffd604d547a1a35f5bea27d0a24bf6b8b5ea514869f8|长实验说明、步骤与融合机制|
|S7|backend-interview-lab/rabbitmq/notes/05-ordered-messages.md|f89e2640255d383daa734939d5113f06dc39827f1073d32f1eb70afa995d865e|补充 YAML、Java 队列声明、SQL及失败前提|
|S8|backend-interview-lab/juc/notes/06-completable-future.md|a1809b5bc01ca6831d8e41f2c35c502cad49b4f854e7b8968216ef37ba35da53|补充多段 Java 异步组合、代码与解释混排|

补充样本的代码块均未超过1200；真实样本未触发超长降级。因此超长代码/表格、极端长句、超长单行例外仅由明确标记的合成测试验证，不冒充真实实验改善。

## 完整统计

所有长度为 Unicode Code Point。P50/P90 为排序后 nearest-rank；短块阈值 <200 是本次约定，不是行业标准。重复开销=(总Chunk字符量−原文长度)/原文长度，不包含辅助元数据。详细逐篇机器结果见 [m4-01-statistics.tsv](../../../evaluation/m4-01-statistics.tsv)。

|指标|A 固定800/120|B 结构800/1200|
|---|---:|---:|
|原文总长度|38148|38148|
|Chunk数|58|83|
|平均长度|761.17|459.61|
|P50 / P90|800 / 800|468 / 764|
|最小 / 最大|216 / 800|23 / 799|
|<200 数量/比例|0 / 0%|10 / 12.05%|
|>1200 数量|0|0|
|总Chunk字符量|44148|38148|
|相对原文重复开销|15.73%|0%|
|围栏内边界事件|19|0|
|表格内边界事件|10|0|
|列表项内边界事件|3|0|
|跨标题作用域Chunk|45|0|
|超长降级输出块/例外|0 / 0|0 / 0|

边界事件定义为“结构×落在其内部的去重Chunk起止坐标”；同一结构可能多次计数。它是自动结构候选指标，不能全部当作已确认严重语义缺陷。真实样本B无降级，故本轮减少的事件不是把必要拆分隐藏在降级标签中。

|样本|策略|数量|平均|P50|P90|最小|最大|短块|总字符量|重复开销|围栏/表格/项切断|跨标题|
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|
|S1|A|7|740.29|800|800|382|800|0|5182|16.14%|1/1/0|7|
|S1|B|12|371.83|281|681|28|713|2|4462|0.00%|0/0/0|0|
|S2|A|10|775.60|800|800|556|800|0|7756|16.18%|3/2/0|6|
|S2|B|13|513.54|502|777|193|785|2|6676|0.00%|0/0/0|0|
|S3|A|2|712.50|625|800|625|800|0|1425|9.20%|0/0/1|2|
|S3|B|3|435.00|303|736|266|736|0|1305|0.00%|0/0/0|0|
|S4|A|5|763.00|800|800|615|800|0|3815|14.39%|2/3/0|4|
|S4|B|9|370.56|287|702|33|702|1|3335|0.00%|0/0/0|0|
|S5|A|5|688.20|800|800|241|800|0|3441|16.21%|0/1/0|4|
|S5|B|6|493.50|438|791|225|791|0|2961|0.00%|0/0/0|0|
|S6|A|14|795.50|800|800|737|800|0|11137|16.29%|2/3/0|9|
|S6|B|17|563.35|672|782|23|799|3|9577|0.00%|0/0/0|0|
|S7|A|7|716.57|800|800|216|800|0|5016|16.76%|5/0/1|6|
|S7|B|12|358.00|396|471|74|548|1|4296|0.00%|0/0/0|0|
|S8|A|8|797.00|800|800|776|800|0|6376|15.17%|6/0/1|7|
|S8|B|11|503.27|554|764|39|778|1|5536|0.00%|0/0/0|0|

各篇各策略 >1200 和超长降级均为0。基础验收覆盖：精确回取、起点严格递增、区间无洞、重叠可解释、全文覆盖及 B 重复执行结果相等；A 确定性由直接运行的现有 DocumentSplitterTests 验证。

## 真实对比与人工复核

序号零基，范围左闭右开 CP。下述为审计者对照原文和生成 JSON 的复核，待用户人工评审，不宣称已有独立人类验收。

1. **明确结构改善、证据未改善：S2 建表。** 原围栏 [770,1089)，A0 [0,800) 只剩 `CREATE TABLE order_lab (` 开头；B2 [679,1453) 包含完整SQL。A1 [680,1480) 原本也包含完整SQL，所以 Q03 两种策略均 COMPLETE，不能把保护围栏直接算作证据分数提升。
2. **完整代码而非碎片：S1 Mermaid。** A1 [680,1480) 从围栏内部开始，缺开围栏；B1 [28,741) 保留原围栏 [237,737)。B2 [741,840) 则单独保留提交失败的限制说明；没有将图表和结论强行视为同一个证据单元。
3. **表格改善：S1。** A2 [1360,2160) 首行是 `常返回 | 代理按正常返回路径请求提交 |` 的残段，无该表表头；B3 [840,1521) 完整包含这张回滚规则表。该案例确认单块 Markdown 结构更完整，未运行召回来证明排名改善。
4. **证据改善：S7 Q09。** A1 [680,1480) 包含单队列配置，A2 [1360,2160) 包含失败必须阻止依赖事件先完成的限制；没有单块同时具备。B4 [1211,1759) 同时包含配置、YAML、Java声明和失败前提，DISTRIBUTED→COMPLETE。
5. **没有改善：S3 Q04。** 参数观察在 A0 / B0，未执行性能对照的限制在 A1 / B2；仍需多个块联合回答。按标题隔离不能消除跨节证据依赖。
6. **成本退化：S1 B0 [0,28)、S4 B0 [0,33)、S6 B0 [0,23)。** 顶层标题独占短块；它们不能独立支持多数正文问题，增加候选数与可能的无效命中。源文完整短知识点和纯标题短块需要区别对待。未在本轮为了消除短块而违反章节隔离规则。

## Gold Evidence 与语义结果

固定12题，后端8题、AI4题；8篇均被覆盖。[Gold 标注](../../../evaluation/m4-01-gold-evidence.json) 保存 questionId、路径、字节哈希、问题、答案点及必要原文范围/文本；多区间分别保存，不绑定Chunk。标注在首次对照运行前建立，未根据结果更改证据范围；Q03答案点后补明确列名/索引，未改证据。

[审查记录](../../../evaluation/m4-01-evidence-review.json) 分别记录 A/B 状态、实际Chunk序号和范围、审查理由及审查者身份。自动入口验证哈希与原文回取，只输出区间候选，不生成语义标签。INSUFFICIENT 表示选取的块集合仍缺必要且可理解证据，本次按完整离线候选池审查，没有这种情况；这不表示真实Top-K永远充足。

|题号|问题要点|A语义判定/Chunk|B语义判定/Chunk|说明|
|---|---|---|---|---|
|Q01|提交异常与最终状态|COMPLETE / 1|COMPLETE / 2|均含完整限制|
|Q02|默认回滚与吞异常|COMPLETE / 1|COMPLETE / 3|均含规则和原因|
|Q03|建表列与索引|COMPLETE / 1|COMPLETE / 2|A虽有破损窗口，也有完整窗口|
|Q04|观察与性能边界|DISTRIBUTED / 0+1|DISTRIBUTED / 0+2|真实跨节依赖|
|Q05|版本和供应商|COMPLETE / 0|COMPLETE / 1|同一完整段落|
|Q06|块数相同与质量|COMPLETE / 1|COMPLETE / 2|两段证据同现|
|Q07|补邻居是否足够|COMPLETE / 2|COMPLETE / 2|A缺段首铺垫仍包含必要证据|
|Q08|RRF公式和去重|COMPLETE / 8|COMPLETE / 10|A只截断非必要JSON记录说明|
|Q09|保序配置和失败前提|DISTRIBUTED / 1+2|COMPLETE / 4|本题明确改善|
|Q10|扩容取模风险|COMPLETE / 3|COMPLETE / 6|均含完整前提和方案|
|Q11|提交与join等待|COMPLETE / 0|COMPLETE / 1|均含限制|
|Q12|超时不终止底层计算|COMPLETE / 6|COMPLETE / 9|均可独立支持答案|

Single-Chunk Evidence Completeness Rate：A **10/12=83.33%**，B **11/12=91.67%**，增加8.33个百分点，改善1题，无语义退化题。自动整段区间单块率为 A 8/12、B 11/12；Q07/Q08的完整标注段落包含不影响本题的额外文字，人工复核判定 A 仍 COMPLETE，保留这种差异而不伪造一致性。所有题使用同一Gold，12题开发样本不足以形成泛化或统计显著性结论。

## 限制与下一步

- 无真实检索、Embedding或生成；证据率代表候选池中“存在可独立回答块”，不代表召回率、排序或最终上下文质量。
- 结构边界事件以本次同一AST/有限表格规则识别，不保护未知Markdown扩展、复杂嵌套、HTML表格或图像内容。报告的0只覆盖识别范围。
- B不按Java方法切分；真实样本没有 >1200 单元。长围栏按行分片可能仍截断方法逻辑，表格分片也需要辅助表头才能理解；合成测试仅证明机械行为。
- 无Overlap降低重复量，也可能损害跨段指代；严格标题隔离产生纯标题短块，候选量增加43.10%。本次没有测量索引大小或上下文预算开销，字符量不等于Token成本。
- 人工语义标签由本次审计者复核，仍需独立人工评审；不是在线答案评分。

Q1：在8篇识别范围内，不必要的结构破坏明显减少；不能外推到所有Markdown。

Q2：人工复核的单块证据完整率从83.33%至91.67%，小幅改善1题；自动区间率不能代替该结论。

Q3：没有真实超长块或降级，但新增10个短块、Chunk数量明显增加，含纯标题块，是需要检索实验衡量的退化成本，不能直接称为可接受。

Q4：足够支持在人工确认样本及Gold后开展受控真实检索对照；不支持替换生产策略。建议固定相同问题、权限/过滤、模型、Top-K和预算，分别比较向量/BM25/融合召回与证据覆盖，关注短标题占位和跨节证据，再评估实现成本。在线执行需另行授权，本次不执行。

## 实际验证

执行 Spotless apply/check、测试编译，定向 ChunkingComparisonTests、DocumentParserTests、DocumentSplitterTests、ChunkingStrategyTests；最终共35项通过（对照/边界3、Parser19、Splitter11、Strategy2），失败/错误/跳过均为0；Git差异与新增文件空白检查通过。合成测试覆盖 Unicode/BOM/CRLF、LF、表格、完整多行列表项/引用、801–1200单元、超长代码/表格按行、超长行例外、极端长句重叠与确定性。首轮发现围栏空行错误后修复并重跑；新增长段落测试还发现中文句号误要求后接空白，已修复并通过最终定向测试。统计均取最终产物。

未执行 clean verify、数据库/ES/MinIO/Docker集成测试、在线模型或性能测试，均不在本任务验证范围。未修改生产代码；Commit SHA 见 Git 提交记录。
