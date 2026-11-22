# M2-01 发布快照与统一检索协议

## 基线与审查

分支 feature/m2-retrieval；起始 HEAD c5a61eb97d544c1a3507711d2fbf4188b4fb5c10，工作区干净，本地与上轮同步的远端一致。本轮不重复 fetch，不 Push/Merge/Rebase。

已审阅 Product Spec、Architecture Spec、Implementation Plan、configuration-baseline、m0-m1-architecture-and-flows、m1-final-acceptance、m0-m1-local-manual-acceptance、m1-final-merge-review、codex-task-template、AGENTS.md 和 code-style；核对 Document/IndexVersion/DocumentChunk/KnowledgeBase、上传 Service/Repository、KB Repository、首次发布 Service/Repository/Mapper SQL、V1～V5 迁移、错误协议、RequestId 及上传/发布持久化测试基础设施。

差异：AGENTS.md 关于 Architecture 仍为 Boot 3.x 的提示已过时，实际 Spec 和 pom 均为 4.1.2。Spec 使用概念表名 index_version/import_task，V4 已重命名为 document_index_version/document_import_task。配置基线历史描述将容量安排于 M1，实际此前尚未实现；本任务补齐。Spec 允许未来显式降级，本任务按授权严格禁止降级。不修改冻结文档和既有迁移。

## 实际模型与发布快照

PG 为发布权威；无外键。document 属于 KB，document_index_version 通过 document_id 属于文档；版本没有独立 KB 字段。document_chunk 保存 KB/文档/版本归属、Unicode 码点来源区间、正文、可空 heading_path、1024 维向量和记录乐观版本。document_import_task 与文档/版本绑定。active_version_id 唯一赋予可见性，状态仅用于诊断。

核心 SQL（独立 MyBatis XML，参数绑定）：

```sql
SELECT k.id AS knowledge_base_id,d.id AS document_id,d.active_version_id,
       v.id AS version_id,v.document_id AS version_document_id
FROM knowledge_base k
LEFT JOIN document d ON d.knowledge_base_id=k.id AND d.active_version_id IS NOT NULL
LEFT JOIN document_index_version v ON v.id=d.active_version_id
WHERE k.id=#{kbId}
ORDER BY d.id
```

单条 SELECT 使用 PostgreSQL 单语句 MVCC 视图。KB 不存在返回零行，抛安全业务错误；存在而无发布文档返回 KB 占位行，形成合法空快照。LEFT JOIN 保留悬空和错误归属指针，Repository 校验版本存在且 document_id 与目标文档一致，异常立即失败，不跳过。SQL 异常继续作为失败传播，不变为空列表。不依据版本状态授予可见性。

快照按 Document ID 排序，集合不可变；SHA-256 输入为大端 64 位 KB、文档数及依次排列的 Document/Version ID。构造器复核 hash 和文档唯一性，避免外部构造不一致快照。版本 terms 集合去重、排序、不可变。

PublishedVersionSnapshotService.read 在未来编排入口每请求调用一次；将返回值放入 RetrievalContext，并传给 PG/ES 两通道，通道不能自行刷新快照。当前不实现编排，因此一次读取的调用约束由 M2-06 接线与测试落实。读取不持有跨网络事务；V1 已发布内容不可变使旧快照继续有效。

## 容量与上传事务

固定每 KB 最多 1000 条 document，包含所有状态，不提供删除。创建事务先 SELECT knowledge_base FOR UPDATE，锁内重新检查内容摘要；重复则复用已有任务，再对新内容统计全部文档并裁决容量，最后创建 Document/Version/Task。锁只覆盖目标 KB；READ COMMITTED 下等待 KB 锁后下一条计数语句可见先前创建者的提交。不同 KB 不共享该行锁。

锁序为 KB→新建 Document/Version/Task；既有发布为 Document→Version→Task，发布不获取 KB 锁，不形成反向等待。无全局锁、分布式锁或状态表。数据库同 KB 摘要唯一约束及原 DuplicateKeyException 胜出查询仍保留。

不做上传前容量拒绝，避免满库重复请求被误拒绝，也保留 M1 校验顺序。最终裁决在 PG 创建事务内。容量错误固定 40010/知识库文档数量已达上限，沿用领域错误默认 HTTP 400。对象上传/复制仍在事务外；容量异常走原 catch 的 originals 引用复核与补偿，staging 仍由 finally 清理。锁内去重后清理本请求未引用的 originals；提交不确定或引用查询失败仍优先保留对象。首次发布协议完全未修改。

## 统一领域协议

- RetrievalStrategy：VECTOR/BM25/HYBRID_RRF/HYBRID_RERANK，Java Enum 本身无重复枚举值，未知字符串不能 valueOf。
- RetrievalParameters：默认 20/20/60/20/5，上限 100/100/1000/100/100，全部为正数。validate(strategy) 仅校验选中策略所需候选池关系；融合池不得超过两路总和仅对混合策略适用。
- RetrievalContext：requestId、query、strategy、parameters、不可变发布 snapshot；query 非空且校验选中策略参数。
- RankedChunk：四类 ID、正文、来源区间、可空 headingPath、vectorRank/distance、bm25Rank/score、rrfScore、rerankScore、finalRank。未执行通道使用 null；rank 从 1 开始，通道排名/分数成对存在，分数必须有限，真实零分允许存在。
- RetrievalDiagnostics：requested/actual strategy、degraded、status、snapshotHash、参数、各阶段 Phase/耗时/数量/候选 ID/rank/score，以及固定 Failure 分类。集合不可变，阶段不能重复，数量须与候选明细一致。
- SUCCESS/EMPTY_RESULT 的 actual=requested、无错误且有 hash；FAILED 的 actual=null 且必须携带安全分类；degraded 一律 false。失败可保留已执行阶段诊断，但 RetrievalResult 不允许返回部分成功候选。SUCCESS 必须非空，EMPTY_RESULT 和 FAILED 必须空。

不新增 API DTO、Controller、检索查询、融合算法、重排编排或诊断表，不建立工厂/注册中心。

## ES terms 与后续责任

PublishedVersionTerms.validate 先去重再校验最多 2000 个唯一 Version ID，越界明确失败，不截断。1000 文档容量不是替代 terms 校验的理由。M2-03 必须读取实际目标索引 index.max_terms_count，以两者较小值为有效上限，测试较低配置和 Top-K 前过滤。本任务无 ES 设置或搜索网络请求。

## 测试与证据

新增 RetrievalFoundationTests（协议、参数、不可变性、hash、terms）和 RetrievalFoundationIT（真实 PG）。IT 正常发布使用生产 IndexPublishService；悬空指针/错误归属及不可用表为显式异常注入。容量准备使用 generate_series 批量种子，竞争最后名额执行真实 DocumentImportService.receive 和 PG 事务，不 Mock Repository。容量测试使用内存对象存储；真实 MinIO 由 StorageImportIT 回归覆盖，不能混称为新容量场景的真实 MinIO 测试。

执行结果在收尾节记录。日志位于本地忽略的 target/m2-01-tests.log；不提交运行日志。镜像使用已有固定 PG digest、预置 MinIO 和 Ryuk，禁止拉取，不启动 Compose，不调用在线模型。

## 限制与 M2-02 入口

M2-02 可复用 PublishedVersionSnapshotService.read、RetrievalContext、快照 documents、RankedChunk 和参数 validate。精确检索必须在排序/LIMIT 前限定同一 snapshot 的 KB/Document/Version 映射，不能只按版本状态或在 Top-K 后过滤。Query Embedding、pgvector 查询及其模型兼容校验仍待 M2-02。

未验证真实模型语义、ES 目标索引设置、真实 PG TCP 中断、生产容量性能、多 Worker；当前只保证经统一上传入口的容量裁决，直接维护 SQL 绕过业务锁不受应用保护。没有新增跨存储长事务或迁移。

## 收尾验证结果（2026-11-10）

实际命令：

```powershell
mvn spotless:apply spotless:check -DskipTests test-compile
mvn spotless:apply spotless:check '-Dtest=RetrievalFoundationTests,RetrievalFoundationIT,ImportTests,ImportPersistenceTests,ImportApiTests,ImportObservabilityTests,IndexPublishIT,StorageImportIT' test
git diff --check
git diff --cached --check
```

首次格式化遇到新增文件 GBK 编码错误，转为 UTF-8 后修复，编译成功。指定回归在逐步审查修改后共运行三轮；最终以 target/m2-01-verified-tests.log 为准，18:23:59 BUILD SUCCESS，2分02秒；不累加多轮数量。Spotless apply/check、生产及测试编译通过。

| 测试类 | 通过 | 失败/错误/跳过 |
| --- | ---: | --- |
| RetrievalFoundationTests | 3 | 0/0/0 |
| RetrievalFoundationIT | 2 | 0/0/0 |
| ImportTests | 4 | 0/0/0 |
| ImportPersistenceTests | 2 | 0/0/0 |
| ImportApiTests | 1 | 0/0/0 |
| ImportObservabilityTests | 3 | 0/0/0 |
| IndexPublishIT | 1 | 0/0/0 |
| StorageImportIT | 1 | 0/0/0 |
| 合计 | 17 | 0/0/0 |

实际使用隔离 Testcontainers PostgreSQL 17.6/pgvector 0.8.0，以及 StorageImportIT 的真实 MinIO 预置镜像；不使用开发数据库或 Compose 卷，不拉取未知镜像。StorageImportIT 主动停止 PG 的故障场景会产生既有 Hikari 已关闭连接警告，不代表新增测试失败，也不等同真实 TCP 断线验证。

最终审阅生产代码和 SQL、测试与文档完整差异，确认没有修改首次发布事务/既有迁移/补偿方法；成功事件保持在创建事务提交后记录。新文件无通配符 import 或 var。Git 工作区和暂存差异检查通过。未运行 clean verify、在线模型或 ES 网络测试，原因分别为限定影响范围、无授权及 M2-03 职责。

结论：PASS（M2-01 及上述指定回归范围），可进入 M2-02 精确向量检索。后续仍须落实每请求读取一次快照及两通道复用，不将本任务协议测试冒充完整检索编排验收。本次 Commit SHA 由 Git 提交记录及任务最终回复提供，避免文档自引用提交哈希。
