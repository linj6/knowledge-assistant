# M2-02 pgvector 精确向量检索

## 实现及接口

QueryEmbeddingService 使用现有 EmbeddingModel，检查实际 OpenAiEmbeddingModel 的模型 text-embedding-v4、VectorSchema 1024 维、FLOAT 编码及 maxRetries=0。请求只执行一次 call，复用 ModelResponseChecks 校验单结果、index=0、维度及有限 float，复用 ModelFailures 隐藏供应商正文。拒绝事务内调用；不持久化查询向量。QueryVector 防御性复制并拒绝零向量（余弦距离无定义）。生产模型 Bean 及查询服务受原 enabled 开关控制。

VectorSearchRepository 封装 Mapper，空快照不发 SQL。VectorRetriever 使用传入 RetrievalContext，不重新读取快照；retrieve(context) 生成一次向量，retrieve(context, QueryVector) 复用已有向量。通道返回 Recall（chunks、stages、failure、status），供 M2-06 汇总统一诊断；不冒充完整策略执行结果。vectorRank 从 1 起，其他通道和 finalRank 留空。没有新增依赖、迁移、ANN 或策略框架。

## 精确 SQL 和可见性

完整 SQL 位于 src/main/resources/mapper/retrieval/VectorSearchMapper.xml。核心形式：

```sql
SELECT ..., c.embedding <=> :query_vector AS distance
FROM document_chunk c
JOIN document d ON d.id=c.document_id
 AND d.knowledge_base_id=c.knowledge_base_id
 AND d.active_version_id=c.index_version_id
JOIN document_index_version v ON v.id=c.index_version_id AND v.document_id=d.id
WHERE c.knowledge_base_id=:kb AND c.embedding IS NOT NULL
 AND ((c.document_id=:d1 AND c.index_version_id=:v1) OR ...)
ORDER BY distance ASC, c.id ASC
LIMIT :vectorTopK
```

所有参数通过 MyBatis 绑定，向量复用 VectorTypeHandler。快照成对保留 Document/Version 归属，WHERE 在排序和 LIMIT 前执行，不依赖版本 status；Java 不过滤数据库候选。召回上限是 vectorTopK，finalTopK 留给后续编排。

## 诊断和失败

新增 EMBEDDING 和 EMBEDDING_FAILED。Embedding 阶段候选数为零，Vector 阶段记录 PG 查询耗时及候选 ID、真实距离和排名。模型与 PG 耗时分别计算。任意阶段失败返回 FAILED、明确分类及空候选，不降级、不返回部分成功。空快照直接 EMPTY_RESULT，不调用模型、不查询 PG。

## 测试及实际证据

离线 QueryEmbeddingTests 使用可控 OpenAiEmbeddingModel 测试替身，覆盖正常、模型/客户端维度不符、返回维度不符、空/多结果、错误/重复 index、NaN/Infinity、空查询、零向量、模型异常安全处理、请求次数及事务禁止。

VectorSearchIT 使用现有固定 digest 的 PostgreSQL 17.6 / pgvector 0.8.0 Testcontainers，禁止拉取；模型关闭，固定向量，不访问供应商。查询 [1,0,...]：未发布 ID100 距离0，已发布 ID101/102 距离约0.2，ID103 距离1。Top-K=2 实际严格返回101、102；K=10返回3条。同距离按ID稳定排序。其他KB、NULL向量、错误归属排除；快照读取后发布13/23不进入旧快照；表重命名故障返回 VECTOR_FAILED。空快照在模型关闭时 EMPTY_RESULT，表不可用时仍可返回空集合。

执行命令：

```powershell
mvn spotless:apply spotless:check -DskipTests test-compile
mvn '-Dtest=QueryEmbeddingTests,VectorSearchIT,RetrievalFoundationTests,EmbeddingServiceTests,EmbeddingPersistenceIT,IndexPublishIT' test
mvn '-Dtest=QueryEmbeddingTests' test
git diff --check
git diff --cached --check
```

首轮指定测试14项通过，0失败/错误/跳过：QueryEmbeddingTests 3、VectorSearchIT 1、RetrievalFoundationTests 3、EmbeddingServiceTests 4、EmbeddingPersistenceIT 2、IndexPublishIT 1。补充事务守卫测试后单独重跑 QueryEmbeddingTests，最终结果见收尾记录。编译和 Spotless 通过。中间一次测试在 Spotless 阶段因耗时边界调整未格式化而停止，修复后重跑通过。日志位于忽略的 target/m2-02-*.log，不提交日志。

## 验证边界

未运行 clean verify、在线供应商、ES、性能/召回质量评估或四策略 REST API。PG 故障用真实数据库表不可用注入，不等同 TCP 断线测试。测试场景集中在一个真实 PG 用例，多场景断言不计为多个 JUnit 测试。未修改 M1 发布/写入协议和既有迁移。可进入 M2-03，M2-06 负责组合 Recall 与统一策略诊断。

## 收尾结果

最终 QueryEmbeddingTests 4项通过，0失败/错误/跳过；与未变化的其余10项构成15项唯一测试通过。最终 Spotless apply/check 和 test-compile 成功，完整新增代码及 SQL 已审阅；diff 与暂存差异空白检查通过。Commit SHA 由任务最终回复提供，避免自引用。
