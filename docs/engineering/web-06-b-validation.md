# WEB-06-B 真实浏览器验收记录

## 结论与基线

BROWSER VALIDATION BLOCKED；WEB-06-B READY TO CLOSE：否。
2026-11-12，feature/web-ui-v1，HEAD 148e86663a8b981bd6457ab51f8306e60ccb308e；开始工作区干净。没有新 Commit。
已阅读设计、API 盘点、WEB-06-A、现有模块/样式、行为测试设施与静态 HTTP 测试。设计和 API 文档中的“尚未实施”属于历史基线，本轮不改写冻结文档。

## 实际环境与能力证据

使用 computer-use 技能，通过 @oai/sky.list_windows 一次探测，返回 native pipe unavailable，系统找不到指定文件；没有继续重试原生通道。
本机安装文件版本：Chrome 157.0.8103.52；Edge 157.0.4341.46。这是文件版本信息，不是已启动浏览器版本证据。
当前项目 Node resolution 未找到 playwright/playwright-core/puppeteer/puppeteer-core/selenium-webdriver；Python 未找到 playwright/selenium；PATH 未找到 chromedriver/msedgedriver；全局 npm 只有 Codex 和 Claude Code，默认 ms-playwright 缓存不存在。未安装工具、未修改系统配置。本次有限盘点不证明整台机器所有位置都没有自动化工具。
浏览器启动、导航、输入点击、截图、视口调整、Console 和 Network 全部未执行，不能标记 PASS。原生工具有截图/键盘 API 不代表连接可用。

## 隔离 Mock 设施与实际检查

新增 src/test/js/web-browser-mock.mjs，使用 Node 内置 http/fs，仅监听 127.0.0.1，默认 18080；读取仓库静态文件，所有业务状态只在进程内，重启清空。没有代理和出站业务请求，不读取应用配置，没有 JDBC、Flyway、Worker 或模型组件。它不是 Spring Boot，也不替代 WEB-06-A 的真实 Servlet 测试。

启动方式（仓库根目录）：

```powershell
node src/test/js/web-browser-mock.mjs
# 浏览器打开 http://127.0.0.1:18080/api/index.html
# 可选端口：启动前设置 $env:KA_MOCK_PORT='18081'
# Ctrl+C 停止并清空测试状态
```

本轮实际启动该 Mock HTTP 服务，语法检查通过；HTTP 冒烟通过：index.html 200/text/html、app.js 200/text/javascript、知识库列表 200/application/json、第二页文档 200、RAG ANSWERED 两个 Citation、四策略 partial 返回前三项 SUCCESS 和 HYBRID_RERANK FAILED。之后 Ctrl+C 停止。以上是 HTTP 检查，不是浏览器渲染结果。

Mock 场景：

| 操作 | 场景与预期 |
|---|---|
| 选择知识库 | Mock 多页知识库 21 文档；Mock 空知识库为空；创建写内存并返回 201 |
| 上传 | 普通 md/txt 名称返回 202；duplicate.md 返回 200；failed.md 最终 FAILED；其他任务 PROCESSING → READY |
| RAG 问题 | 普通文本 ANSWERED、empty 证据不足、failed 业务失败；答案含换行、长文本和安全显示的 script 字面量 |
| Citation | E1 长文件名、标题、正文；E2 固定 404，可测试重试失败及返回 E1 |
| Retrieval query | 普通 SUCCESS；empty 全空；failed 全失败；partial 仅 HYBRID_RERANK 失败；Diagnostics 空 stages 如实显示 |
| 创建名称/RAG 问题/检索 query | 包含 http400/http404/http500 返回对应 HTTP；network 断开连接；delay 延迟 65 秒 |

Mock 保留路径、Envelope、字符串 ID 和实际字段名；不是完整后端验证器。上传仅按 multipart 中场景文件名匹配，不执行真实解析/编码校验/内容哈希；200 场景只模拟去重状态码，不证明服务端去重身份。检索评分为模拟字段，不作为真实策略算法证据。非空阶段、证据 500/延迟、上传超时和任务查询网络恢复需用 DevTools 本地响应覆盖/节流补充，不能称已覆盖。尚未新增这些场景的自动断言；本轮仅完成上述 HTTP 冒烟。

## Windows 11 人工执行方案（全部待执行）

先启动上述 Mock，确认终端显示 Mock ONLY 且 URL 为 127.0.0.1:18080；不要启动正常应用或 IDEA 的业务启动配置。打开 Chrome/Edge DevTools，Network Preserve log、Disable cache；Console 保留日志。记录 chrome://version 或 edge://version；截图保存到独立验收目录，避免包含个人浏览器数据。Network 应只出现该 Mock 地址；如出现供应商或其他业务地址立即停止。

| 编号 | 操作及通过标准 | 当前结果 |
|---|---|---|
| B01 导航 | 首次默认 #chat；点击知识库/检索；前进后退、刷新、直接带 Hash URL；全局 KB 一致，无模块错误 | 未执行 |
| B02 创建 | 空名称、101 字名称、1001 字描述；Enter、快速双击；成功选中新库；http500/network/delay 后解锁并提示结果未知 | 未执行 |
| B03 文档 | 1001 两页及首页末页；1002 空；长文件名、发布状态、切库归第一页；用 Network Request blocking 模拟 GET 失败 | 未执行 |
| B04 上传 | 本机创建明显 Mock 的普通 md/txt、duplicate.md、failed.md；空文件、错误扩展名、大于2MiB；验证禁用、202/200、终态及发布展示不混淆 | 未执行 |
| B05 轮询 | 处理中离页暂停、返回恢复；切库清空；DevTools Offline 后恢复并手动刷新；确认 GET 不重叠且无真实 Worker | 未执行 |
| B06 问答 | Enter/Shift+Enter、真实中文 IME Enter、重复提交；普通/empty/failed；多条内存历史；切库清空；刷新清空；Network POST 只有当前问题，无 history | 未执行 |
| B07 取消 | delay 问题或查询，取消后旧响应不得回填；不取消等待超时；再次提交恢复，确认无自动付费 POST 重试 | 未执行 |
| B08 Citation | E1 打开焦点进入；Tab/Shift+Tab 不进入背景；Esc/按钮关闭恢复触发点；长内容滚动；E2 404 重试；快速 E1/E2、关闭立即重开至少20轮，观察延迟 close 竞态；切库和 Hash 离页自动关闭 | 未执行 |
| B09 检索 | 四策略独立和组合、全不选；高级参数/默认/非法值；普通/empty/failed/partial；排名与原始分数/null、Diagnostics 展开；无统一相似度百分比；切库使延迟请求失效 | 未执行 |
| B10 异常 | http400/http404/http500、network、delay；错误安全且可恢复，已有答案不因 Citation 错误丢失 | 未执行 |
| B11 Console/Network | 三页无未处理异常、资源加载失败；核对 URL/Method/Content-Type、201/202/200、Envelope、取消请求次数；保存脱敏截图/HAR | 未执行 |

文件可使用 Windows 编辑器另存 UTF-8；大文件可在临时目录生成，禁止上传个人资料。DevTools Local Overrides 只对 Mock origin 使用，结束关闭覆盖。非空 stages 应按真实 DTO 提供 phase/elapsedMillis字符串/candidateCount/candidates，并核对数量；不得伪造为后端真实诊断。

## 响应式、键盘与可访问性（全部待执行）

DevTools 设备模式分别设 1440×900、1024×768、768×1024、390×844，100% 缩放。每种宽度截三页面和打开的 Citation：导航不遮挡、表单/上传状态可读、输入区域可用、无整体横向溢出、长标题/文件名换行、抽屉不出屏、结果纵排、Diagnostics 不撑破布局。另确认横向滚动条和系统字体缩放。

鼠标移开后完整 Tab/Shift+Tab：全局选择→导航→当前页面控件顺序合理；焦点始终可见；Enter/Space 激活按钮；所有输入可访问名称正确、禁用明确；原生 dialog 焦点限制与恢复。用 Windows Narrator 人工确认 label、错误/任务状态播报、aria-live 不过量；颜色对比和字体可读性需人工/DevTools 确认。没有真实辅助技术执行结果。

## 缺陷与回归

未实际复现生产缺陷，不基于静态猜测修改 CSS/JavaScript。没有真实浏览器截图，不能声称延迟 close 竞态或响应式通过。
未修改生产代码，未重复执行 WEB-06-A 的176项行为测试、50项Maven、Spotless和静态资源回归；历史通过不算本轮通过。新增 Mock 文件 node --check 通过；本轮 HTTP 冒烟如上；git diff --check 通过。若后续修复生产 JS/CSS，再执行相关行为测试、必要全套、全部模块语法、静态 HTTP 和 Spotless。

## 交付与安全边界

修改文件：src/test/js/web-browser-mock.mjs、本文。没有修改敏感配置、M1/M2/M3、Schema 或 Migration。只启动并停止本机隔离 Mock 服务；未启动外部服务/容器；未写开发 PG/ES/MinIO；Mock 创建/任务仅改变内存。没有在线模型调用。仓库新增测试设施与文档属于本轮文件写入。

READY TO COMMIT：是，仅指 Mock 设施与诚实的阻断记录可供评审，不代表产品浏览器验收通过。READY TO CLOSE：否；上表所有真实浏览器、响应式、键盘、Console/Network、辅助技术项目仍待执行。最终 git status/diff --stat 在交付报告给出；无自动 Commit/Push/Merge/Rebase。
