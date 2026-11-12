# WEB-06-A 验收记录

日期：2026-11-12。分支 feature/web-ui-v1；HEAD 441a182129f0b671982893fbfeac6ca473409b98；开始时工作区干净。未提交、推送或修改配置。架构文档 Boot 3.x 与 pom 4.1.2 的已知差异保留。

## 审查结论

审阅 static/js 全部八模块及行为测试。app 负责 Hash 导航、知识库加载和协调；api 统一 HTTP/Envelope/错误；state 内存共享状态；knowledge 管理创建与分页；import 上传及轮询；chat 单轮回答和内存历史；evidence 原生 dialog；retrieval 四策略及 Diagnostics。没有引入框架或修改后端契约。

切库使用代次与 AbortController 阻止旧响应写入。Chat、Citation、Retrieval 离页失效；上传离页允许完成同一知识库请求，轮询仅知识库页面可见时继续，切库清空任务。刷新不保存问答或任务。重复提交锁、超时、轮询停止及结果未知提示已有覆盖。取消客户端等待不能取消服务端模型执行。付费 POST 无自动重试；任务 GET 的有限重试不触发模型。业务正文使用 textContent；日志不输出原始响应。Long ID 按字符串传递，无 Number 转换；Citation 按知识库、文档、版本、Chunk 绑定。Diagnostics 只显示返回数据，不补造评分或阶段。未确认需要修改生产代码的缺陷。

发现验证缺口：WebStaticResourcesTests 漏掉 import.js 和 retrieval.js，且未断言 HTML/CSS MIME。本轮补齐；这是测试覆盖修复。

## 本轮实际验证

- `node --experimental-vm-modules --test src/test/js/*.test.mjs`：176 项通过，失败/取消/跳过均 0。
- 全部 8 个 static/js 模块经 `node --input-type=module --check`：通过。
- Maven 定向 KnowledgeBaseApiTests、ImportApiTests、DocumentQueryTests、RagApiTests、RetrievalApiTests、RetrievalServiceTests、RetrievalDemoTests、WebStaticResourcesTests：50 项，0 失败、0 错误、0 跳过。
- `mvn '-Dtest=KnowledgeBaseApiTests,ImportApiTests,DocumentQueryTests,RagApiTests,RetrievalApiTests,RetrievalServiceTests,RetrievalDemoTests,WebStaticResourcesTests' test spotless:check` 最终 BUILD SUCCESS。首次运行因新增行换行格式失败，归一化该文件 CRLF 后重跑成功。
- Spotless check、git diff --check 通过。未执行 clean verify。

静态测试为随机端口嵌入式 Spring Boot 真实 Servlet HTTP，使用 Java HttpClient；不是 MockMvc。排除 DataSource/Flyway，只导入 Web 配置与 Mock 服务，无业务 Worker。/api/index.html、/api/retrieval-demo.html、/api/css/app.css 和全部八个 /api/js/*.js 均 200、非空，HTML/CSS/JS MIME 断言通过。相对模块依赖均存在，Node 模块链接测试通过；演示页定向测试通过。API 控制器测试使用 MockMvc，不等于真实业务 HTTP 联调。

## API 契约

Controller 的 /v1 路径结合 servlet /api 与前端一致：知识库 POST/GET、文档 GET 和 multipart POST documents:import、任务 GET、chat/answers POST、文档版本 chunks GET、retrieval/search POST。创建 name/description；上传 FormData file 不手设 Content-Type；分页 page/pageSize；RAG knowledgeBaseId/question/strategy，仅当前问题，无历史；检索 knowledgeBaseId/query/strategies/parameters，四策略及五个整数参数一致。Envelope code=0 才成功，HTTP 200 不替代业务状态判断。引用请求使用 citation.indexVersionId 作为 versions 路径参数并核对响应身份；Diagnostics 校验策略集合、实际策略、状态、候选和阶段。不修改 M1/M2/M3。

## 测试依赖分类与环境

本轮白名单：纯 Mock 单元 RetrievalServiceTests；MockMvc API/查询测试；隔离嵌入式 HTTP 静态/演示测试。未运行 Testcontainers 类，包括 DocumentLifecycleMigrationTests、ImportPersistenceTests、KnowledgeBasePersistenceTests、KnowledgeAssistantApplicationTests、MybatisCompatibilityTests、DocumentQueryPersistenceTests。数据库/索引/导入 IT 需要 PG 或 ES/MinIO；online-model profile 和 OnlineIT 需要在线模型，均未运行。

Compose 只读盘点：PG pgvector 0.8.0-pg17 固定 digest；ES 本地 8.18.0/IK8.18.0；MinIO 本地 RELEASE.2025-09-07T16-13-09Z，pull_policy never。端口仅绑定 127.0.0.1，三者独立持久卷。Testcontainers PG 使用同一固定 digest，testcontainers.properties 指定 LocalImagePullPolicy，多处显式禁止拉取。本轮未启动或探测这些业务服务；配置存在不代表服务健康。

默认 application.yml：servlet /api；Flyway enabled=true；Worker enabled=false；ES enabled=false；model enabled=false、online-authorized=false；rerank 同样双关闭；embedding baseline-authorized=false。Embedding 默认 text-embedding-v4/1024；模型基于 Spring AI OpenAI 适配配置，Chat ID 与 endpoint 需要启用时明确配置。MinIO endpoint/bucket 与凭据环境变量分离。未读取 application-local.yml 或凭据原值，也未修改任何配置；运行时覆盖值未核实，不能据默认值断言实际环境安全。

PG 用于知识库、文档、任务、发布快照、Chunk/vector。MinIO 用于上传原文和 Worker 读取；ES 用于关键词索引及 BM25/混合。创建知识库写 PG；上传写 MinIO 和 PG；Worker 领取/重试/分块/向量/发布写 PG 与 ES。查询和 Citation 读取 PG，BM25 读 ES；静态资源不依赖三者。

Worker 导入分块后会触发 Embedding；VECTOR/混合查询会触发 query Embedding；BM25 不需要 Embedding；HYBRID_RERANK 另触发 Rerank；RAG 在检索和上下文满足条件时触发 Chat，默认 HYBRID_RRF 还可能先触发 Embedding。失败与重试可能扩大调用数，不能把一个 UI POST 等同一个供应商请求。

## 浏览器能力

使用 computer-use 技能初始化 @oai/sky 成功；首次使用不存在的方法未完成探测，查阅 API 后调用 list_windows，返回 native pipe unavailable / 系统找不到指定文件。停止重试。浏览器类型版本、本地访问、截图、键盘、Console、Network 均未验证。Node 模拟 DOM 不能替代真实浏览器。

## 后续计划（仅规划，未执行）

WEB-06-B：Windows 11 + IDEA + Chrome/Edge。优先运行隔离 Mock 后端/静态测试夹具；禁用 Worker、模型与 Rerank，并阻断供应商出口，避免普通应用启动自动 Flyway。记录浏览器版本；/api/index.html 与演示页；Hash/刷新/历史；知识库选择；分页；上传输入规则；任务各状态；模拟 RAG/Citation/四策略响应；桌面及窄屏；Tab 顺序、Esc、IME Enter、dialog 焦点恢复；Console 无异常，Network 核对 MIME、取消与请求次数。真实创建/上传不属于无写入体验检查，须在隔离数据授权后执行。

WEB-06-C：明确授权后使用独立 Compose 项目、独立 PG 数据库、ES 索引、MinIO bucket/卷和隔离知识库；禁止复用开发数据。预先准备已迁移测试环境，Migration 另行授权。一个小型 Markdown、一次上传、一个 Worker 任务，检查终态 READY/FAILED、发布版本、PG/ES 双索引可见性。会写三种存储并调用 Embedding；建议供应商 Embedding 请求总上限 4，包含重试，超限/超时/首个任务失败即停止。未经授权不自动重跑或清理数据。

WEB-06-D：对上述固定发布快照执行一次四策略请求和一次 HYBRID_RRF 单轮 RAG，随后读取 Citation；比对排名、原始评分、Diagnostics、身份和正文区间。不假定阶段共享：建议供应商总预算 Embedding 4、Rerank 1、Chat 1（含重试），必须具备计数/禁止重试机制才能保证上限；否则先完善隔离运行方案再请求授权。费用取决于供应商价格、输入 token、Chunk 数和重试，本轮未估算金额。出现身份不一致、越界数据、意外持久化、预算达到上限或首个供应商失败立即停止。上述在线请求及数据写入均等待明确授权。

## 结论

READY TO COMMIT：是，仅限 WEB-06-A 测试补齐与记录；没有自动提交，Commit SHA 无新增。WEB-06 全站最终验收尚未完成：真实视觉/移动端/键盘/DevTools、真实服务健康、Worker 与双索引发布、在线检索/RAG/Citation 一致性仍待 B/C/D。本轮没有模型调用、业务数据写入、Schema/Flyway 执行或基础设施启动。

修改文件：本记录；WebStaticResourcesTests.java。最终状态与 diff 以交付终端检查为准。
