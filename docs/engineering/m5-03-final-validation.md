# M5-03 最终工程验收

**工程验收：ENGINEERING READY**

**公开发布：PUBLIC NOT READY**

## 基线、环境与范围

- 分支为 `feature/m5-public-readiness`，最终在线 E2E 和本次归档时的 Git HEAD 为 `d439025ddf8c7c20fa1c18272904f33db4f34210`。在线验收复用了已有本地运行配置，工作区已有的本地配置修改保持原样。
- 在线 E2E 于 2026-11-23 在 Windows 上执行。IntelliJ IDEA 中已运行应用，浏览器访问 `http://127.0.0.1:8080/api/index.html`。项目基线为 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2。Docker 中已运行 PostgreSQL/pgvector、Redis、Elasticsearch 和 MinIO。浏览器使用 Playwright 驱动 Microsoft Edge。
- 两轮验收覆盖自动化回归、隔离环境验证和实际在线业务流程。在线业务使用本轮测试账号、独立知识库、一个用于切换的空知识库和两篇短文档，覆盖从注册到退出的 V1 流程。归档采用已有验收结果。

## 自动化回归与隔离环境

| 验证 | 结果 | 证据范围 |
|---|---|---|
| Java | PASS | 535 项通过，3 项条件跳过。 |
| JavaScript | PASS | 212 项通过。 |
| Python | PASS | 6 项通过。 |
| M4 冻结评估 | PASS | 使用冻结候选、Gold 和标签完成 OFFLINE 复算，复算指纹与冻结结果一致。该过程无在线模型调用。参见[评估说明](../evaluation.md)及[冻结结果](m4-b03-frozen-ranking-evaluation.json)。 |

自动化结果沿用第一轮隔离环境验收记录。真实 HTTP 证据来自第二轮运行中的服务，包含浏览器请求及第二账号会话下直接访问资源的权限检查。真实浏览器证据来自页面交互和 DOM 状态。在线模型证据来自真实发布产物、检索诊断及问答终态。

## 真实浏览器、HTTP 与在线模型

| 场景 | 结果 | 证据 |
|---|---|---|
| 注册、登录和知识库切换 | PASS | 浏览器注册和登录均返回 HTTP `200`。刷新后 `/auth/me` 返回 `200`，页面显示当前用户。新建知识库出现在选择器中，切换后页面状态同步更新。 |
| Markdown 与 TXT 上传、导入和发布 | PASS | 页面上传 `redis-consistency.md` 和 `rabbitmq-ack.txt`，两次请求均返回 `202`。导入任务 `2124793976222683137`、`2124794017117147137` 经 Worker 处理到达 `READY`，文档列表均显示已发布。MinIO 查到两个原始对象的存储记录，PostgreSQL 中两个活动索引版本均为 `PUBLISHED`，各持久化一个由 `text-embedding-v4` 生成的 1024 维向量。真实 BM25 命中验证 Elasticsearch 检索链路。 |
| BM25 | PASS | HTTP `200`，请求与实际策略均为 `BM25`，RabbitMQ 片段排名第 1。 |
| VECTOR | PASS | HTTP `200`，请求与实际策略均为 `VECTOR`，RabbitMQ 片段排名第 1，也返回 Redis Markdown 片段及标题路径。 |
| HYBRID_RRF | PASS | HTTP `200`，请求与实际策略均为 `HYBRID_RRF`，RabbitMQ 片段排名第 1，响应包含融合分数。 |
| HYBRID_RERANK | PASS | HTTP `200`，请求与实际策略均为 `HYBRID_RERANK`，RabbitMQ 片段排名第 1，`qwen3-rerank` 重排分数约为 `0.95145`。请求 ID 为 `aa8e40c1-e33a-4e15-a069-42d4dc2e0c3c`。 |
| RAG 和多轮问答 | PASS | `qwen-plus` 两轮均到达 `ANSWERED`。首轮解释 prefetch 限制未确认投递数，追问延续 RabbitMQ 语境，解释自动 ACK 后处理前崩溃可能导致消息丢失。两轮引用均归属本轮 RabbitMQ 文档。 |
| Citation | PASS | 点击 `E1` 后证据接口返回 `200`。浏览器展示正确文件、正文及来源区间 `[0, 554)`。TXT 小标题保留在正文，结构化标题路径为空。关闭后焦点回到引用触发按钮。请求 ID 为 `c5384e72-e79f-417d-95c6-9e0a4a2a9888`。 |
| 历史恢复与输入状态 | PASS | 刷新恢复原会话及消息，当前会话为 `aria-current=true`。新会话为空，切回恢复历史。切换到空白知识库后未显示原历史，切回后恢复。未发送文字切库后清空。 |
| 取消生成 | PASS | 第三轮仅提交一次。取消接口返回 HTTP `200` 和 `CANCEL_REQUESTED`，轮询得到 `CANCELLED`，答案为空，页面显示已取消。终态请求 ID 为 `3e360a3e-0534-4925-b4e1-f5da74a65283`。 |
| 多用户隔离 | PASS | 第二账号登录后，知识库列表为 HTTP `200` 和空数组。使用该会话通过真实 HTTP 访问首账号的文档列表、导入任务、会话列表与详情、消息及单轮详情，均返回 `404`。 |
| 页面反馈及退出 | PASS | 实际操作知识库、检索和问答导航。页面区分用户和助手消息，并显示上传、导入、检索、生成和取消反馈。完成退出并回到登录表单。 |

四种检索响应均报告 `SUCCESS` 和 `degraded=false`。浏览器渲染了检索结果，命中片段的文档与知识库 ID 对应本轮上传数据。

## 覆盖缺口与调用统计

- **NOT TESTED**。Citation 已打开并检查 554 字符的正文，未执行长正文滚动手势并断言滚动位置变化。
- **NOT TESTED**。未主动触发登录、导入或问答失败，也未验证失败恢复及提交结果未知时的草稿重试。
- **NOT TESTED**。供应商侧精确请求计数未取得。浏览器提交 2 次上传、4 次检索和 3 轮问答，其中两轮回答、一轮取消。按两个文档向量化、三次显式向量检索、两次成功回答和一次显式重排，可归因至少 8 次模型调用。内部检索、重试和取消轮是否已发出上游请求缺少独立计数。未发起批量评估或压力测试。

## 最终结论

**ENGINEERING READY**。两轮验收期间未发现新的已确认产品缺陷，真实业务验证覆盖了文档发布、四策略检索、两轮有引用问答、取消及账号隔离。

**PUBLIC NOT READY**。MinIO 镜像公开再分发合规仍是独立的发布阻断项。参见 [MinIO 镜像交付说明](../../infra/minio/README.md)。
