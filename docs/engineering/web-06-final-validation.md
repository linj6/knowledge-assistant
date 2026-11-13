# WEB-06-E 最终验收与合并准备

日期：2026-11-13。分支 feature/web-ui-v1；HEAD 42ff52a3888b2aafb6ef9a84680efc836102ab50。未 Commit/Push/Merge/Rebase。

## 状态与证据边界

- A：历史离线审查完成，本轮回归通过。
- B：Mock HTTP/DOM 自动化通过；既有文档仅记录部分用户 Chrome 观察，完整浏览器验收未关闭，不能宣称全部 Mock 浏览器场景通过。
- C：用户确认真实上传发布通过。
- D：用户确认真实检索、在线 RAG 与拒答通过；Citation API 身份和内容人工对照通过，浏览器详情交互待复验。
- E：归档、最小契约修复和离线回归完成；最终浏览器门禁待人工关闭。

以下在线事实来源为本次用户明确提供的人工本地 API 响应摘要；原始请求响应未随任务提供，本轮没有重新执行、调用供应商或启动基础设施。

## 真实联调摘要

创建测试知识库、上传 Markdown、Worker 领取、解析切分、分批 Embedding、PG Vector 写入、ES 建索引、双索引核验、READY、已发布及 activeVersionId 更新均已确认。Worker 默认关闭造成 RECEIVED 停留、异常包装遮蔽原因、批次16超过 text-embedding-v4 上限10均已修复并有相应验证，不是当前未修复问题。

测试知识库2《停车中台核心业务分析》：

| 问题 | BM25 | VECTOR | HYBRID_RRF | HYBRID_RERANK |
|---|---|---|---|---|
| Q1 停车缴费下单限流组件及 QPS（Guava RateLimiter，250 QPS） | 正确证据1 | 正确证据3 | 正确证据1 | 未提供 |
| Q2 优惠失败权益回滚，完整 Chunk 尾号1492 | 1 | 4 | 2 | 1 |

Q2 尾号1491为下单流程及 rollbackAll() 入口，1492为完整规则。RERANK 320ms、候选16、1492 rerankScore约0.9482、SUCCESS、degraded=false。Rerank 将RRF第二提升为第一；Q1未证明RRF优于BM25，Q2也不能证明Rerank普遍优于BM25。

Q2 RAG：ANSWERED、HYBRID_RRF、degraded=false、retrievalStatus=SUCCESS；input/output/totalTokens=1547/79/1626。回答正确涵盖 rollbackAll()、停车券解冻及恢复次数、会员权益恢复次数、积分回滚冻结恢复额度、消费减免恢复次数。E1对应1491，E2对应1492；事实与引用支持性已经人工对照。

Q3“HNSW 参数 M 和 efConstruction”返回 INSUFFICIENT_EVIDENCE，说明文档未提供，citations=[]，retrievalStatus=SUCCESS、degraded=false，input/outputTokens=1720/37。正确拒答，无编造或不恰当引用。

## 本轮代码检查与修复

对照既有A/B审查、前端知识库/上传/Citation流程与DocumentSummaryResponse、ImportTaskResponse契约。任务DTO不提供indexVersionId，前端却固定展示“未提供”；移除该无契约字段，保留文档activeVersionId，避免误认为发布缺版本。同步修正测试夹具不再虚构任务字段。该问题P2，已修复。

headingPath在Citation和检索中直接以字符串展示，JSON标题路径可读性差，列为P3后续改进。窄屏溢出未通过真实视觉复验，不能推断通过。刷新重新加载知识库与文档，不持久化问答/任务；切库使用代次/取消及状态清空，已有自动化覆盖。本轮未发现明确P0/P1缺陷，但浏览器验证缺口仍存在。

## 本轮实际验证

- JavaScript全量192项通过，0失败/取消/跳过；修复后重跑通过。首次修复后旧测试夹具仍要求不存在字段导致1项失败，修正契约夹具后通过。
- 八个生产JS模块语法检查通过（移除字段前）；最终全量模块加载/行为回归通过。
- Java离线365项，0失败/错误/跳过，BUILD SUCCESS；包含WebStaticResourcesTests真实Servlet HTTP/MIME检查。
- 命令：mvn '-Dtest=*Tests,!DocumentLifecycleMigrationTests,!ImportPersistenceTests,!KnowledgeBasePersistenceTests,!KnowledgeAssistantApplicationTests,!MybatisCompatibilityTests,!DocumentQueryPersistenceTests' test spotless:check。
- Spotless通过；该Maven生命周期包含编译及测试编译。未执行clean verify、Testcontainers/其他IT、在线模型测试。排除六个依赖容器的Tests，无新增集成测试。
- git diff --check通过。测试日志保存在本机临时目录ka-web06-js.log及ka-web06-java.log，不属于合并文件。

## Chrome人工门禁（尚未执行）

本轮未操作真实Chrome，不提供截图或Console通过结论。复用已有Q2结果，避免重复付费请求；若刷新导致结果丢失，需另行授权在线重问。

1. 在现有Q2回答点击E1/E2，核对文档、版本、1491/1492正文与检索结果一致；关闭、重开、Esc正常。
2. 切换知识库，确认旧问答、检索和Citation不冒充新库数据。
3. 刷新后知识库及文档列表正常（可重新选库），Console无阻断错误。
4. Chrome 390px宽度检查三页与Citation无阻断操作的溢出。

记录执行人、日期、浏览器版本、结果及脱敏截图；未通过则仅修复明确缺陷。

## Git与合并范围

相对本地main为23个提交，89文件、5063新增/172删除（本轮修改前的已提交差异）。包含Web UI、只读API、测试文档以及真实联调产生的Worker/日志/Embedding修复；不是仅静态前端变更。未查询远端main是否更新。

开始已有application-local.yml修改、两份web-06-c未跟踪文档及infra/web06c/，全部保留且未纳入本轮修改。application-local.yml已被Git跟踪，切勿git add .；建议用户单独检查私有配置，另行决定取消跟踪策略。本轮不展示配置原值，不作“整个历史无密钥”的保证。两份预检/隔离记录建议独立历史归档；infra/web06c预算网关/容器属于已结束实验，不是正式运行依赖，等待用户决定单独归档。

README已有正式入口http://localhost:8080/api/index.html及Hash说明，无需本轮更新。正式静态模块无infra/web06c依赖；配置应由用户本地环境提供，不能把未提交本地配置作为可移植运行保证。已提交差异列表未见新增target或构建生成物；本轮没有生成仓库内验收附件。

本轮文件：本文、static/js/knowledge.js、src/test/js/web-knowledge.test.mjs。
READY TO COMMIT：是，仅限以上三个文件，需用户审阅并自行提交；无新增Commit SHA。
READY TO MERGE：否。关键Citation真实浏览器、切库/刷新/Console与窄屏门禁尚未关闭；提交范围还需排除私有配置及隔离实验。本结论不以README系统性完善为门禁。
