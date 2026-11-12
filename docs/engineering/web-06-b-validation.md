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
| 上传 | 新内容返回 202；同库相同内容再次上传返回 200 和原任务（改名仍去重）；failed.md 最终 FAILED；其他任务 PROCESSING → READY |
| RAG 问题 | 普通文本 ANSWERED、empty 证据不足、failed 业务失败；答案含换行、长文本和安全显示的 script 字面量 |
| Citation | E1 长文件名、标题、正文；E2 固定 404，可测试重试失败及返回 E1 |
| Retrieval query | 普通 SUCCESS；empty 全空；failed 全失败；partial 仅 HYBRID_RERANK 失败；Diagnostics 空 stages 如实显示 |
| 创建名称/RAG 问题/检索 query | 包含 http400/http404/http500 返回对应 HTTP；network 断开连接；delay 延迟 65 秒 |

Mock 保留路径、Envelope、字符串 ID 和实际字段名；不是完整后端验证器。上传按浏览器单文件 multipart 字段提取文件内容，以同库 SHA-256 内容哈希关联原文档和任务；仅是测试设施，不是完整生产 multipart/编码校验器。检索评分为模拟字段，不作为真实策略算法证据。非空阶段、证据 500/延迟、上传超时和任务查询网络恢复需用 DevTools 本地响应覆盖/节流补充，不能称已覆盖。尚未新增这些场景的自动断言；本轮仅完成上述 HTTP 冒烟。

## Windows 11 人工执行方案（全部待执行）

先启动上述 Mock，确认终端显示 Mock ONLY 且 URL 为 127.0.0.1:18080；不要启动正常应用或 IDEA 的业务启动配置。打开 Chrome/Edge DevTools，Network Preserve log、Disable cache；Console 保留日志。记录 chrome://version 或 edge://version；截图保存到独立验收目录，避免包含个人浏览器数据。Network 应只出现该 Mock 地址；如出现供应商或其他业务地址立即停止。

| 编号 | 操作及通过标准 | 当前结果 |
|---|---|---|
| B01 导航 | 首次默认 #chat；点击知识库/检索；前进后退、刷新、直接带 Hash URL；全局 KB 一致，无模块错误 | 未执行 |
| B02 创建 | 空名称、101 字名称、1001 字描述；Enter、快速双击；成功选中新库；http500/network/delay 后解锁并提示结果未知 | 未执行 |
| B03 文档 | 1001 两页及首页末页；1002 空；长文件名、发布状态、切库归第一页；用 Network Request blocking 模拟 GET 失败 | 未执行 |
| B04 上传 | 本机创建明显 Mock 的普通 md/txt、相同内容再次上传（可改名）、failed.md；空文件、错误扩展名、大于2MiB；验证禁用、202/200、终态及发布展示不混淆 | 未执行 |
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


## Mock 上传列表缺陷修复（人工发现后的追加记录）

修复基线：feature/web-ui-v1，HEAD 27fdf637deb0e4321d075da61807f5eedfee2db9，开始时工作区干净。用户在 Windows Chrome、http://127.0.0.1:18080/api/index.html 人工确认创建、切库、分页、文件校验、上传与轮询正常，但 READY 后及刷新页面后均看不到新文档。这是用户提供的真实浏览器观察，不是本轮代理执行浏览器的结果。

根因属于 Mock 数据模拟：POST 只存 polls/failed，所有上传返回固定 documentId=2001，没有关联知识库的文档；任务 GET 只改变返回状态，未模拟发布；文档 GET 每次生成固定21/0条列表。不是前端遗漏刷新或新记录落在后页。代码链路已核对：import.js accept 在 READY/FAILED 调用 refreshDocuments；knowledge.js 传入 () => load(1)，随后查询当前知识库第一页，校验代次并更新 DOM。原列表根本没有上传数据，所以刷新页面也无法解决。

最小修复限定 web-browser-mock.mjs：按知识库保存内存文档；上传生成独立 document/task/version ID 并关联；初始 UNPUBLISHED/activeVersionId=null；任务 PROCESSING 后在模拟 READY 时同步 PUBLISHED、活动版本、publishedAt 和最新任务摘要；FAILED 保持未发布。列表读取内存并按 createdAt DESC、ID DESC 排序，动态 total/pageSize/切片；同库文件内容哈希去重，返回已有任务，不按 duplicate 文件名假造200。新建知识库初始化独立列表。刷新浏览器不重启服务器，数据仍保留；重启进程重置初始夹具。该状态机仅模拟发布事实，没有执行 Worker、Embedding、ES 或 MinIO。

新增 web-browser-mock.test.mjs：独立随机端口子进程，结束自动停止。4项 HTTP 回归覆盖新文件 RECEIVED→PROCESSING→READY、发布前后字段、任务与 documentId 对应、21→22条/第二页2条/分页无重复、重新加载入口及列表保留、同内容改名去重及 READY 去重、不同内容独立、失败终态不发布、跨库及新建库隔离、新进程重置。HTTP 重读不等于真实浏览器刷新证据。

本轮执行：全部 JavaScript 行为与新增 HTTP 测试180项通过，0失败/取消/跳过；Mock及测试文件 node --check 通过；全部8个生产静态模块语法通过；git diff --check 通过。未修改生产JS/CSS/Java，因此未重复 Maven、Spotless、静态Servlet HTTP测试。仅启动自动测试所用隔离 Mock 子进程，测试后停止；没有真实基础设施、业务存储写入或在线模型调用。

待重新人工验收两项：

1. 停止旧 Mock 并用新代码重新启动（node src/test/js/web-browser-mock.mjs），Chrome 打开原 URL；选 Mock 多页知识库，在第二页上传具有新内容的 Mock Markdown。确认任务 READY 后自动回第一页，新文件位于顶部、总数22、PUBLISHED、第二页2条。Network 应有终态任务 GET 后的 documents?page=1&pageSize=20；切库不显示该文档。
2. 保持同一服务器进程，刷新 Chrome 并重新选择上传的知识库，确认文档仍在；同内容再上传应200、同documentId/任务且total不增加。可上传不同内容 failed.md 确认FAILED/UNPUBLISHED。最后重启服务器，确认上传记录消失、恢复21/0条初始夹具（纯内存预期）。

上述两项修复后浏览器复验尚未执行；WEB-06-B READY TO CLOSE 仍为否。READY TO COMMIT：是，仅限此次 Mock 修复、测试及记录；未自动提交。
