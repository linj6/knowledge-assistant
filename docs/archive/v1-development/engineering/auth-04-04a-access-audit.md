# AUTH-04-04A 授权覆盖审查

- 分支：`feature/m4.5-product-enhancement`
- 基线：`a8b32b017f06b71e3b0dbc31578cc12fc23b7d14`
- 结论：**NOT READY**。HTTP 授权覆盖审查通过，未发现跨用户资源访问漏洞。本地 PostgreSQL 已恢复连接，实测 Flyway 停在 V5，共有 5 个历史知识库。V6 的归属结构尚未部署，AUTH-04-04B 前置条件未满足。

## HTTP API 授权矩阵

核对了 10 个生产 Controller 的类级和方法级映射，共 21 条显式 API 映射。`spring.mvc.servlet.path=/api` 与 Security 的 `basePath("/api")` 一致。表中错误格式为 HTTP 状态 / 业务码。所有要求登录的路由在匿名请求通过 CSRF 检查后返回 401 / 40100。默认 CSRF 保护 POST、PUT 等非安全方法，缺失或无效时返回 403 / 40300，可能早于认证和所有权判断。GET、HEAD、OPTIONS、TRACE 不要求 CSRF，仍受授权规则约束。

证据标记：B 为既有 `AuthBusinessHttpTests`，S 为既有 `AuthSecurityHttpTests`，D 为既有文档与 Citation 测试源，O 为既有 `OnlineAuthorizationTests`，H 为本轮隔离 HTTP 验证，C 为源码核对。

| Method | 实际路径 | 匿名 | CSRF | 所有权或授权位置 | 拒绝 | 证据与风险 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/auth/register | 允许 | 是 | AuthService 创建 ACTIVE 用户，身份由服务端生成 | 403 / 40300；重名 409 | B、S、C |
| POST | /api/v1/auth/login | 允许 | 是 | AuthService 认证，成功后轮换 Session 并保存 Principal | 403 / 40300；凭据错误或禁用 401 / 40100 | B；存量 Session 风险见下文 |
| POST | /api/v1/auth/logout | 拒绝 | 是 | AuthController 清除当前认证、Session、CSRF 和 Cookie | 401 / 40100 或 403 / 40300 | B |
| GET | /api/v1/auth/me | 拒绝 | 否 | AuthController 返回当前 Session Principal | 401 / 40100 | B |
| GET | /api/v1/auth/csrf | 允许 | 否 | CsrfController 返回本会话 CSRF token | 不适用 | B、S、H |
| POST | /api/v1/knowledge-bases | 拒绝 | 是 | Controller 从 AuthPrincipal 取所有者，Repository 写入 owner_user_id | 401 / 40100 或 403 / 40300 | B，覆盖伪造 userId |
| GET | /api/v1/knowledge-bases | 拒绝 | 否 | KnowledgeBaseRepository.findAllByOwner 按当前用户过滤 | 匿名 401 / 40100；他人和未绑定库从结果排除 | B |
| GET | /api/v1/knowledge-bases/{id} | 拒绝 | 否 | KnowledgeBaseService.requireOwned | 401 / 40100 或 404 / 40401 | B |
| GET | /api/v1/knowledge-bases/{kbId}/documents | 拒绝 | 否 | DocumentQueryController 先校验 KB 所有权，Service 按 KB 分页 | 401 / 40100 或 404 / 40401 | B、D |
| GET | /api/v1/knowledge-bases/{kbId}/documents/{documentId}/versions/{versionId}/chunks/{chunkId} | 拒绝 | 否 | DocumentQueryController 先校验 KB 所有权，Service 校验文档、活动版本、分块链 | 401 / 40100；库不匹配 404 / 40401；下级资源不匹配 404 / 40400 | B、D |
| POST | /api/v1/knowledge-bases/{kbId}/documents:import | 拒绝 | 是 | DocumentImportController 先校验 KB 所有权，再调用 receive | 401 / 40100、403 / 40300 或 404 / 40401 | B；容器 multipart 解析见下文 |
| GET | /api/v1/import-tasks/{taskId} | 拒绝 | 否 | DocumentImportService.getOwned 按任务关联文档的 KB 判定 | 401 / 40100 或 404 / 40400 | B |
| POST | /api/v1/retrieval/search | 拒绝 | 是 | RetrievalController 校验 KB 后调用 search | 401 / 40100、403 / 40300 或 404 / 40401 | B、O |
| POST | /api/v1/chat/answers | 拒绝 | 是 | RagController 校验 KB 后调用 answer | 401 / 40100、403 / 40300 或 404 / 40401 | B、O |
| GET | /api/v1/evaluation/datasets | 拒绝 | 否 | Security denyAll，EvaluationRunController 不可达 | 匿名 401 / 40100；登录 403 / 40300 | H |
| POST | /api/v1/evaluation/runs | 拒绝 | 是 | Security denyAll，EvaluationRunController 不可达 | 匿名 401 / 40100；登录或 CSRF 失败 403 / 40300 | H |
| GET | /api/v1/evaluation/runs | 拒绝 | 否 | Security denyAll，EvaluationController 不可达 | 匿名 401 / 40100；登录 403 / 40300 | S、H |
| GET | /api/v1/evaluation/runs/{runId} | 拒绝 | 否 | Security denyAll，EvaluationController 不可达 | 匿名 401 / 40100；登录 403 / 40300 | H |
| GET | /api/v1/evaluation/runs/{runId}/review | 拒绝 | 否 | Security denyAll，AnswerReviewController 不可达 | 匿名 401 / 40100；登录 403 / 40300 | H |
| PUT | /api/v1/evaluation/runs/{runId}/review | 拒绝 | 是 | Security denyAll，AnswerReviewController 不可达 | 匿名 401 / 40100；登录或 CSRF 失败 403 / 40300 | H |
| GET | /api/v1/evaluation/runs/{runId}/score | 拒绝 | 否 | Security denyAll，AnswerReviewController 不可达 | 匿名 401 / 40100；登录 403 / 40300 | H |
| GET | /api/、/api/index.html、/api/retrieval-demo.html、/api/css/app.css、/api/js/*.js | 允许 | 否 | Security 按方法与路径放行，MVC 提供打包资源 | 缺失资源由 MVC 返回 404 | S 抽样；公开脚本包含前端调用逻辑 |
| 其他 | 未知 API、前缀外路径、非白名单资源或方法 | 拒绝 | 非安全方法需要 | Security anyRequest().authenticated()；认证后交给 MVC 或容器 | 匿名 401 / 40100；CSRF 失败 403 / 40300；登录后未映射 API 通常 404 / 40400 | S 覆盖匿名未知路径及 .js 后缀，H 覆盖登录后未知 API |

Evaluation 的基路径及子树匹配优先于公共白名单，无 Method 限制。静态放行仅限表内 GET，MVC 的隐式 HEAD 和 OPTIONS 不继承这个 GET 白名单，Evaluation 的方法无关拒绝仍优先。源码与依赖清单未发现额外的生产 RouterFunction、自定义 Servlet 路由、Actuator 或 Spring Data REST 暴露。框架资源和错误映射也受全局认证兜底。本轮未穷举框架错误分发和静态资源组合。

## 授权调用边界

- `AuthPrincipal.requireUserId` 从已认证的服务端 Principal 取 ID。创建、列表、详情与业务入口不从请求参数、请求体或上传表单获取授权用户 ID。客户端提交的 KB ID 只用于选择目标。仓库的 `findByIdAndOwner` 同时按 `id` 和 `owner_user_id` 等值查询，未绑定、他人及不存在的 KB 都返回同一 40401。列表也用相同所有者过滤。
- 文档列表与证据接口均先校验路径中的 KB。证据服务要求文档属于该 KB、请求版本是文档的活动已发布版本、分块归属与 KB/文档/版本一致。串换 ID 无法读取证据，损坏的活动版本元数据走内部错误。导入任务先按 taskId 查任务，再按任务内 documentId 查文档，最终用文档的 KB 校验。调用者无法独立指定任务的 KB。任务或文档缺失、以及 KB 所有权未通过均返回 40400。
- 上传在 `receive` 前拒绝外部 KB，因此不会进入 MinIO、导入任务写入或后续 Worker。Spring MVC 在 Controller 执行前可能解析 multipart 和暂存请求内容，所有权判定无法避免该层的处理成本。检索和 RAG 在调用业务服务前拒绝未授权 KB，因此不会触发查询 embedding、召回、重排、上下文构建或回答生成。输入绑定或参数校验可能先返回 400。既有业务 HTTP 测试包含跨用户、未绑定、缺失或混用关联 ID，覆盖伪造身份，并验证拒绝请求未调用对象存储或检索与 RAG 服务。
- Worker 使用调度器领取或恢复的任务及数据库关联，任务租约的 owner 代表执行者标识；导入处理器还核对文档与版本归属。后台处理不依赖 HTTP Session。`KnowledgeBaseService.get`、`DocumentImportService.get`、`RetrievalService.search` 和 `RagService.executeWithTrace` 保留可信内部入口。离线 Runner 由受控入口执行，EvaluationService 可内部构建评估 trace，三个 Evaluation Controller 的入口均被 Security 拒绝。审查未找到可从在线请求跳过 Controller 所有权检查到达这些内部路径的调用。

## 已确认问题与严重程度

- **低，已知会话限制**。用户已登录后，若运维把账号状态改为 DISABLED，原 Session 仍可在退出或过期前访问自己的资源。源码证据是 `DatabaseUserDetailsService` 在登录认证时检查状态，后续请求使用 Redis 保存的 SecurityContext，`requireOwned` 仅按当前 Principal 的用户 ID 匹配。AUTH-03 运维文档已明确该语义。本轮未改动账号状态来复测，也未改变会话语义。
- **低，上传处理成本**。已认证且 CSRF 有效的跨库上传可到达 multipart 解析，应用的归属检查仍发生在对象存储与任务写入之前。这是请求解析开销，未发现跨用户持久化副作用。
- **迁移阻断**。本地数据库处于 V5，缺少 V6 的归属结构，归属统计与约束验收仍待完成。

## 历史知识库只读盘点

Docker Desktop 和 Engine 可用。现有 Compose PostgreSQL 容器处于停止状态，挂载原数据卷，所需连接配置已存在。使用 `docker start` 启动原容器，`pg_isready` 与宿主机回环端口检查均通过。通过容器内的 psql 复用现有配置进行 TCP 认证，核验连接后保留容器运行。

所有 SQL 均在 `REPEATABLE READ READ ONLY` 事务中执行，`transaction_read_only` 返回 `on`，两个事务均以 `ROLLBACK` 结束。先检查迁移历史、表及列是否存在，再查询当前结构支持的聚合统计。

| 检查项 | 实测结果 |
| --- | --- |
| Flyway 当前成功版本 | V5，失败历史记录 0 条 |
| app_user 是否存在 | 否 |
| knowledge_base.owner_user_id 是否存在、是否允许 NULL | 列不存在，实际可空性不适用 |
| 知识库总数 | 5 |
| 已绑定、未绑定知识库数量 | 归属列不存在，无法按该列统计 |
| 悬空 owner ID 数量及受影响知识库数量 | 无法统计，归属列和用户表均不存在 |
| ACTIVE、DISABLED 用户数量 | 无法统计，用户表不存在 |
| 已归属 DISABLED 用户的知识库数量 | 无法统计，归属列和用户表均不存在 |

当前 5 个历史库都需要人工确认归属。仓库 V6 新增可空归属列且不绑定数据，应用 V6 后需重新盘点实际结果。[AUTH-01 历史知识库绑定](../operations/auth-01-owner-binding.md) 的绑定流程需要先具备 V6 结构，再按明确清单确认目标 ACTIVE 用户及归属。本轮未执行 Flyway、绑定、用户创建或其他数据库写入，也未重建容器或数据卷。

## AUTH-04-04B 前置条件

1. 当前本地最小阻断项是 Flyway 停在 V5。需另行授权应用现有 V6，在迁移目标环境确认其成功记录及实际表列，再执行归属聚合盘点。
2. 明确每个未绑定历史库的归属，由负责人确认知识库清单、目标 ACTIVE 用户及预期数量。按已授权的人工流程处理后复查。确认未绑定和悬空计数均为 0。
3. 核查 DISABLED 所有者并记录处置决定。DISABLED 本身不违反非空或引用约束，账号状态及其存量会话处理需要独立确认。
4. 确认生产和受控写入路径都提供有效所有者。在施加非空或计划中的引用约束前考虑并发写入与锁窗口，迁移执行前再次核对数据。使用新的 Flyway 迁移。

## 实际验证

- 核对分支、HEAD、全部生产映射、Security 和服务调用链。开始时工作区干净。项目指引已记录架构文档 Spring Boot 3.x 与 POM 4.1.2 的差异，本轮以代码和 POM 为准。
- 复用本地既有 Surefire 结果，`AuthBusinessHttpTests` 1 项、`AuthSecurityHttpTests` 1 项、`OnlineAuthorizationTests` 7 项、`AuthMigrationTests` 3 项均无失败、错误或跳过。迁移测试属于既有测试环境证据。
- 使用仓库外临时 Java 探针复用现有构建产物，在隔离嵌入式 Tomcat 注册生产 Security、CSRF 和三个 Evaluation Controller，使用容器 Session、测试认证入口及空评估服务依赖。已认证对照路由返回 200。七条 Evaluation 映射分别发送匿名和已认证请求，状态全部为 401 和 403，403 响应业务码均为 40300。写方法带各自会话的有效 CSRF。已认证未知 API 返回 404。探针退出码 0。
- 本轮只新增审查报告。未运行 Maven 编译、Maven 全量回归或重复执行既有 63 项测试。未请求在线模型、启动 Docker 基础设施、执行数据库写入或迁移。未提交、推送或合并。

- 已审阅新增报告的完整差异，21 条显式 API 映射均已列入矩阵。工作区与新增文件的 Git 空白检查通过。
