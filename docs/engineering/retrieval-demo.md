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
