# M2-03 Elasticsearch BM25 检索

## 实现与边界

生产目标为 ka-chunks-v1，复用 ElasticsearchClient、EsIndexRepository 的只读 Mapping 校验。
content 使用 ik_max_word / ik_smart 和 BM25，不修改现有 Mapping、写入或发布流程。
检索入口 KeywordRetriever 复用 RetrievalContext，类似 VectorRetriever.Recall 提供 chunks、stages、failure 和 status。
不调用模型、不重新读取快照、不实现融合、重排或策略编排。

## Query DSL

以下为参数化请求示例，版本及 KB 都以十进制字符串发送：

```json
{
  "size": 20,
  "query": {
    "bool": {
      "must": [{"match": {"content": {"query": "Spring 知识库"}}}],
      "filter": [
        {"term": {"knowledge_base_id": {"value": "1"}}},
        {"terms": {"index_version_id": ["3"]}}
      ]
    }
  },
  "sort": [{"_score": "desc"}, {"chunk_id": "asc"}]
}
```

请求设置 allow_partial_search_results=false。size 为 bm25TopK，不应用 finalTopK。
KB 和版本 terms 在 ES 内、评分与 Top-K 前执行；Java 校验不能替代预过滤。
用户已确认将不可直接排序的 _id 改为现有 keyword chunk_id。这是字符串字典序，例如 10 在 2 前；不转换为数值排序。
读取实际 Mapping，拒绝 chunk_id 显式 doc_values=false；默认 keyword doc_values 可排序。

## 实际索引上限

每次非空检索调用目标索引 GET /{index}/_settings?include_defaults=true。
优先使用显式 max_terms_count，否则读取服务器返回的 defaults，处理 index 嵌套包装。
缺失、非正数、反序列化失败或无法确定安全值时拒绝检索，不硬编码 ES 默认值。
有效上限 min(2000, 实际值)。PublishedVersionSnapshot.versionIdsForTerms() 先去重并执行应用上限校验。
不截断、不分批拆 terms、不修改设置。仅测试隔离索引会设置较低上限 2。

## 候选与 PG 权威数据

ES 检查超时、失败 shard、候选数量、source 字段类型、正十进制 Long ID、有限非负真实 _score、重复 ID、_id 一致性以及快照 Document/Version 成对关系。
PG 只对命中 ID 发一次批量查询，LEFT JOIN 保留损坏关系供检查。
校验 KB、Document、Version、document.active_version_id、版本归属、正文、chunk_order，缺失或不一致整路失败。
正文、偏移和 headingPath 来自 PG；排序和分数来自 ES，bm25Rank 从 1 开始，其他通道字段为 null。
不创建跨存储事务。若查询期间发布指针变化，失败而不悄悄刷新快照。

## 诊断

空快照不访问 ES/PG；无匹配为空结果；应用及实际索引 terms 超限统一为 TERMS_LIMIT_EXCEEDED；其他依赖或协议错误由 KeywordRetriever 转为 FAILED / BM25_FAILED，返回空候选。
阶段为 BM25，保留真实分数和排名供 M2-04。对外不传播原始异常、响应或查询正文，不新增敏感日志。

## 验证证据

KeywordSearchIT 使用现有固定 ES 8.18.0 + IK 8.18.0 和 PG 17.6 镜像，不调用在线模型。
隔离索引复用生产 Mapping；数据为相同正文的发布 A、未发布 B、其他 KB C，验证中文、英文、混合、无匹配、同分字典序和 size。
真实索引测试包含默认上限读取、显式上限 2、等于上限和超限、错误 Document/Version、索引缺失、空快照。
真实 PG 测试覆盖批量 SQL 来源读取和正文变化拒绝，并串联 KeywordRetriever 的真实 ES/PG 请求，验证 PG 后续首次发布不进入旧快照。
KeywordProtocolTests 使用 Mockito 覆盖正常响应对照、超时标记、失败 Shard、缺失 Source、错误 ID、非有限分数、重复候选和 IO 异常；不声称验证了真实网络故障。
KeywordRetrievalTests 使用 Mockito，覆盖空快照无调用、依赖异常无部分候选、PG 缺失/正文/归属/active 指针错误。这不是网络故障证据。

执行命令：

```powershell
mvn spotless:apply spotless:check -DskipTests test-compile
mvn '-Dtest=KeywordProtocolTests,KeywordRetrievalTests,EsIndexTests,EsIntegrityTests,RetrievalFoundationTests' test
mvn '-Dit.test=KeywordSearchIT,EsIndexIT,EsIntegrityIT,RetrievalFoundationIT,VectorSearchIT' failsafe:integration-test failsafe:verify
git diff --check
git diff --cached --check
```

首次验证存在并行 Maven 编译干扰测试 classpath 的错误，已废弃该轮验收结果；最终串行结果见收尾记录。

## 已知限制及 M2-04 前置条件

未做性能/质量评估、真实 TCP 中断或超时注入。畸形 ES 响应、shard 失败和 IO 异常属于 Mock 协议故障测试，未执行真实 ES 网络断线。
没有缓存框架；每个非空请求检查实际 Mapping 和设置，有额外元数据请求成本。
M2-04 应复用入口快照和真实候选排名，不把 BM25 分数与向量距离相加。


## 收尾结果（2026-11-10）

分支 feature/m2-retrieval，起始 HEAD b4e55845669b4d025eabeb091d4e263f91ad7d79，开始时工作区干净。
最终 Spotless apply/check 和 test-compile 成功。

| 测试类 | 通过 | 失败/错误/跳过 |
| --- | ---: | --- |
| KeywordProtocolTests（Mock） | 1 | 0/0/0 |
| KeywordRetrievalTests（Mock） | 3 | 0/0/0 |
| EsIndexTests | 6 | 0/0/0 |
| EsIntegrityTests | 2 | 0/0/0 |
| RetrievalFoundationTests | 3 | 0/0/0 |
| KeywordSearchIT（真实 ES/PG，含继承的 ES 基线用例） | 3 | 0/0/0 |
| EsIndexIT | 4 | 0/0/0 |
| EsIntegrityIT | 2 | 0/0/0 |
| RetrievalFoundationIT | 2 | 0/0/0 |
| VectorSearchIT | 1 | 0/0/0 |
| 合计（唯一用例，不累加重跑） | 27 | 0/0/0 |

指定五类集成回归串行执行 12 项通过；仅测试补充后，单独重跑 KeywordSearchIT 3 项通过。
最终单元测试命令含 KeywordProtocolTests，共 15 项通过。
真实 ES 已验证同分字典序、不同分数降序、Top-K 前过滤、terms 去重后等于上限 2 允许而 3 拒绝。
真实 PG/ES 串联验证旧快照不包含后续发布版本，正文变化返回 FAILED。
生成证据位于忽略的 target/surefire-reports 与 target/failsafe-reports，不提交生成报告。
中间补充测试时出现过格式检查和缺少 import 的编译错误，均已修复并重跑；最终结果不包含这些失败轮次。
未运行全量 clean verify、在线模型、真实网络中断或性能评估。未 Push、Merge、Rebase 或开始 M2-04。
最终提交 SHA 由任务回复提供，避免文档自引用。BM25 候选协议可供 M2-04 使用。

## M2-03 审查修复验证（2026-11-10）

基线 458b83bc956a36a185a08b7bddfe8960a1a853c6，工作区起始干净。
使用 TermsLimitExceededException 统一应用 2000 上限和实际索引上限；Retriever 按类型分类，不匹配异常消息，也不携带供应商响应或查询正文。
嵌套 index.maxTermsCount 为 null 时继续读取外层，再回退服务器 defaults；缺失或非正数仍拒绝检索。BM25 查询 DSL 未修改。

最终 Spotless apply/check、test-compile 成功。相关五类单测共 16 项通过，失败/错误/跳过均为 0。
新增 Mock 覆盖嵌套空字段的外层回退、defaults 回退、无安全值拒绝、实际上限分类；应用 2001 个唯一版本验证不访问依赖且分类正确。
网络 IO、超时及畸形协议仍检查 BM25_FAILED 且不包含原始 cause；不声称执行真实断网注入。
中间新增测试后的格式检查失败已修正，最终单测已重跑通过。

真实 ES/PG 回归命令沿用上文五类 IT，共 12 项通过（EsIndexIT 4、EsIntegrityIT 2、KeywordSearchIT 3、RetrievalFoundationIT 2、VectorSearchIT 1），失败/错误/跳过均为 0。
KeywordSearchIT 验证实际上限 2、3 个唯一版本在 Retriever 返回 TERMS_LIMIT_EXCEEDED，并继续通过未超限的真实 BM25 排序、过滤和分数检查。
完整 diff 已审阅；git diff --check 和 git diff --cached --check 均通过。
未运行全量 clean verify、在线供应商 API、真实 TCP 故障注入或性能评估。未 Push、Merge、Rebase，未进入 M2-04。
