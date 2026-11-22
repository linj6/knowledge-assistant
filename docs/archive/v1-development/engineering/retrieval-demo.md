# 轻量检索演示页面

本页仅用于本地开发体验 M2 四策略检索，不属于正式 V1 Web UI，不生成 RAG 答案。

## 启动与访问

在已有 PostgreSQL、迁移及知识库发布资料可用的环境中，将私有连接参数配置到被 Git 忽略的 `config/application-local.yml`，执行：

```powershell
mvn spring-boot:run '-Dspring-boot.run.profiles=local'
```

默认地址：http://localhost:8080/api/retrieval-demo.html 。`spring.mvc.servlet.path=/api` 同时影响 MVC 静态资源映射；`/retrieval-demo.html` 返回 404。页面同源 POST `/api/v1/retrieval/search`，不要通过 file:// 打开页面。修改端口后相应替换地址。

## 本地配置

默认模型、ES、Rerank 均关闭。BM25 需要启用 `assistant.elasticsearch.enabled` 并配置已有 ES/IK 及已发布索引。Vector/Hybrid 需要已有发布快照、向量数据及正确配置且授权的 Embedding。Rerank 还需单独配置并授权 `assistant.rerank`。遵循现有模型授权规范，不在 HTML 中填写密钥，不为演示启动未经授权的供应商调用。

填写已发布知识库 ID 和问题，分别只选 BM25、VECTOR、HYBRID_RRF、HYBRID_RERANK，再全选四策略对照。展开每张卡片的“阶段诊断与完整候选”。无候选展示提示，FAILED 展示后端脱敏错误枚举，其他策略可独立显示。结果按 API 返回顺序显示。ID 请求使用十进制字符串，响应沿用后端 Long 字符串序列化，避免 JavaScript 精度丢失。

## 本次验证与可复现证据

2026-11-11：

- `RetrievalDemoTests` 使用真实嵌入式 Tomcat 和项目 application.yml，HTTP 验证 `/api/retrieval-demo.html` 返回 200 / text/html，根路径返回 404，并检查同源 API 地址和危险渲染方式。
- `RetrievalApiTests` 使用真实 Controller/Service、模拟下游依赖，覆盖字符串 ID 输入、Long 字符串输出、四策略顺序、参数校验、成功 Envelope、部分策略失败及敏感异常隔离。这些不是线上真实检索结果。
- `RetrievalServiceTests` 覆盖检索状态与策略隔离。
- 复现：`mvn spotless:check '-Dtest=RetrievalDemoTests,RetrievalApiTests,RetrievalServiceTests' test`。结果位于 `target/surefire-reports`。
- 环境检查未发现本地 8080、5432、9200、9000 监听服务；未启动 Docker，未请求真实模型。没有执行已发布资料的五组真实检索，未完成浏览器提交、实际结果/失败、诊断展开、桌面及手机视觉验收，也没有截图。上述项目仍需在基础设施和资料就绪后执行，不应视为验收通过。

## 安全与限制

保留提供的独立 HTML，仅调整长 ID 和表格的窄屏换行。正文、问题和诊断通过文本节点展示，无 innerHTML、localStorage、第三方 CDN、前端密钥或新增 CORS 配置。安全检查为源代码审阅及轻量测试，不代表浏览器运行验证。

本页没有访问控制，只用于本地开发；未来公开部署必须另行评估访问控制，不可直接视为生产功能。没有新增检索接口或修改 M2 算法，没有开展 M3。

## 真实环境补充验收（2026-11-11）

结论：PARTIAL。以下取代上轮环境未启动的状态记录。

### 静态资源：PASS

应用实际启动于 8080，GET `/api/retrieval-demo.html` 返回 200。Docker Desktop 29.9.3，使用 `docker compose start` 启动原有 PG、ES、MinIO 容器，三者健康；未重建容器、未删除卷、未写入测试数据。Flyway 报告 schema 已是最新。

现有 `.env` 基础设施变量仅加载到启动进程，PG 密码映射到 `SPRING_DATASOURCE_PASSWORD`，MinIO 使用原有 KA_INFRA 变量。无 `config/application-local.yml`，未发现模型相关环境变量。使用 classpath local 配置，启动参数增加 `--assistant.elasticsearch.enabled=true`；模型与 worker 保持默认关闭。可在同样环境下执行：

```powershell
mvn spring-boot:run '-Dspring-boot.run.profiles=local' '-Dspring-boot.run.arguments=--assistant.elasticsearch.enabled=true'
```

执行前需在进程环境中配置现有 PG 密码与 MinIO 变量；不要将密码放入命令或提交文档。

### 真实 PG/ES 检索：PASS（HTTP）

复用知识库 `2120052899884367873`（manual-m0m1-20261110-151614）与现有 PUBLISHED 文档版本 `2120055079517310977`。POST 正式接口，query 为“索引发布”，参数 vectorTopK/bm25TopK/rrfK/fusionTopK/finalTopK = 20/20/60/20/5。

BM25-only：Envelope code=0，SUCCESS，阶段耗时 1105 ms，4 个候选。返回真实正文“Spring Boot 管理应用生命周期…”以及 documentId、indexVersionId、sourceStart/sourceEnd、headingPath。排名与分数如下：

| 最终/BM25排名 | Chunk ID | BM25分数 |
|---|---|---|
| 1 | 2120055097183719427 | 1.3753142 |
| 2 | 2120055097183719426 | 1.372027 |
| 3 | 2120055097246633986 | 1.3645755 |
| 4 | 2120055097246633987 | 1.3540555 |

query=`zzznomatch20261111xyz`：EMPTY_RESULT，0 候选，BM25 43 ms。

同一真实接口的四策略请求按 VECTOR、BM25、HYBRID_RRF、HYBRID_RERANK 顺序返回：VECTOR FAILED/EMBEDDING_FAILED；BM25 SUCCESS（同上4个结果，51 ms）；两个 Hybrid FAILED/EMBEDDING_FAILED（保留 BM25 阶段诊断，不返回最终候选）。Embedding 失败阶段 1 ms。这验证了真实服务的失败隔离，没有用 Fake 数据替代。

本地原始 HTTP 证据：`target/demo-bm25.json`、`target/demo-empty.json`、`target/demo-four.json`；应用日志 `target/retrieval-demo-runtime.log`（均不提交）。

### 真实在线模型：未验证

没有现成私有模型配置，Embedding/Rerank 未启用，未发起供应商请求。四策略请求仅验证关闭模型时的合法失败状态，不代表在线验收。没有 RRF/Rerank 最终排名，也无法比较排名变化。未执行三个依赖模型策略的单独在线请求。

### 浏览器交互：BLOCKED

尝试 Computer Use：native pipe 不存在，重试仍失败；另一浏览器工具报告 browser access host unavailable，无可用浏览器。没有截图、控制台或布局验收结果。页面正文、来源、分数和诊断的视觉显示以及按钮、空/失败提示、窄屏溢出、JS 异常仍未验证。

人工复现：打开页面，填上述知识库和问题，仅选 BM25 提交，核对上表并展开诊断；改用空结果 query；再全选四策略确认 BM25 仍显示而其他策略 FAILED。桌面与约 390px 窄屏分别检查横向溢出，并打开开发者工具确认控制台无异常。

### 最小修复

实际响应中的 elapsedMillis 受 Long 字符串序列化影响，原页面数字函数会显示“—”。现允许十进制数字字符串按原值显示，不转成 Number，保留 Long 精度；未修改检索算法。

回归：`mvn spotless:check '-Dtest=RetrievalDemoTests,RetrievalApiTests,RetrievalServiceTests' test` 通过，25 项，0 失败/错误/跳过；`git diff --check` 通过。应用与原有 Compose 服务保留运行，便于人工继续验收。未 Push/Merge/Rebase。
