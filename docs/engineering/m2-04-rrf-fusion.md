# M2-04 RRF 融合与候选去重

## 算法与协议

RrfFusionService.fuse(vector, bm25, parameters) 是无状态纯算法入口，返回不可变 RankedChunk 列表。
RRF(d) = Σ 1 / (rrfK + rank)，不使用原始分数加权或归一化。
例如 k=60，VECTOR rank=1、BM25 rank=2 得到 1/61+1/62=0.03252247488101534；单路 rank=1 为 0.01639344262295082。

仅接收两路成功且完整的 Recall.chunks；允许成功空列表，拒绝 null 列表或元素。
VECTOR 必须仅有 vectorRank/vectorDistance，BM25 必须仅有 bm25Rank/bm25Score。
复用 RankedChunk 的字段配对、正排名和有限数值校验，复用 RetrievalParameters.validate(HYBRID_RRF)。
补充校验列表中排名从 1 连续且与位置一致、数量及排名不超过对应 Top-K、同通道 ID 不重复，拒绝已有 rrfScore、rerankScore 或 finalRank。

## 合并与排序

chunkId 是唯一键。同 ID 的 documentId、indexVersionId、knowledgeBaseId、content、sourceStart、sourceEnd、headingPath 必须全部一致（包括可空 headingPath），否则抛出异常，无部分结果。
保留完整正文及来源；双路命中保留双路原始排名、距离和分数，单路命中另一通道字段为空。
完整融合后按 rrfScore 降序、chunkId 的 Long 数值升序排序，再截断 fusionTopK。
finalTopK 只由参数协议验证，本组件不按它截断；finalRank 和 rerankScore 始终为空。

## 测试与边界

RrfFusionTests 使用真实算法，无 Mock，包含公式及 k=1/60/1000、空集合、单路和双路、字段保留、七种元数据冲突、重复/非法输入、20 次重复排序及融合后截断断言。
同分 ID 2 在 10 前；双路 rank=2 的 ID30 在两条单路 rank=1 前，验证先融合后截断。fusionTopK=3、finalTopK=1 时仍返回三条。

M2-06 必须先检查两个 Recall.failure；任一路失败不得调用融合后标记成功。
调用方围绕 fuse 记录 FUSION 耗时，异常映射现有 FUSION_FAILED 并返回空失败结果，不透传证据正文、不静默降级。无需新增诊断对象。
本组件不能从列表判断上游是否失败、是否来自同一快照，调用方负责成功状态与快照复用。
未实现 Rerank、策略编排或检索调用；未验证真实 PG/ES、在线供应商、性能或质量指标。

## 执行结果

最终 `mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile` 成功。
`mvn '-Dtest=RrfFusionTests' test`：4 项通过；
`mvn '-Dtest=RetrievalFoundationTests,QueryEmbeddingTests,KeywordProtocolTests,KeywordRetrievalTests' test`：12 项通过，均无失败、错误或跳过。
首次组合测试被新增测试末尾空行格式检查拦截，修复后以上最终执行成功。
限定回归包含 Mock 通道协议测试，不代表真实 PG/ES 集成验证。纯算法新增未修改现有通道，未运行基础设施 IT 或全量 clean verify。
完整新增文件已审阅；提交前执行 git diff --check 和 git diff --cached --check。
基线 feature/m2-retrieval / 3fbc4c6739159a6a4d3565e6f35050b1062b4279，fetch 后远端差异 0/0，初始工作区干净。
结论 PASS，可进入 M2-05；未自动开始后续任务。提交 SHA 见最终回复。
