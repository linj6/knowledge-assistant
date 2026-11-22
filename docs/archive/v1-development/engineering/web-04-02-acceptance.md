# WEB-04-02 验收记录

基线：feature/web-ui-v1，HEAD 7201a5d53f550ebe6720caed8a4ae44d27eb1f32。保留前轮未提交实现，不自动提交。

## 实现与实际修复

真实 Citation 按钮仅由 RagAnswer.citations 创建，绑定消息知识库；非法或重复 evidenceId 拒绝整份回答。ID 全程字符串。证据使用现有 API Client，验证返回归属、字段类型和码点区间；原文通过 textContent 渲染，不截取全文。

修复关闭后立即重开时，上一次排队的原生 close 事件可能关闭新抽屉的问题：close 事件仅在 dialog 已关闭且仍有上下文时清理。新增回归验证新请求不会被旧 close 事件取消。

测试桩原本覆盖同类 window 监听器，修为累积派发，以真实覆盖 pagehide 的多个订阅者。未修改业务后端、app.js、api.js 或本轮 CSS。

## 本轮自动化结果

- JavaScript 121/121 通过：WEB-03 66、WEB-04-01 26、WEB-04-02 29。
- 证据测试覆盖打开、关闭按钮、Esc cancel、原生 close、焦点进入与恢复、重开清错、排队 close、切库、Hash 往返、pagehide、事件不重复绑定。
- 可控 Promise 覆盖 E2 先返回/E1 晚到，关闭与切库后迟到成功、超时后迟到成功，均不写入旧正文。
- 定时器覆盖取消后回调无效、成功清除定时器、旧定时器不污染重开。
- 404、500、网络、超时、非法 Envelope 均显示对应安全提示；手动重试保持原路径，连续重试只产生一个当前 GET，随后可恢复成功。
- 三种 ID 不匹配、非法字段和来源区间均拒绝；Long 字符串精度保持；非法/重复 evidenceId 不产生按钮。
- HTML 内容保留为文本，不创建 script/img 节点；服务器错误 HTML 与非法 requestId 不展示。
- 全部静态 JavaScript 模块语法检查通过。
- Maven 定向测试 8 项通过，含真实 HTTP /api/js/evidence.js 与既有入口检查：200、JavaScript MIME、非空内容。
- Spotless apply/check 通过；git diff --check 通过（仅有 Git 行尾转换提示）。

## 实际命令

```powershell
node --experimental-vm-modules --test src/test/js/*.test.mjs
Get-ChildItem src/main/resources/static/js/*.js | ForEach-Object { Get-Content $_.FullName -Raw | node --input-type=module --check }
mvn spotless:apply
mvn '-Dtest=WebStaticResourcesTests,DocumentQueryTests,DocumentQueryPersistenceTests' test
mvn spotless:check
git diff --check
git status --short --untracked-files=all
git diff --stat
```

## 验收边界与结论

READY TO COMMIT。没有已知代码阻断问题。模拟 DOM 仅证明状态和事件协调，不代表真实浏览器验证。WEB-06 仍须验收视觉、移动端、原生模态背景焦点限制、Tab/Enter/Space/Esc、真实焦点恢复及辅助技术播报。原生模态打开时背景不可交互，快速切引用测试通过程序触发按钮验证协调逻辑。

未调用付费模型。Maven 持久化测试使用其既有测试数据库生命周期；未修改数据库或 Migration 源文件。起始及结束 git status 均未显示用户所述两个原有未跟踪日志，未删除、覆盖或提交日志。未创建 Commit，HEAD 不变。
