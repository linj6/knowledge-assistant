# M4-D03-B1 后端人工评审

## 入口与边界

沿用现有 `/api` MVC servlet path，API 为：

- `GET /api/v1/evaluation/runs/{runId}/review`
- `PUT /api/v1/evaluation/runs/{runId}/review`
- `GET /api/v1/evaluation/runs/{runId}/score`

仅 app.evaluation.enabled=true 注册。GET review 返回 run（含 manifest 快照及完整实际 execution）、Loader 加载的完整 gold、review、reviewStatus、后端 citationValidity。execution.context 中保留 SelectedEvidence、evidenceMap 与模型上下文，retrieval 保留实际候选和诊断。C01/C02 返回 422 ANSWER_REVIEW_NOT_APPLICABLE。

PUT 草稿示意（占位身份须替换为 GET 返回的真实值）：

```json
{
  "runId": "实际 runId",
  "caseId": "Q01",
  "datasetVersion": 1,
  "freezeFingerprint": "57c202a788d20e86a2ed166776c77dab6e66bed8b4ae08a1a925806458a5dbca",
  "snapshotHash": "manifest.snapshot.snapshotHash",
  "reviewer": "人工评审人",
  "basis": "实际回答及被引用正文",
  "reviewStatus": "PENDING",
  "contextAnswerable": "PENDING",
  "appropriateness": "PENDING",
  "factCoverage": {},
  "claims": [],
  "claimsComplete": false
}
```

响应为 Saved record：`{judgments: 上述人工输入, reviewedAt: 服务端时间, knowledgeAnswerable: Gold布尔值, citationValidity: 后端结果}`。确认时改为 CONFIRMED，并完成 reviewer/basis、上下文判断、appropriateness、全部 required Fact 判断、Claim 清单及其 correctness/support；Claim 引用只能使用实际回答的 Citation evidenceId。Claim 使用已有 AnswerMetrics.Claim。PASS 不代表系统自动核实自然语言；人工必须阅读实际引用正文，不能用未引用的 SelectedEvidence 补证。

评分响应示意（未评审的 COMPLETED Q01）：

```json
{"caseId":"Q01","executionStatus":"COMPLETED","reviewComplete":false,"appropriateness":"PENDING","correctness":"PENDING","completeness":"PENDING","validity":"PENDING","support":"PENDING","wholeCase":"PENDING","requiredCount":0,"coveredCount":0}
```

requiredCount/coveredCount 沿用冻结评分器待审结果的 0，不是 Gold 总数；完整事实清单在 review 材料中。

## 分层与持久化

AnswerReviewController 仅映射 HTTP；AnswerReviewService 读取并验证 Run 与 Gold、校验人工输入、构造 AnswerMetrics.Review；AnswerReviewStore 负责 JSON 边界及原子文件替换；AnswerReview 定义强类型人工输入、已保存记录和材料响应。

保存至 `data/evaluation/{runId}/review.json`。不改写 manifest.json、results.json、report.md。runId、caseId、datasetVersion、freezeFingerprint、snapshotHash 必须匹配，服务端生成 reviewedAt、knowledgeAnswerable、citationValidity。同题不同 Run 独立保存。单用户 V1 不增加锁、账户或数据库。

执行状态、ragStatus、answer、answerExplicitNull、实际 citations、error 均来自持久化 Run。Gold 来自 EvaluationDatasetLoader.full()，先复用原 load 校验冻结指纹与协议。评分器从 test 移至 main，指纹调用改为已有 FrozenFingerprint；calculate/summarize 逻辑不变。Citation Validity 重新调用既有 CitationValidator，比较实际 RagCitation 身份；语义支持由人工 Claim 判断提供。

五维及 Whole-case 继续由唯一正式 AnswerMetrics 计算：FAIL 优先于 PENDING；NOT_READY 不适用；FAILED Whole-case FAIL，不当作拒答；合法拒答按原协议处理。summarize 分母仍为 PASS+FAIL，NA/PENDING/NOT_READY 不入分母，零分母 value=null。单题 score 不发布整个数据集通过率。

## 回归边界

BaselineAnswerMetrics 只在测试源码保存基线提交的评分器快照，用于 30 组固定合成输入与汇总的逐 JSON 字段差分，正式 API 不引用它。仓库未发现 30 条正式人工评分产物；这些回归输入不能冒称为真实人工评审结果。

本地 Q01 Run 只读测试按文件可用性执行，缺失时明确跳过。草稿、确认、身份、Fact/Citation 拒绝、失败及 NOT_READY 用 Stub 验证，不调用模型或 Docker。具体执行结果见本次交付报告。
