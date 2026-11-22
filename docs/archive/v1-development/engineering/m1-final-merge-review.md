# M1-Final 最终分支审查与合并准备

## 结论

**READY TO MERGE**（限已冻结的 M1 单实例、单 Worker、本地个人知识库范围）。2026-11-10 完成静态风险审查、历史仓库卫生检查及指定回归，未发现需要本轮修复的明确阻断缺陷。建议用户审核本报告后决定合并 main；没有实际合并、推送或删除分支。

该结论不是生产安全、容量、多 Worker 或真实模型语义质量认证。既知 SQL 风险及未验证边界见下文。

## 分支事实

执行 git fetch origin 成功；开始时工作区干净，分支 feature/m1-ingestion-indexing。

| 引用 | SHA |
|---|---|
| origin/main（本地 main 相同） | 042127cee45f4d1bc88cc31b196350e838b0d758 |
| HEAD / origin/feature/m1-ingestion-indexing | 10937476a47d3b8a72a81553fbc1ef936b252492 |
| 最终应用代码基线 | 167e6bf3eee696666895f8dd1a7bf113ababa5da |
| 重构前基线 | d63abb3ab933725159dccb0148e67708ac92fb63 |

实际执行 status、branch -vv、log origin/main..HEAD、diff --stat 和 --name-status origin/main...HEAD。领先 61，落后 0；190 文件，12831 行新增、163 行删除。main 是 HEAD 祖先，merge-base --is-ancestor 返回 0；在本次 fetch 的引用快照下不存在 main 独有改动造成的冲突，不实际合并。

M1 数据模型、API、解析切分、Embedding、ES、验证、发布、恢复、故障测试和验收提交均在当前祖先链。167e6bf 后仅人工验收文档变化，无生产代码或测试变化。报告提交后领先数自然增加一项，不改变被审查代码。

未发现临时 Stub、数据库备份、原始日志或 M2 检索/RAG 实现混入。CommonMark 可行性测试及历史实验记录属于解析技术决策证据；AI/Rerank 既有基础验证不等于新增 M2 能力。

## 九个重构提交：源码等价性

实际读取 git diff d63abb3..167e6bf -- src/main/java，并核对当前相关 Repository、Worker 和 Mapper XML；不是仅采用既有文档的 PASS。范围确为九个提交、六个文件，其中五个 Java 文件和一份说明文档。

| 提交 | 实际变化及判断 |
|---|---|
| f5b9baa | Processor 提取准备、构建、验证步骤；按最终源码与前基线逐段核对 |
| 2adeafd | 保留准备阶段验证顺序；以最终净差异为等价性判断对象 |
| 247f0b1 | 发布参数、实体、已发布事实、待发布条件命名提取，事务边界未变 |
| c9d6cfb | 重试间隔共同判断提取；null、不可重试、最大次数短路等价 |
| 7c6e38d | Optional 已取出的 claimed 复用，无重新查询或状态改变 |
| 607db85 | 上传校验、对象传输、创建和补偿方法提取，异常覆盖范围保留 |
| a0b58f7 | 显式 import 与领域类型简化，全限定类名语义不变 |
| 1f297b5 | 说明文档，无运行行为改变 |
| 167e6bf | 格式及验收记录，无新增业务协议 |

### 上传

knowledge.get → 文件名/类型/大小/UTF-8 校验和有界读取 → 原始字节 SHA-256 → 同 KB 去重 → 模型/切分快照 → staging/originals 随机 Key → put/copy → PG 创建短事务，顺序保持。

外层 try/finally 仍同时覆盖对象传输和 PG 创建，staging 总会尝试清理。uploadOriginal 内 RuntimeException 仍先补偿本请求 originals 再原样上抛。createTask 的 catch 仍覆盖 transaction.execute 及 outcome；提交异常先查主库引用，再决定补偿，查询失败保留对象。DuplicateKeyException 在补偿之后查胜出任务；胜出结果或异常仍穿过 staging finally。validateAndRead 在对象操作之前，提取没有扩大/缩小存储补偿范围。dimensions 从 Integer 传入 int 的拆箱发生在已判非空之后，不增加有效输入差异。

### Worker、超时和索引顺序

Scheduler 获取 Processor 后才 recover/claim；无 Processor 不领取。scan synchronized、recover 在 claim 之前、batch 默认 1、Worker 调用顺序均保留。TaskRepository retryDelay 仅集中条件，仍在任务行锁读取后调用；随机退避函数每个符合条件的路径调用次数不变。SQL 和最大尝试次数未被重构修改。

Processor 最终超时检查顺序：入口 → 原件读取前 → 读取后 → 解析后 → Chunk 后/Embedding 前 → Embedding 内各批检查及返回后 → ES 后 → verify 前 → verify 后。ES 后与 verify 前两个原本相邻的检查均保留；没有遗漏、移动到发布提交之后或以日志替换检查。

BUILDING 才 initialize/embed/index；VERIFIED 恢复仍重新 verify；已存在发布迹象直接交发布协议确认，不重写索引。catch 仍包围相同主流程，Parse/模型/基础设施异常映射未改变。Chunk 稳定 ID、Embedding 补齐、ES create-only/409 内容核对不属于本次净修改，已结合实际服务源码和既有回归核对。

### 首次发布

@Transactional 仍在对外 publish 入口，不变为自调用事务。validateClaim → repository.lock（Document→IndexVersion→ImportTask）→ 实体归属/epoch → 完整成功事实幂等返回 → VERIFIED/无指针/RUNNING/owner → repository.publish。

三个 SQL 条件更新仍在一个事务中执行；最后 succeedTask 以数据库时间校验 owner、epoch、lease，拒绝时抛异常使此前更新回滚。发布响应不确定仍仅捕获既定事务/连接异常，先读任务，再调用原协议确认；明确业务冲突不额外确认。第二次确认失败仍向 Worker 传播，失败 SQL 拒绝覆盖 SUCCEEDED。成功返回后不追加超时检查。

结论：九提交最终净效果为等价重构，未发现输入、状态、事务或异常传播协议变化。没有分别 checkout 两基线双跑，不把静态核对表述为形式化证明。

## main 合并高风险差异

- pom：Java21/Boot4.1.2/AI2.0.2 未被本分支升级；新增 ES 客户端8.18.0、CommonMark0.32.0、MinIO8.6.0、OkHttp5.1.0、MyBatis-Plus Boot4 3.5.18、Lombok 编译处理器，均有对应实现和测试用途；无引入 MQ/新运行基础设施。
- V1 与 main 相同；V2 建核心表，V3 注释，V4 顺序重命名表/约束/索引，V5 添加 Chunk 乐观版本。无重复迁移版本、破坏性回退或 drop。没有将无外键设计误称为数据库级关联约束；服务负责归属验证。
- Mapper XML 使用 #{...} 绑定，无业务输入拼接；向量写入有 version 和 NULL 条件；发布和失败更新保留领取权条件。验证通过短 PG 快照→ES→PG 完成，网络请求不放入发布长事务。
- 默认 Worker、模型、ES、在线授权均关闭；local 默认数据库 localhost，敏感值为空；prod 必须显式提供数据库和模型地址，未静默继承本地模型公网默认地址。local 存在 DashScope 默认地址不代表自动授权调用；手动启用模型前仍需核实目标与凭据，生产安全不在本轮承诺内。
- MVC 新公共前缀 /api、统一 Result、Long 字符串序列化是有意 API 协议变更；ApiError 删除由统一响应替代，客户端应按新路径/结构使用。
- 删除 application-offline.yml 是迁移到真实数据库/新 test Profile 的配套变化；当前上下文及迁移测试已有证据，不是误删生产配置。
- Compose 与 ES 镜像构建配置相对 main 无变化；MinIO 新增 ILM 安全校验脚本及测试，限制 staging/ 两天且拒绝冲突 originals 规则。运行环境已实测该规则。
- 错误响应只输出固定安全码；RequestId 限制字符集，线程上下文 finally 清理。Worker 不保存原异常消息，ImportEvents 隔离日志后端异常。存储补偿日志会输出随机 objectKey，需受控日志访问，不含用户正文和凭据。
- Architecture 当前实际已写 Boot4.1.2；任务指引关于仍为3.x的提示已过时，此为记录差异，未修改冻结文档。单 Worker 及无在线重建与当前实现一致。

## 历史敏感信息与卫生

不仅检查当前工作区：遍历 origin/main..HEAD 的61个提交树，去重检查481个 blob；常见模型/GitHub/AWS密钥形态、私钥标记及 .env、target、日志、备份/归档/二进制产物路径扫描未发现命中。另执行现有本地 PG/MinIO 密码的历史精确匹配以及配置赋值位置检查（结果见最终补充）。不输出疑似密钥原值，不改写历史。

跟踪的是 classpath application-local.yml 非敏感默认值和 config/application-local.yml.example 占位模板；外部私人覆盖及 .env 保持忽略。原始证据与备份仍存在于仓库外 D:\develop\knowledge-assistant-local-backups，脱敏归档 manifest.json 存在。扫描属于有限规则和人工配置审阅，不能保证发现任意编码/未知格式秘密。

## 验收证据与本轮验证

| 证据 | 覆盖和实际结果 |
|---|---|
| m1-final-acceptance.md | 历史 clean verify：Surefire149+Failsafe35，均0失败/错误/跳过；这是重构前完整默认回归，不冒充最终基线全量运行 |
| m1-13c-verification.md 最终节 | C01～C12全部PASS；30项测试+28项IT连续回归，已纠正早期测试代理事务边界错误，不拼接失败批次 |
| m1-readability-refactor.md | 167e6bf 重构后154项Tests、26项指定IT，全部成功；未做完整verify及基线双跑 |
| m0-m1-local-manual-acceptance.md | 应用代码167e6bf，89a396a/8245a2e/1093747仅记录文档；Docker/Flyway/API及自动定时Worker真实HTTP全链路通过；11个1024维Chunk、PG/ES/MinIO一致，最终进程停止 |
| 本轮实际运行 | mvn spotless:check -Dtest=IngestionTaskProcessorTests,TaskExecutionTests,TaskSchedulerRecoveryTests,ImportTests,ImportObservabilityTests test：30项，0失败/错误/跳过，BUILD SUCCESS |
| 本轮 Git 检查 | git diff --check origin/main...HEAD 及工作区检查通过 |

指定回归覆盖 Processor19、执行预算3、Scheduler1，其余上传与日志7项；没有skipTests、没有online-model、未调用真实供应商。默认 Failsafe 白名单不发现的 ChunkPersistenceIT/EmbeddingPersistenceIT/性能及在线专项不能算入默认全量通过。

不重跑 clean verify 的理由：最终生产代码由重构后指定真实IT及人工全链路覆盖，随后只有文档变化；本轮额外回归通过，未发现需要补足到全量范围的代码变更。既有证据足以覆盖本次等价重构风险，不机械重复完整运行。未启动多个 Maven 进程，未修改生产代码、测试、配置或迁移。

## 剩余风险与合并建议

1. TaskMapper validOwnership 允许 FAILED 版本被领取，而 Processor 拒绝此构建状态；失配队首任务会导致扫描提早结束并可能阻塞后续任务。均为已记录的非阻断风险；本轮不改SQL/状态机。若未来出现这些数据，须单独调查，不手工伪造成功。
2. 单 Scheduler 串行不是跨 JVM fencing；多 Worker、在线重建/版本切换、强制取消 HTTP 当前不支持。
3. PG TCP 真断线、单 Bulk 响应丢失、真实语义质量、备份恢复演练、生产容量与安全未验证；故障代理测试和固定向量不能替代这些能力。
4. 合并包含有意 API 路径和错误协议变化，以及需按顺序应用的 V2～V5；main 使用方应阅读配置与迁移说明。开发库已迁移，不应回退或重新编辑迁移。
5. main 是当前 feature 祖先，合并冲突风险低；61提交/190文件使审阅面较大。本报告采用风险分层检查，不宣称穷尽所有输入和并发交错；合并前若远端生产代码变化，应重新核对差异与证据。

建议在上述 M1 边界下正式合并 main。只新增本审查报告并独立提交；用户仍掌握合并决定，不自动创建后续分支。

## 历史凭据扫描最终补充

现有本地 PG/MinIO 密码精确匹配扫描命中 .env.example；扫描首先遇到1093747提交树，但不代表该提交引入。进一步核对该文件与 origin/main 完全相同，历史由 M0 的 e402303/f4a2740 引入，匹配值具有公开示例占位标记。没有打印原值。

结论：不是 M1 分支新增真实供应商密钥泄漏；当前本地开发容器实际使用公开示例凭据，是需明确保留的非阻断安全风险。仅能用于受限本地环境，不可复用到对外部署；若需要更换，须单独规划现有数据库凭据同步，不在本轮改环境或历史。其他历史配置赋值候选已按路径/版本检查，为空值、环境变量引用或占位配置，未确认真实 API Key/Bearer Token 泄漏。扫描不构成完整安全审计。
