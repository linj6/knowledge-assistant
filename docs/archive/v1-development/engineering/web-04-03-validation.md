# WEB-04-03 综合验收

## 基线与范围

2026-11-12，feature/web-ui-v1，HEAD a0e3e76ca113e7cecd2f27e69fc19e4510df174b。初始工作区干净，无未跟踪日志；未删除或覆盖历史文件，未提交代码。

阅读产品设计、API 盘点、web-04-02-acceptance.md、代码规范；审查 app/state/api/chat/evidence、HTML/CSS、Java DTO、RagController、DocumentQueryController 及 Node 测试设施。历史文档中的“待实施”描述按当前代码核查，不修改冻结文档。

## 结论与新增价值

未发现需修复的阻断缺陷，未修改生产代码。新增两个真正通过 app 初始化、表单提交、真实 Citation 点击和 Fetch 桩驱动的跨模块测试：

- 两条历史回答交替读取证据，分别保持原 chunk/document/version 身份；只有两次 RAG POST，GET 不调用模型，后续 POST 仅包含当前 question、knowledgeBaseId、strategy。
- A 证据未完成时切换 B 并提交问题；A 迟到不展示、不释放 B 问答锁；B 回答的 Citation GET 使用 B 路径并展示 B 正文。

结合现有行为测试确认：E2 先到/E1 后到、A 问答迟到、关闭后重开及延迟 close、Hash 往返、取消与超时后的迟到结果均受代次隔离。关闭/切库会取消等待并清空正文；旧请求不能释放新请求锁。历史仅为内存 DOM，切库清空、刷新重新初始化，无 session/history 请求字段。

HTTP、Result 和 RAG 状态分层处理。非法状态、answer/citations 类型、重复 evidenceId、数值 ID 拒绝整份答案；ANSWERED 空 citations 按协议失败，不生成按钮。INSUFFICIENT_EVIDENCE 允许非空说明文本与后端构造约束一致；FAILED 使用枚举字符串而非异常对象。requestId 缺失允许不展示，chat 现有行为以纯文本显示非法字符串，evidence 错误仅显示合法追踪标识。未改变该既有差异。

引用仅源自服务端 citations，顺序不变，闭包绑定回答所属 KB 与引用；证据返回三个 ID 必须匹配。后端再次负责 KB/文档/版本归属。模型文本、文件名、标题与 Chunk 使用 textContent，无 HTML 执行。无自动 POST 重试；GET 仅手动重试。取消只代表客户端停止等待。

模块职责清晰，初始化有防重复保护，定时器与 AbortController 在结束/失效时清理；本轮未发现需重构的重复监听或死状态。WEB-03 回归通过。

## 实际验证

```powershell
node --experimental-vm-modules --test src/test/js/*.test.mjs
Get-ChildItem src/main/resources/static/js/*.js | ForEach-Object { Get-Content $_.FullName -Raw | node --input-type=module --check }
mvn '-Dtest=WebStaticResourcesTests,RagApiTests,DocumentQueryTests,DocumentQueryPersistenceTests' test
mvn spotless:check
git diff --check
git status --short --untracked-files=all
git diff --stat
```

- Node：123/123，通过；WEB-03 66，原 WEB-04 55，新增集成 2。
- 全部静态 JS 语法检查通过。
- Maven：18/18，失败/错误/跳过均 0。静态资源 1、RAG API 10、文档/证据查询 5、持久化 2。静态 HTTP 验证不代表模型成功。
- Spotless check 通过，无 Java 修改所以未运行 apply。
- git diff --check 通过；新增文件在未暂存状态下不计入 git diff --stat，其内容另行审阅。

既有 DocumentQueryPersistenceTests 实际通过 Testcontainers 启动临时 PostgreSQL 并执行测试迁移；执行前未充分识别这一生命周期。未启动项目 Docker 基础设施，未修改数据库/Migration 源文件，未调用真实供应商或 Worker。

## 边界与交付

READY TO COMMIT（自动化与代码审查范围）。本轮仅新增本记录及 web-chat-integration.test.mjs，无 Commit SHA，HEAD 不变。

WEB-06 待验收，均未标记 PASS：桌面/移动视觉、真实 IME、原生 dialog Tab/Esc/focus、正文滚动、屏幕阅读器公告、真实网络面板请求、真实 RAG 与证据联调。模拟 DOM 不替代浏览器验收；未执行全量 Maven 回归。
