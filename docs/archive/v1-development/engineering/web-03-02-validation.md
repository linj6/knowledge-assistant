# WEB-03-02 可靠性验收（2026-11-12）

基线：feature/web-ui-v1 / 494e1f6643b06f4a3f9ad6eee34453b5501bf4e2。未提交；保留两个既有日志。

## 审查与修复

上传和 GET 共用 api.js 的 Envelope 解析；FormData 不设置 Content-Type。单个模块只初始化一次，页面切换不重复绑定。

- 共享状态重复通知可能绕过已安排的等待，提前查询：生命周期恢复增加 timer 检查。
- 已排队回调停止后缺少代次保护：回调检查 epoch，停止同时清除 timer 引用。
- 新上传继承旧任务失败计数：开始新上传时清零。

## 行为验证

`node --experimental-vm-modules --test src/test/js/*.test.mjs`：65 通过（WEB-03-01 原31项、此前上传11项、本轮新增23项）。新测试运行真实 app、knowledge、import、state、api 模块，以可控 fetch Promise 和可取消模拟定时器验证，不调用模型。

覆盖完整 RECEIVED→PROCESSING→READY/FAILED、历史终态、未知状态/非法ID/任务不匹配、404/500、三次网络失败停止、3/6秒退避、手动恢复、15秒查询超时、60秒上传超时、网络中断、非法 Envelope、未知上传不重发、切库旧 READY 延迟返回、隐藏与页面暂停恢复、重复恢复单链、已排队回调失效、新任务替换旧任务、刷新不恢复、文件名纯文本。已有测试覆盖文件规则、FormData、重复提交、长字符串ID、发布状态独立。

READY/FAILED 刷新文档 GET，最终任务状态保留；不会伪造 PUBLISHED。查询失败不设置任务 FAILED。只追踪当前一个任务，新上传替换旧任务；同一页面切换保留ID，切库清空；无 sessionStorage/localStorage。刷新后文档列表只展示摘要，没有继续查询该任务的操作。

全部 static/js/*.js 通过 Node module 语法检查。git diff --check 通过。Maven 定向验证结果见交付报告。

## 边界

未执行真实浏览器和真实文件端到端验收；模拟 DOM 无法证明浏览器文件选择、布局与后台节流行为。未启动基础设施、未修改数据库、未调用付费模型。测试证明客户端行为，不证明实际 Worker 发布链路。浏览器验收仍待完成，严格最终验收不标记 READY TO COMMIT。
