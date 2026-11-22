# M4-D02 Answer Evaluation Metrics

## 范围与 API

AnswerMetrics 位于测试源码集，仅消费冻结 Gold 与离线人工评审 JSON。`calculate(dataset, goldCase, review)` 返回单题 Result；`summarize(dataset, reviews)` 按整个数据集返回 Summary。缺少的题目仍保留在结果中；未知或重复评审 caseId 拒绝汇总。记录、结果及汇总可通过现有 Jackson 序列化，不创建正式评分文件。

Gold 校验 FROZEN、协议、全部 Case/Fact CONFIRMED，并复用 FrozenRankingEvaluation 的递归排序指纹实现；评审绑定 caseId、datasetVersion、freezeFingerprint。调用者须通过后续运行记录引用保证快照/来源适用，本模块不连接数据库或重新验证发布身份。

## 人工输入

以下仅为输入结构示意，不代表实际用户评审或正式质量结果。reviewStatus 为 CONFIRMED / PENDING；未确认评审的质量结果为 PENDING；拒绝/未知标签或身份不符为 NOT_READY。judgment 使用 PASS / FAIL / PENDING；其他或缺失判断保守记 PENDING。

```json
{
  "caseId": "Q01",
  "datasetVersion": 1,
  "freezeFingerprint": "冻结数据集指纹",
  "runRef": "受控离线运行记录的身份及正文引用",
  "executionStatus": "COMPLETED",
  "ragStatus": "ANSWERED",
  "answer": "实际答案",
  "citations": ["E1", "E2"],
  "knowledgeAnswerable": true,
  "contextAnswerable": true,
  "appropriateness": "PASS",
  "factCoverage": {"Q01-F01": "PASS"},
  "claimsComplete": true,
  "claims": [{"claimId": "C1", "text": "实际实质断言", "correctness": "PASS", "support": "PASS", "citationIds": ["E1", "E2"]}],
  "citationValidity": "PASS",
  "reviewStatus": "CONFIRMED",
  "reviewer": "实际人工评审人",
  "basis": "人工评审依据"
}
```

COMPLETED / FAILED / NOT_RUN 是执行状态，ragStatus 沿用 RagStatus。FAILED 使用 `error` 保存错误码，answer=null、citations=[]；CITATION_INVALID 作为已记录校验失败证据，不创建丢弃的答案。空结果拒答可 answer=null。claimsComplete 是人工对全部实质断言已枚举的声明，不能仅凭 claims=[] 推断没有事实断言。共同 reviewer/basis 适用于整份标签，逐 Claim 可附额外解释。runRef 可携带选中正文/快照归档引用，本计算不读取其外部资源。

## 计算规则

- Appropriateness 使用已完成的人工判断，并核对 Context：充分上下文拒答、不充分上下文给出目标答案为 FAIL；上下文未判断记 PENDING。Knowledge 与 Gold 不一致为 NOT_READY。
- Correctness：任一 Claim FAIL 即 FAIL；无失败但有缺失判断或清单不完整为 PENDING；清单完整且无实质 Claim 为 N/A，否则全部 PASS 才 PASS。
- Completeness：逐题 REQUIRED 为 ALL；缺一条 FAIL，无缺失但未审为 PENDING。OPTIONAL 不进入分母，不可答目标为 N/A；可答题拒答 FAIL。Result 保存 requiredCount/coveredCount 供诊断，不把全数据集45条当作单题分母。
- Validity 消费正式后端校验结论，不重写 CitationValidator；ANSWERED 仍须非空答案与引用。合法无引用拒答 N/A。执行失败通常 N/A，已记录 CITATION_INVALID 为 FAIL。
- Support 仅评 ANSWERED：每个 Claim 人工确认的引用集合可联合支持；引用必须属于实际集合；混入无关引用 FAIL。清单/必要判断未完成记 PENDING，拒答 N/A。不用相似度、关键词或模型推断语义。
- Whole-case：身份/Gold 不适用 NOT_READY；NOT_RUN PENDING；执行 FAILED 直接 FAIL；其他任一适用维度 FAIL 即 FAIL，无失败但有 PENDING 即 PENDING，其余 PASS。Knowledge TRUE / Context FALSE 的合理拒答可 Appropriateness PASS，但 Completeness FAIL。

每维及 Whole-case 输出 PASS/FAIL/NA/PENDING/NOT_READY 数量、numerator、denominator、未舍入 double value。NA 为序列化的 N/A。分母仅 PASS+FAIL；零分母 value=null、status=UNDEFINED。执行失败进入有效整例失败分母，不能伪装成拒答。Summary 另报整个数据集题数、已执行数、执行失败数、人工完成数、待审数及身份不适用数；已执行数可包含身份不适用记录，不能当成质量分母。人工完成数要求各适用维度没有 PENDING；执行失败不冒充已完成人工语义评审，单列执行失败并保留待审数量。

## 验证及能力边界

AnswerMetricsTests 使用内存合成标签，覆盖正确回答、必要事实遗漏、可选事实不影响完整性、错误 Claim、联合引用、合法但不支持/无关引用、未引用证据、合理及错误拒答、执行失败/非法引用、缺失评审、身份或 Gold 篡改、零分母、汇总及 JSON 序列化。没有正式人工评分结果或模型质量结论。

本轮不实现 Runner、实际 RAG 调用、来源归档读取或人工评审界面。不自动核验自然语言标签真实性；claimsComplete 和语义判断由人工负责，Citation Validity 的生产证据由后续运行记录提供。冻结 Gold、生产代码及依赖不变。

## 本轮实际验证

在 feature/m4-rag-evaluation、基线 e41a87699ef9d23c7fc1c9d0aca6510c78c69a8b 上执行 Spotless apply/check、test-compile，以及 `mvn test -Dtest=AnswerMetricsTests` 的定向测试目标（实际合并命令为 `mvn spotless:apply spotless:check test-compile test "-Dtest=AnswerMetricsTests"`）。6项测试全部通过，失败/错误/跳过均0；本机日志 target/m4-d02-tests.log。仅两份新增 Java 被格式化，未修改既有 Java 或 pom.xml。

已检查完整新增文件、空白及冻结 Gold 与 HEAD 无差异。用户已有 application-local.yml 修改保留，不纳入本轮。未运行全量回归、Docker、PG/ES、真实 RAG 或在线模型；无新增 Commit/Push/Merge。READY FOR M4-D03：是，表示离线指标模块已具备使用条件，不表示模型质量通过或本轮授权运行 D03。
