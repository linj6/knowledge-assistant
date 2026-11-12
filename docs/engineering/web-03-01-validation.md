# WEB-03-01 验收记录（2026-11-12）

基线 feature/web-ui-v1 / 8cfc4292acaf24e822cd87a1ae2d8813e94802e2。保留原有未跟踪日志，不提交。

## 修复

- 创建开始记录选择修订号，用户后续选择优先，旧创建结果不能强制选回。
- 极大 total 使用 BigInt；Java int 最大页禁止继续下一页，提示末页按钮的可查询边界；拒绝超过 Long 上限的 total。
- 旧文档取消不显示错误；列表增加20秒客户端等待上限。
- 列表验证字符串正整数 ID 与描述类型。

## 实际执行

- `node --experimental-vm-modules --test src/test/js/web-knowledge.test.mjs`：31通过。
- 四个模块分别通过 `Get-Content <模块路径> | node --input-type=module --check`。
- `mvn spotless:apply "-Dtest=WebStaticResourcesTests,KnowledgeBaseApiTests,DocumentQueryTests" test`：13通过，0失败/错误/跳过。
- `mvn spotless:check`：通过。
- `git diff --check`：通过。

JS 测试运行真实 app/knowledge/state/api 模块、模拟 fetch 与最小 DOM，覆盖创建校验、防重、400/500/网络/AbortError、刷新失败保留成功事实、字符串ID、分页导航及越界、巨大total、非法响应、四种任务状态与发布状态独立、纯文本节点、Hash切换、列表与文档乱序。超时用 AbortError 模拟，未实等20秒。

随机端口真实 HTTP 测试覆盖 /api/index.html、所有模块（含 knowledge.js）、CSS、retrieval-demo.html、知识库列表、文档分页路径。使用真实 Controller 和模拟 Service，排除数据库/Flyway；不证明数据库持久化集成。未调用模型或启动基础设施。

## 浏览器边界与人工验收

浏览器工具报告 browser access host unavailable、native pipe unavailable；无法执行真实浏览器验收。最小 DOM 测试不能证明渲染、浏览器默认表单行为、焦点、前进后退和刷新。

人工步骤（待执行）：
1. 在隔离环境打开 /api/index.html，检查控制台、模块 MIME、暖白布局及窄屏。
2. 三个 Hash 页面切换、前进后退、刷新；确认知识库上下文一致，上传/RAG/检索按钮禁用。
3. 创建合法/空/超长知识库，快速重复提交；确认一次POST、返回ID被选择；断网及超时后刷新确认结果。
4. 使用空、单页、多页文档库验证首页/上一页/下一页/末页，切库重置第一页。
5. 人为延迟A请求并切B，确认B始终展示；恶意名称、描述、文件名应作为纯文本。
6. 检查 retrieval-demo.html 兼容。

未完成真实浏览器与持久化集成验收；最终验收暂不标记 READY TO COMMIT。WEB-03-02 上传、去重、任务轮询均未实施。
