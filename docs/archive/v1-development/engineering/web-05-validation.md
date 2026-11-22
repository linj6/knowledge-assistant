# WEB-05 自动化验收记录

## 范围与页面
分支 feature/web-ui-v1，HEAD f47b24af07f2d50bec9b755b9a4293f78a60f354。保留现有工作区，未提交、推送、合并或改写历史。

retrieval.js 创建查询表单、四策略多选、默认折叠高级参数、恢复默认、取消等待、纵向策略卡片及折叠 Diagnostics。app.js 仅增加模块初始化，app.css 仅增加检索页样式。不修改 Java 后端、数据库、配置或 M1/M2/M3。

## 实际契约
POST /api/v1/retrieval/search：knowledgeBaseId 为十进制字符串，query 为非空文本，strategies 固定 VECTOR、BM25、HYBRID_RRF、HYBRID_RERANK 顺序筛选，parameters 为五个整数。默认 20/20/60/20/5；TopK 1～100，rrfK 1～1000。finalTopK 不超过各选中策略候选池，混合 fusionTopK 不超过两路 TopK 之和。

Result Envelope、HTTP 和策略业务状态分层判断。results 策略集合必须匹配请求，无重复；按响应顺序显示，不排序 Chunk。SUCCESS 有 Chunk，EMPTY_RESULT/FAILED 无 Chunk。失败单独展示，不隐藏成功策略。

实际没有独立 RetrievalStage 类：阶段为 RetrievalDiagnostics.Stage。DTO 明确要求 candidateCount 等于 candidates.size，阶段不重复，耗时非负。elapsedMillis 字符串原样显示并检查 Long 上界；数值形式仅接受安全整数。ID 始终为字符串。

当前 DTO 禁止 degraded=true；非失败 actualStrategy 必须等于 requestedStrategy，失败 actualStrategy=null。因此降级或不一致响应显示协议错误，不伪造策略。Diagnostics 展示 requestedStrategy、actualStrategy、degraded、status、snapshotHash、parameters、stages 和 error；空阶段明确提示，不反推候选或补造失败原因。

Chunk 保留 finalRank、归属 ID、标题、正文、来源区间、通道排名与分数。Vector Distance 为距离；BM25、RRF、Rerank 各保留评分语义，不能跨通道统一比较，不归一化；null 显示不适用。所有业务文本使用 textContent。

## 审查与修复
发现失败结果的 snapshotHash 缺少类型检查，可能把对象显示为有效诊断；补充非 null 必须是字符串的校验及回归测试。未发现需要改动后端契约的问题。复核模块的校验、请求生命周期与渲染职责，未引入新抽象或复制问答模块。

请求期间拒绝并发；AbortController 加请求代次保护，切库、Hash 离页、取消、超时均使旧回调失效。迟到响应不能覆盖新结果或释放新请求锁。只在主动提交时发一个 POST，无自动重试。取消不代表服务端停止。

## 自动化结果
- WEB-05：53 项通过。覆盖四策略独立成功和空结果、四策略同时返回、部分/全部失败、HTTP/Envelope/业务/网络错误、策略缺失/重复/集合不匹配；Chunk 原始字段、null、安全文本、大 ID 和顺序；完整 Diagnostics、空候选、失败阶段、大 Long、非法耗时/评分/数量；切库、Hash 往返、取消、超时、重复提交、迟到响应；输入、候选池、恢复默认和折叠不请求。
- WEB-03/WEB-04 既有 JavaScript：123 项通过；总计 176 项，0 失败。
- 全部静态 JavaScript 模块语法检查通过。
- Maven RetrievalApiTests 7、RetrievalServiceTests 18、RetrievalDemoTests 1、WebStaticResourcesTests 1，共 27 项通过。
- Spotless check 通过；git diff --check 通过。

执行命令：
```powershell
node --experimental-vm-modules --test src/test/js/web-retrieval.test.mjs
node --experimental-vm-modules --test src/test/js/*.test.mjs
Get-ChildItem src/main/resources/static/js/*.js | ForEach-Object { Get-Content -Raw $_ | node --input-type=module --check }
mvn '-Dtest=RetrievalApiTests,RetrievalDemoTests,RetrievalServiceTests' test spotless:check
mvn '-Dtest=WebStaticResourcesTests' test spotless:check
git branch --show-current
git rev-parse HEAD
git status --short
git diff --stat
git diff --check
```

执行前审阅测试配置：服务依赖均 Mock，静态测试使用独立嵌入式服务器，排除数据库/Flyway 或仅导入 Web 配置。不运行 Testcontainers、Docker、PG、ES、MinIO 或真实模型。未执行全量 Maven 集成测试。

## WEB-06 待办及限制
真实浏览器视觉、键盘、移动端与真实检索模型联调未执行。Node 使用轻量 DOM 模拟，不能代替浏览器布局与原生控件行为。已有静态回归验证入口和原有模块，retrieval.js 的加载由 Node 模块链接验证，未单独验证该新资源的 HTTP MIME。

VECTOR/混合策略在实际配置下可能产生在线 Embedding 费用，HYBRID_RERANK 还可能产生 Rerank 费用；页面已提示，测试仅模拟响应。不同策略可能共享阶段，耗时不可简单相加作为请求总耗时。

## READY TO COMMIT
是：本轮前端自动化范围通过，未发现阻断缺陷。真实浏览器和真实模型验证按授权留待 WEB-06。未执行任何 Git 提交操作。
