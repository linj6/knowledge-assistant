# WEB-06 UI Polish 验收记录

## 基线与范围

2026-11-12；feature/web-ui-v1；HEAD 13496c721bbef5619f3cfee78ccac8c85e212e04，开始工作区干净。无提交、推送或历史修改。
依据当前源码与产品设计、WEB-06-A/B 记录。用户报告 Windows Chrome 已完成主要区域核心操作，并确认重启新 Mock 后上传列表恢复；这不作为本轮新布局的视觉验收证据。

## 问题与实际修改

- AI 问答：问题与回答标题、正文原先层级相近，输入区较高。增加局部展示类，问题使用轻量灰底，标题缩小，回答保持纯文本换行和完整内容；引用按钮紧凑排列，requestId 降为辅助文本；输入区缩至88px最小高度，操作按钮不再占整行。未强制截断答案。
- Citation：将关闭按钮与标题放入紧凑工具栏，文件名从大标题改为完整小字号文本，标题路径、区间与原文分区。原文区域限制55dvh并独立滚动，抽屉保留外层滚动。没有更改任何 open/close、取消、重试、代次或身份校验逻辑。
- 知识库：文档大卡片改为分隔线紧凑列表；文件名、创建时间、发布状态和最新任务状态优先呈现，失败分类保留。原生 details 承载发布时间和文档/活动版本/任务技术字段，完整字符串展示。上传面板使用辅助背景，任务状态节点增加 role=status，分页及上传状态机不变。
- 检索：Diagnostics 移至策略标题后、Chunk之前；正文默认约4行高度，用真实按钮切换“展开全文/收起正文”，通过 aria-expanded 表示状态，完整 content 始终以 textContent 保留。评分以两列小字号展示，null仍为不适用；技术元数据以原生details折叠。服务端顺序不变。策略选择桌面两列、窄屏单列，高级参数标签/输入更紧凑。
- 全局：统一标题、辅助文本、按钮尺寸、边框和间距；主操作蓝色，禁用灰色；维持暖白主题、可见焦点和原生控件，无依赖、动画或装饰性组件。

保留现有API、Long字符串、BigInt分页、共享知识库、单轮请求、Citation身份、四策略评分含义和全部Diagnostics字段。没有修改api.js/state.js/app.js，未变更请求体、参数规则、异步生命周期和自动重试行为。未新增Memory或业务能力。

## 测试与结果

实际命令：

```powershell
node --experimental-vm-modules --test src/test/js/*.test.mjs
Get-ChildItem src/main/resources/static/js/*.js | ForEach-Object { Get-Content -Raw $_ | node --input-type=module --check }
mvn '-Dtest=WebStaticResourcesTests,RetrievalDemoTests' test spotless:check
git diff --check
```

- JavaScript 全量184项通过，0失败/取消/跳过；历史180项全部重跑。本轮新增4项：正文默认摘要→展开→收起，完整不可信长文本不丢失且无额外请求；Diagnostics与元数据独立折叠及入口顺序；紧凑文档行长文件名、状态与详情；证据来源/正文安全渲染。
- 一次新增证据测试误用了fixture返回对象属性，修正测试调用后全套重跑184通过。生产行为未因该错误修改。
- 既有测试继续覆盖Citation关闭/重开与延迟close、单轮请求/IME、上传轮询、取消/迟到响应、四策略参数/状态/完整诊断。
- 全部8个静态JS模块语法通过；git diff --check通过。
- Maven 2项通过，0失败/错误/跳过，Spotless通过。执行前审查静态设施：排除DataSource/Flyway，只导入Web配置与Mock服务；演示页独立Web配置。没有Testcontainers或真实存储依赖。
- WebStaticResourcesTests使用随机端口嵌入式Spring Boot和Java HttpClient，确认入口、演示页、CSS及全部8个模块200、非空与正确MIME；不是MockMvc，更不是浏览器截图验证。没有生产Java改动，不重复后端业务集成测试。

## 响应式及可访问性边界

静态检查：minmax(0,1fr)、控件max-width/min-width、标题与元数据overflow-wrap、pre保留换行；767px断点切换导航和评分/策略单列；抽屉宽度不超过100vw。1440/1024/768/390均为待真实浏览器复验，不能从CSS宣称无溢出。
原生details/summary提供键盘折叠；展开按钮有名称与aria-expanded；原生dialog、Esc和焦点恢复代码未改；状态带role=status，焦点样式保留。Node使用轻量DOM模拟，没有浏览器排版、焦点限制或屏幕阅读器证据。当前控制通道此前native pipe不可用，本轮按授权完成代码及自动化验证，不重试失败服务。

Chrome人工复验（使用隔离Mock，禁止正常业务应用）：

1. 当前Mock每次读取静态文件，无需因本轮CSS/JS修改重启；Ctrl+Shift+R禁用旧缓存，确认URL为127.0.0.1:18080/api/index.html。
2. 1440/1024/768/390宽度分别检查三个页面、20条长文档名、上传和分页，不应整体横向溢出；查看长回答及多条历史。
3. 四策略结果：Diagnostics入口位于标题附近；默认正文约4行，按钮可展开完整文本并收起；评分和技术元数据可读，展开折叠不产生Network请求。验证所有原始评分、null、阶段及候选仍在。
4. Citation长文件名/标题不占据大标题空间，原文滚动；Tab/Shift+Tab、Esc、关闭后焦点、E1/E2、关闭立即重开、404重试、切库离页。确认独立滚动区键盘可操作；如浏览器不自动允许聚焦滚动区，记录实际表现再修复。
5. 验证问答Enter/Shift+Enter/真实IME、上传READY刷新、分页切库归零、取消迟到响应；Console无未处理异常，Network无额外POST或外部请求。用Narrator检查状态播报及对比度/字号。

## 限制与交付

生产改动仅CSS与五个展示模块；另改三个测试及本文。无后端、数据库、Flyway、Prompt、配置或算法变更。无真实Worker、PG、ES、MinIO或在线模型调用；仅自动测试启动隔离Mock子进程和嵌入式Web服务器，测试结束关闭。未操作开发数据。
已知限制：本轮没有真实截图、布局测量、原生焦点或辅助技术验收；正文摘要高度由CSS控制，Node只验证状态和原文保留。没有已确认未修复的功能回归。
READY TO COMMIT：是，代码及自动化范围通过；不代表WEB-06最终验收关闭，真实视觉复验仍待执行。没有新Commit SHA。
