# M3-02 ContextBuilder

## 实现范围

新增 rag/ContextBuilder、ContextProperties、SelectedEvidence、AssembledContext；修改 RagService、RagError，新增 ContextBuilderTests 并调整 RagApiTests 构造方式。没有修改 M2 检索、数据库、供应商客户端或冻结协议。

ContextBuilder 只接收最终 RankedChunk；按 finalRank 稳定排序（历史数据 null 排名放最后并保持输入顺序），按 chunkId 去重。重复 ID 的 KB、文档、版本、正文、位置、标题任一不同均拒绝；排名和通道评分不参与身份比较。先验证全部候选，再预算选择，冲突不会被超预算掩盖。

## 数据边界与编号

Context 是严格 JSON 数组，例如：

```json
[{"evidenceId":"E1","headingPath":"RabbitMQ Consumer ACK","content":"Consumer ACK 用于确认消费处理。"}]
```

模型可见的轻量 ModelEvidence 仅含 evidenceId、headingPath、content；内部 ID、来源位置和排名仅保存在服务端 SelectedEvidence 与 evidenceMap。正文、标题均通过 JSON 字符串转义，伪造标签、引号、换行及反斜杠不能新增结构字段。恶意指令作为普通数据保留；这不证明模型一定忽略指令。完整 Prompt Contract 留给 M3-03。

每次试选完整 Chunk 后序列化整个数组并计量，包括 ID、元数据、转义、括号和逗号。超预算跳过当前候选继续尝试；仅成功入选才占用 E 编号。一次构建同时返回文本、List.copyOf 列表及 Map.copyOf 映射，元素也是不可变 record；后续不得重新选择或编号。映射只包含实际入选证据。selectedCount 与 excludedByBudget 分别为入选数和按去重后候选统计的预算排除数。

## 预算与精度

复用现有 TokenEstimator(0)，不改变其行为：序列化文本 Unicode 码点数 + 固定开销0，再向上取整追加20%余量；usage.exact() 恒为 false。不是 Qwen 模型 Tokenizer，也不是已验证的 Token 上界或计费数量。没有新增第二套算法或调用在线 Tokenizer。

配置 assistant.rag.context.estimated-token-budget 默认4000，max-characters 默认16000（Java UTF-16 code unit），支持启动绑定并拒绝小于最小 JSON 数组开销的配置。字符硬限制与 estimated token 限制同时生效，不截断正文。测试可通过构造器注入固定参数的 TokenEstimator，例如余量0，离线验证边界。

4000 是独立证据预算，不是整个模型窗口。系统提示/消息封装、用户问题、输出需在 M3-03 的独立预算中预留，不能挪用此证据配额；初始容量规划分别预留1000、6000、2000 estimated tokens，接入实际模型时仍须核实窗口及 Prompt 后重新估算。M3-02 没有生成调用，不声称这些预留已实施精确窗口控制，也不能因 context usage <=4000 就断言真实请求不会溢出。

## 失败语义

RagService 保留完整 RetrievalResult，仅 SUCCESS 构建上下文。EMPTY_RESULT 和 FAILED 保持原协议；构建成功仍 FAILED/GENERATION_NOT_IMPLEMENTED，无答案或引用。全部候选超预算为 CONTEXT_BUDGET_EXCEEDED；重复身份冲突为 CONTEXT_DATA_INCONSISTENT；其他运行时构建异常为 CONTEXT_BUILD_FAILED。响应不携带 Throwable、正文或 Context；构建器不记录正文或异常原始消息。

## 验证与边界

ContextBuilderTests 覆盖完整选择、排名去重、冲突、序列化开销与边界、部分入选、前大后小、全部排除、连续编号、仅入选映射、不可变集合、中英文/Unicode、伪标签、恶意指令、JSON 特殊字符及 RAG 分支隔离。RagApiTests 保持 M3-01 API 契约。

最终测试结果与提交标识见下方完成记录。未调用模型，不实现 Prompt、Chat、输出解析或 CitationValidator。Architecture Spec 的 Boot 3.x 与 pom 4.1.2 差异沿用既有记录；本次按用户确认使用估算预算，是模型 Tokenizer 尚未验证的明确精度边界，不改冻结文档。

提交 SHA：本文所属的 M3-02 提交（通过 git log -- docs/engineering/m3-02-context-builder.md 查询；最终交付报告列出完整 SHA）。

## 完成记录

2026-11-11，Java 21.0.10：

- Spotless apply、check、生产及测试编译通过；git diff --check 通过。
- 定向命令：`mvn "-Dmaven.repo.local=D:/develop/repository" spotless:apply test "-Dtest=ContextBuilderTests,RagApiTests,Retrieval*Tests,KeywordRetrievalTests"`，55项全部通过（新增Context 11项、Rag API 10项，其余相关M2 34项）。首轮55项有1项 Mockito 重设异常桩写法错误，改用 doThrow 后重跑全部通过。
- 默认 `mvn "-Dmaven.repo.local=D:/develop/repository" verify`：Surefire 268项，266通过、2失败、0错误、0跳过。失败仅为 ProfileConfigurationTests.commonAndLocalDefaults、embeddingConfigurationCanBeOverriddenOffline 的默认关闭断言（实际启用）。未读取或修改 application-local.yml，未放宽断言；没有宣称全量通过。
- Surefire 失败使 Failsafe 未执行，本次无 Failsafe IT 验收结果。未另外构造隔离副本。默认测试过程使用仓库已有 Testcontainers 测试，未手动启动 Docker 基础设施；没有运行 online-model profile 或真实供应商请求。
- Surefire 中实际执行的容器测试5类共6项，均0失败/错误/跳过：DocumentLifecycleMigrationTests 1项、ImportPersistenceTests 2项、KnowledgeAssistantApplicationTests 1项、KnowledgeBasePersistenceTests 1项、MybatisCompatibilityTests 1项。这6项已计入268项，不与 Failsafe 混算。
- Maven 原始日志仅在系统临时目录 ka-m302-targeted.log、ka-m302-verify.log，不纳入提交；不提交凭据、业务正文或完整 Context 日志。

提交前审阅全部新增文件和完整 diff，仅涉及本任务9个文件；未改依赖版本、Migration、冻结文档或本地敏感配置。

## 补充审查修正（2026-11-11）

仅修改 ContextBuilder、RagService、ContextBuilderTests 和本文。ContextBuilder 使用内部轻量 ModelEvidence 序列化 evidenceId、headingPath、content；完整 SelectedEvidence 与 evidenceMap 仍保留全部真实身份及来源位置，编号规则不变。预算改为计量实际模型可见 JSON，默认预算及 TokenEstimator 不变。

RagService 两类 Context 异常各调用一次 FailureLog.error，boundary=RAG_CONTEXT。为避免任意序列化异常消息、cause、suppressed 携带正文，先构造只含固定错误分类及原故障栈位置的诊断异常，再交给 FailureLog 重建；不向日志后端传原始 Throwable。此边界有意舍弃原始消息和原因链，不改变 API 错误分类。

新增测试断言模型 JSON 精确三字段、不含内部 ID，服务端身份及 E1 映射完整；原有特殊字符/Prompt Injection 往返测试继续通过。两类异常均以真实 Logback 事件和 ThrowableProxy 渲染验证：每次恰好一条 ERROR，包含安全分类，且问题、正文、Context、cause、suppressed 哨兵均不出现。

验证命令：`mvn "-Dmaven.repo.local=D:/develop/repository" spotless:apply test "-Dtest=ContextBuilderTests,RagApiTests,FailureLogTests"`。Spotless apply/check、编译通过；32项全部通过，0失败/错误/跳过（ContextBuilderTests 14、RagApiTests 10、FailureLogTests 8）。git diff --check 通过。按本次范围未重复默认全量及容器测试，未调用模型、未读取或修改本地敏感配置。补充提交 SHA 由最终交付报告列出，也可查询本文最新 git log。
