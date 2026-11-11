# Web UI V1 API 盘点与工程方案

- 状态：当前代码事实与待实施契约分开记录；两个新增 API 尚不存在。
- 核查基线：2026-11-11，feature/web-ui-v1；HEAD 与 main 均为 78105a27123d9d6fd8357e3393b0ea02cb6a7f8d，工作区初始干净，RAG 重构已包含。
- 关联：[产品设计](../product/web-ui-v1-design.md)、[架构](../architecture/architecture.md)、[代码规范](code-style.md)、[原实施计划](../plans/implementation-plan.md)、[RAG 重构](rag-package-refactoring.md)。

## 1. 当前 API 能力矩阵（代码事实）

下表 Endpoint 基于默认部署无 context-path。application.yml 的 spring.mvc.servlet.path=/api 与 Controller /v1 组合；不可漏掉 /api 或在 Controller 重复增加它。

| Controller | Method | Controller Mapping | 实际 Endpoint | 请求 | Result.data | 用途/现状 |
|---|---|---|---|---|---|---|
| KnowledgeBaseController | POST | /v1/knowledge-bases | /api/v1/knowledge-bases | CreateKnowledgeBaseRequest | KnowledgeBaseResponse | 已实现，201，含 Location；创建 |
| KnowledgeBaseController | GET | /v1/knowledge-bases | /api/v1/knowledge-bases | 无 | List<KnowledgeBaseResponse> | 已实现，200；选择列表 |
| KnowledgeBaseController | GET | /v1/knowledge-bases/{id} | /api/v1/knowledge-bases/{id} | 正 Long 路径参数 | KnowledgeBaseResponse | 已实现，200；详情 |
| DocumentImportController | POST | /v1/knowledge-bases/{kbId}/documents:import | /api/v1/knowledge-bases/{kbId}/documents:import | multipart file，恰好一个 | ImportTaskResponse | 已实现，新建202、去重200 |
| DocumentImportController | GET | /v1/import-tasks/{taskId} | /api/v1/import-tasks/{taskId} | 正 Long 路径参数 | ImportTaskResponse | 已实现，200；轮询 |
| RetrievalController | POST | /v1/retrieval/search | /api/v1/retrieval/search | RetrievalSearchRequest | RetrievalSearchResponse | 已实现，200；多策略比较 |
| RagController | POST | /v1/chat/answers | /api/v1/chat/answers | RagAnswerRequest | RagAnswer | 已实现，200；单轮问答 |

### DTO 字段与验证

- CreateKnowledgeBaseRequest：name 去首尾空白、非空、最长100；description 去首尾空白、最长1000。KnowledgeBaseResponse：id、name、description、createdAt。
- ImportTaskResponse：importTaskId、documentId、status、createdAt、updatedAt、failureCode；不含 indexVersionId、发布状态或进度百分比。FAILED 的 failureCode 固定 IMPORT_FAILED，不暴露库中自由文本错误。
- RetrievalSearchRequest：knowledgeBaseId 正值、query 非空白、strategies 一至四个且服务层拒绝重复，parameters 可省略；提供 parameters 时五个字段均必填。
- RetrievalParameters：vectorTopK、bm25TopK、fusionTopK、finalTopK 范围1～100，rrfK 1～1000；默认20、20、60、20、5（字段顺序 vectorTopK/bm25TopK/rrfK/fusionTopK/finalTopK）。finalTopK 不超过该策略候选池，混合 fusionTopK 不超过两通道 TopK 之和。
- RetrievalSearchResponse：results；每项 RetrievalResult 为 chunks、diagnostics。RankedChunk 包含 chunkId、documentId、indexVersionId、knowledgeBaseId、content、sourceStart、sourceEnd、headingPath、vectorRank、vectorDistance、bm25Rank、bm25Score、rrfScore、rerankScore、finalRank；没有 filename。
- RetrievalDiagnostics：requestedStrategy、actualStrategy、degraded、status、snapshotHash、parameters、stages、error；stage 为 phase、elapsedMillis、candidateCount、candidates（chunkId/rank/score）。状态 SUCCESS / EMPTY_RESULT / FAILED；当前不降级，degraded=false，失败 actualStrategy=null。失败码含 SNAPSHOT_READ_FAILED、SNAPSHOT_INVALID、TERMS_LIMIT_EXCEEDED、EMBEDDING_FAILED、VECTOR_FAILED、BM25_FAILED、FUSION_FAILED、RERANK_FAILED、INTERNAL_ERROR。
- RagAnswerRequest：knowledgeBaseId、question（非空白，最长4000 Java 字符）、strategy、retrievalParameters；默认 HYBRID_RRF 和默认检索参数，不含 history/sessionId。
- RagAnswer：status、answer、citations、requestedStrategy、actualStrategy、degraded、error、diagnostics。diagnostics 为 retrievalStatus、retrievalError、snapshotHash、usage；不能假定包含检索完整 stages。RagCitation 为 evidenceId、chunkId、documentId、indexVersionId、sourceStart、sourceEnd，没有正文或文件名。

### Service / Repository 核查

调用遵循 Controller → Service → Repository → Mapper。KnowledgeBaseService/Repository 负责创建、列表与存在性；DocumentImportService/Repository 校验文件、同库内容哈希去重、容量及事务创建文档/版本/任务。当前去重选择按 createdAt、id 升序的首个任务，并非最新任务。单库文档上限1000。

RetrievalService 读取统一 PublishedVersionSnapshot，复用 Query Embedding 和通道结果；混合失败不隐式降级。RagService 编排检索、上下文、生成与引用校验。IndexPublishRepository/Mapper 在事务内更新 Document.activeVersionId、版本 PUBLISHED/publishedAt 和任务 SUCCEEDED。只读扩展不能改变这些流程。

## 2. 统一协议与配置事实

Result<T> 固定 code、message、data、requestId；成功 code=0、message=成功。ApiExceptionHandler 将参数错误映射400/40000，知识库不存在404/40401，资源不存在404/40400，方法405/40500，媒体类型415/41500，内部异常500/50000；领域自定义业务码默认 HTTP400。框架异常保留其 HTTP 状态，业务码可能统一为40000，不能通过业务码反推全部 HTTP 状态。

HTTP 成功且 code=0 仍可能是 RAG FAILED 或某策略 FAILED。分三层判断：HTTP、Result、领域状态。非 JSON 网关或容器错误也需兜底，不保证所有外层响应都有 Envelope。

RequestIdFilter 接受 X-Request-Id 匹配 [A-Za-z0-9_-]{1,64}，否则生成 UUID；响应头回传，与 Result.requestId 关联，结束清理 MDC。客户端保留追踪标识但不记录敏感正文。

ApiJacksonConfiguration 使用 Jackson 3，将 Long/long 全部序列化为十进制字符串，包括非 ID 的 elapsedMillis；Integer code 保持数值。ID 禁止 Number/parseInt 转换或算术。

上传使用 FormData.append('file', file)，不手写 Content-Type boundary。仅 .md/.txt（不区分大小写）、合法 UTF-8、非空；文件名最长255且拒绝路径分隔符与控制字符。业务上限默认2097152字节，可配置降低；multipart 单文件2MB、请求3MB。扩展名/MIME 的前端校验仅作提示，后端为准。

当前未提供用户鉴权与完整访问控制系统；面向单用户本地使用，不可据此宣称可公开部署。无用户身份不代表可跳过 KB/文档/版本/Chunk 归属校验。默认 worker、Elasticsearch、模型及 rerank 开关关闭；真实可用性取决于运行配置和基础设施，本次未启动或验证。不要回显 local 配置的敏感值。

### 当前接口示例（结构示例，非运行结果）

POST /api/v1/knowledge-bases：

```json
{"name":"技术学习","description":"个人资料"}
```

201 响应：

```json
{"code":0,"message":"成功","data":{"id":"1001","name":"技术学习","description":"个人资料","createdAt":"2026-11-11T00:00:00Z"},"requestId":"example-1"}
```

上传 POST /api/v1/knowledge-bases/1001/documents:import，multipart 字段 file 为 notes.md；202 示例：

```json
{"code":0,"message":"成功","data":{"importTaskId":"3001","documentId":"2001","status":"RECEIVED","createdAt":"2026-11-11T00:00:00Z","updatedAt":"2026-11-11T00:00:00Z","failureCode":null},"requestId":"example-2"}
```

GET /api/v1/import-tasks/3001 返回同结构，状态映射 PENDING→RECEIVED、RUNNING→PROCESSING、SUCCEEDED→READY、FAILED→FAILED。上传接收与任务完成、发布可见性分开展示。

POST /api/v1/retrieval/search：

```json
{"knowledgeBaseId":"1001","query":"什么是事务？","strategies":["VECTOR","BM25","HYBRID_RRF","HYBRID_RERANK"]}
```

响应 results 每策略一项，顺序对应请求；空结果单策略示例：

```json
{"code":0,"message":"成功","data":{"results":[{"chunks":[],"diagnostics":{"requestedStrategy":"BM25","actualStrategy":"BM25","degraded":false,"status":"EMPTY_RESULT","snapshotHash":"example-hash","parameters":{"vectorTopK":20,"bm25TopK":20,"rrfK":60,"fusionTopK":20,"finalTopK":5},"stages":[],"error":null}}]},"requestId":"example-3"}
```

POST /api/v1/chat/answers：

```json
{"knowledgeBaseId":"1001","question":"什么是事务？","strategy":"HYBRID_RRF"}
```

ANSWERED 响应结构示例：

```json
{"code":0,"message":"成功","data":{"status":"ANSWERED","answer":"事务是一组操作。[E1]","citations":[{"evidenceId":"E1","chunkId":"5001","documentId":"2001","indexVersionId":"4001","sourceStart":0,"sourceEnd":80}],"requestedStrategy":"HYBRID_RRF","actualStrategy":"HYBRID_RRF","degraded":false,"error":null,"diagnostics":{"retrievalStatus":"SUCCESS","retrievalError":null,"snapshotHash":"example-hash","usage":null}},"requestId":"example-4"}
```

错误示例：HTTP404，{"code":40401,"message":"知识库不存在","data":null,"requestId":"example-5"}。以上示例不表示已调用任何服务。

## 3. 新增只读 API 契约（待实施）

### API 1：文档分页列表

GET /api/v1/knowledge-bases/{kbId}/documents?page=1&pageSize=20。Controller Mapping 使用 /v1 前缀，沿用 Result。

方案规定 page 默认1、pageSize 默认20，正整数，pageSize 最大100；非法值400/40000。先确认 KB 存在，缺失404/40401；超出页数返回空 items，保留 total。按 document.createdAt DESC、id DESC 稳定排序；总数按文档计数，不能因连接多个任务或版本重复计数。计数与页面建议使用同一只读快照，普通翻页期间新增文档仍可能导致偏移变化。

```json
{"code":0,"message":"成功","data":{"items":[{"documentId":"2001","filename":"notes.md","createdAt":"2026-11-11T00:00:00Z","publicationStatus":"PUBLISHED","activeVersionId":"4001","publishedAt":"2026-11-11T00:01:00Z","importTask":{"importTaskId":"3001","indexVersionId":"4001","status":"READY","createdAt":"2026-11-11T00:00:00Z","updatedAt":"2026-11-11T00:01:00Z","failureCode":null}}],"page":1,"pageSize":20,"total":"1"},"requestId":"example-list"}
```

新增摘要包含 indexVersionId 以明确任务对应版本；不是修改既有 ImportTaskResponse。importTask 选该文档按 createdAt DESC、id DESC 的最新任务，可为 null；其状态不决定发布事实，亦不保证属于活动版本。重复上传仍按既有接口语义返回首个任务，不改变旧逻辑。

Document、IndexVersion、ImportTask 通过 documentId、indexVersionId 关联；模型允许文档关联多个版本和任务，不以当前导入一次创建三条记录推断永久一对一。Document.activeVersionId 为 null 时 UNPUBLISHED，publishedAt=null；非 null 须验证版本存在、documentId 一致、状态 PUBLISHED、publishedAt 非空，才 PUBLISHED。异常指针返回安全500/50000并记录内部诊断，不能伪装未发布。

publishedAt 可以可靠读取活动 IndexVersion.publishedAt，发布 SQL 在事务中写入，不能使用任务 updatedAt 或文档 createdAt 代替。activeVersionId 保持原指针；total 采用 Long 故输出字符串。

### API 2：指定引用证据

GET /api/v1/knowledge-bases/{kbId}/documents/{documentId}/versions/{versionId}/chunks/{chunkId}。

```json
{"code":0,"message":"成功","data":{"chunkId":"5001","documentId":"2001","indexVersionId":"4001","filename":"notes.md","headingPath":"事务","content":"事务是一组操作。","sourceStart":0,"sourceEnd":80},"requestId":"example-evidence"}
```

查询顺序与强制校验：KB 存在；Document.knowledgeBaseId 等于 kbId；IndexVersion.documentId 等于 documentId；Chunk.documentId/indexVersionId/knowledgeBaseId 全部匹配路径；Document.activeVersionId 等于 versionId，版本 PUBLISHED 且 publishedAt 有效；sourceStart >= 0、sourceEnd > sourceStart。参数非正或无法解析400/40000；KB 缺失404/40401；资源缺失、归属不匹配、非活动版本统一404/40400；存储内部完整性损坏返回500/50000，不泄露其他归属实体。

区间单位是原始文本 Unicode 码点，左闭右开，不是 JavaScript UTF-16 下标，也不要求等于渲染后 Chunk 内容长度。当前查询模型没有原文总码点数，不能声称查询时验证了终点不超过原文总长；该上界依赖既有导入验证，不新增 MinIO 读取或字段。前端展示 Chunk 正文与区间，不据此直接 substring 高亮原文。

从 PG 权威记录读取正文，不直接信任 ES 或客户端附带内容；一致性读取避免校验与读取分离。只允许当前活动发布版本。当前 V1 首次发布后活动指针不可修改；未来若开放版本切换，旧引用可能404，需另行设计历史可见性或引用快照，不能默默改查新版本。

## 4. 后端边界与兼容性

仅增加上述两个只读查询，沿用 Controller → Service → Repository → Mapper。不得增加数据库表、Flyway Migration、索引、模型调用、删除、版本管理、任务列表、手动重试、WebSocket/SSE；不修改 M1/M2/M3 业务逻辑和旧 DTO。分页与引用查询均不得触发索引构建或发布。

## 5. 前端技术架构（已确定、待实施）

HTML5、CSS3、原生 JavaScript、ES Modules、Fetch API、Spring Boot 静态资源托管；零构建、零 npm 依赖。

| static 下文件 | 职责 |
|---|---|
| index.html | 语义化入口、导航、主区域和抽屉容器 |
| css/app.css | 暖白主题、布局、状态与响应式样式 |
| js/app.js | 初始化、路由/导航、生命周期协调 |
| js/api.js | Fetch、Envelope、错误、超时、requestId |
| js/state.js | 当前 KB、页面内存、请求代次与订阅 |
| js/utils.js | 安全 DOM、时间及通用展示辅助 |
| js/chat.js | 单轮提交、历史阅读、真实引用与证据抽屉 |
| js/knowledge.js | KB 创建选择、分页、上传与任务轮询 |
| js/retrieval.js | 策略参数、结果比较与 Diagnostics |

现有 static/retrieval-demo.html 是演示页，不等于 V1 产品入口；保留兼容，本次不修改。由于 MVC servlet path=/api，WEB-01 必须实际验证静态首页、模块和 CSS 的访问路径与 MIME，并统一资源基路径，不能假定 /index.html 与 /api/index.html 等价；优先沿现有 DispatcherServlet 前缀验证 /api/index.html，最终入口由该任务验收确定。

## 6. 安全与可靠性

统一 API Client 依次处理网络/超时、HTTP、JSON/Envelope、业务状态；拒绝畸形响应，保留 requestId，错误不包含模型原文、Token 或内部异常。FormData 请求不设置 JSON 头。ID 全程字符串，路径使用 encodeURIComponent；Long 统计和耗时按显示需求显式处理，不能无条件转为 Number。

不可信模型回答、Chunk、文件名、标题、错误信息均用 textContent 等安全 DOM API。V1 Markdown 默认纯文本加保留换行，允许未来以 DOM 节点构造有限格式；禁用 HTML 执行、事件属性、危险 URL，不引入未经清理 innerHTML。引用控件只绑定响应 RagCitation，不根据任意 [数字] 或模型链接推测。

AbortController 只取消浏览器等待；超时不自动重复上传或付费模型请求。显式用户重试提示服务端可能已执行；GET 查询可恢复。轮询使用完成后 setTimeout，退避并设置等待上限，页面卸载停止。切换 KB/页面递增请求代次并取消旧等待，回调写入前校验 KB 和代次。共享 KB 选择，业务数据分区，清空旧抽屉、任务展示与结果；不把跨库历史拼成模型上下文。

## 7. 任务拆分与依赖

全部在 feature/web-ui-v1，逐任务授权开发；本次只归档。

| 任务 | 工作与完成条件 | 依赖 |
|---|---|---|
| WEB-01 基础工程与静态资源入口 | 入口、模块、主题、导航、state/API Client；验证路径、MIME 与错误处理 | 本设计 |
| WEB-02 两个只读后端 API | 分页、最新任务摘要、发布一致性、证据归属；完成针对性测试 | 契约 |
| WEB-03 知识库与文档导入 | 选择创建、上传/去重、非重叠轮询、列表发布闭环 | 01、02 |
| WEB-04 AI 问答与引用交互 | 单轮状态、页面内存历史、证据抽屉与失效处理 | 01、02、03 |
| WEB-05 检索实验室 | 四策略、参数、排名评分、分策略失败及诊断 | 01、03 |
| WEB-06 UI 完善与端到端验收 | 响应式、键盘、安全、竞态与原能力回归 | 03～05 |

## 8. 验收标准（待执行）

| 范围 | 必须覆盖 |
|---|---|
| 只读 API | KB 不存在、跨 KB/文档/版本/Chunk 组合、未发布/非活动版本、坏区间与活动指针损坏；不返回越权内容 |
| 分页 | 默认/非法/边界参数、越界空页、稳定排序、多任务多版本无重复、正确 total 与最新摘要 |
| 协议 | /api 前缀、201/202/200、HTTP 与 code 分离、requestId、Long 字符串、旧 DTO 不变 |
| 导入 | 新文件、重复、非法类型/大小/编码、任务成功失败、上传成功但仍处理、发布后可问答；不依赖 UI 重试驱动任务 |
| RAG | ANSWERED、INSUFFICIENT_EVIDENCE、FAILED；无伪造引用；刷新历史清空；请求无历史 |
| 引用 | 点击加载匹配 Chunk、来源信息、失败/不存在/未来旧版本引用提示 |
| 检索 | 四策略独立与多选、SUCCESS/EMPTY_RESULT/FAILED、无隐式降级、排名评分与 Diagnostics 忠实于 DTO |
| 安全可靠性 | 超过 JS 安全整数 ID、XSS 文本/文件名/模型答案、超时取消、无自动付费重试、轮询不重叠、旧请求不覆盖新状态 |
| 可用性 | 窄屏、键盘导航、抽屉焦点、空状态、加载与可恢复错误 |
| 回归 | 按修改影响范围验证原 M1/M2/M3；WEB-06 关键验收节点执行全量回归；真实供应商与基础设施须另行授权 |

本次仅文档检查，不将上述验收标记为通过。

## 9. 与历史描述差异及待确认事项

架构文档 Spring Boot 3.x 与当前 pom.xml 4.1.2 不一致；本次按当前代码且不改冻结文档。原计划仍保留 M0 阶段表述及“不提前建设 Web UI”，本次明确授权归档不代表回写原里程碑状态。

已核实差异：没有文档列表或证据 API；上传不是统一201而是202/200；任务 READY 不是单独的发布字段；RagCitation 没有 filename/content；检索 Chunk 没有 filename；全部 Long 而非仅 ID 输出字符串；现有去重返回首个任务，新列表摘要建议最新任务；活动指针当前仅首次发布可写，不能假设已支持版本切换。

待 WEB-01 实测确认静态入口 URL 与部署 context-path；运行环境开关与服务可用性待实际验收。分页上限、items 命名、最新任务摘要及统一404策略为本文明确提出的待实施契约，WEB-02 按此落实；未来历史引用兼容性另案设计，不阻塞当前 V1。
