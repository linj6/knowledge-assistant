# M4-D03-B2 浏览器人工评审

## 文件与流程

`evaluation-review.js` 独立负责材料加载、证据卡片、Fact/Claim 表单、保存确认、只读与后端评分展示；`evaluation.js` 在 Answer Run 详情后挂载，并沿用历史选择的 revision 防止旧响应覆盖。C01/C02 保持原检索指标页面，不挂载评审模块。

`api.js` 保留错误响应的 detail/reason 供安全文本提示；不改变裸 JSON 的 request raw:true 约定。`app.css` 补充评审表单和折叠证据样式。交互回归扩展 `web-evaluation.test.mjs`，不引入框架。

选择历史 Answer Run → GET review → 展示完整 gold（不取 results.case 作为 Fact 来源）及 review.judgments → GET score。初始判断均为 PENDING，claimsComplete 默认不选中；不自动设置 PASS 或上下文可答。

Citation 卡片显示实际引用及其对应 SelectedEvidence 正文；实际上下文、SelectedEvidence 与 Retrieval Candidates 独立折叠展示。Claim 引用仅能从实际 Citation 勾选，新 Claim ID 单次表单内递增且不复用已删除 ID；已保存 ID 原样恢复。大整数身份沿用字符串，不进行 Number 转换。不可信正文、Gold 与用户输入仅用 textContent/value 渲染。

## API 与状态

三个 Review API 均通过 request(...,{raw:true}) 读取裸 JSON。

- 保存草稿：PUT reviewStatus=PENDING，允许必要判断待审，成功后重新 GET review/score。
- 确认：检查 reviewer/basis、正常回答必要判断及 claimsComplete；support=PASS 必须实际关联引用；浏览器二次确认告知 V1 确认后无法修改。后端仍是最终校验方。
- CONFIRMED：所有人工输入禁用，不显示保存及确认按钮；重开按服务器状态恢复只读。
- FAILED：只提供 reviewer、basis 及保存/确认，不提交虚构质量标签；后端 N/A 原样展示。
- INSUFFICIENT_EVIDENCE：不强制添加 Claim 或引用，允许按实际拒答说明及 Gold 边界评审。
- NOT_READY：显示原因，不提供确认按钮。

PUT 执行期间禁用控件，防止重复提交。409 显示 REVIEW_ALREADY_CONFIRMED 并重新 GET。其他 HTTP/网络异常不重复 PUT，先 GET 服务器状态；重读失败提供显式重读按钮。切换 Run 后旧响应及旧操作不得写入新页面。

评分区仅展示 GET score 的五维与 Whole-case、reviewComplete、requiredCount、coveredCount；保留 NA→N/A、PENDING、NOT_READY，不计算通过率或任何前端质量评分。

## 验证边界

使用现有 Node VM DOM Mock 与 Stub fetch 完成交互测试；不执行真实模型、Embedding、Rerank、Docker 或 Maven。本轮未进行真实浏览器布局、键盘及端到端 HTTP 验收，交付状态仅为 READY FOR D03-B2 BROWSER VALIDATION。测试中的 Q01 采用冻结 Gold 与符合 API 结构的 Stub，不冒称为真实模型评审。

实际验证：`node --experimental-vm-modules --test src/test/js/*.test.mjs` 对应的现有前端测试共 207 项通过（其中 web-evaluation 15 项），失败、跳过均 0；evaluation-review.js、evaluation.js、api.js 的 node --check 通过，git diff --check 通过。未执行 Maven 或真实模型请求。
