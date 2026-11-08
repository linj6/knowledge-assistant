# M1-07 Elasticsearch BM25 写入验证

## 客户端与依赖

基线 8a229c097138ae89d0027e595a37f416ea6eae30，分支 feature/m1-ingestion-indexing，开始时工作区干净。Java API Client 与官方 REST Client 均固定 8.18.0，没有使用 9.x 或 Spring Data Repository。dependency:tree 实测 Jackson 2.21.7（com.fasterxml.jackson）与 Jackson 3.1.7（tools.jackson）并存；ES 独立 JacksonJsonpMapper，不替换 MVC mapper。主代码/测试编译和真实容器调用共同验证本次使用路径，不宣称所有客户端 API 均已验证。

官方参考：[8.x 客户端安装](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/8.19/installation.html)、[官方客户端兼容政策](https://www.elastic.co/docs/reference/elasticsearch/clients/java)。实际依赖以本项目固定版本及编译/调用结果为准。

## Mapping Schema V1

共享物理索引 ka-chunks-v1 的 v1 表示 Mapping Schema，非业务 IndexVersion。chunk_id、knowledge_base_id、document_id、index_version_id 为 keyword，chunk_order 为 integer，content 为 text，ik_max_word / ik_smart，显式 similarity=BM25。_id 与四个 ID 字段均为十进制字符串，没有浮点转换。只保存六个业务检索字段。

开发单节点 number_of_replicas=0，不代表生产高可用。不存在时创建，已有时读取关键字段类型、analyzer、search_analyzer、similarity；不兼容明确拒绝，不删除/重建。并发创建的 resource_already_exists_exception 仍须完成校验。当前严格接受本协议显式 BM25 的映射，不接纳省略 similarity 的其他映射。

## PG 快照与 Bulk

Service 在外部请求前检查 Document/IndexVersion 关联、KB、BUILDING、空 publishedAt/activeVersionId、正数量、连续顺序、非空唯一 ID、非空正文及有效记录 version。逐批前和结束后重读比较全部索引业务字段。正文直接来自 PG Chunk，不重新解析/切分/生成 ID，不调用 Embedding，不更新 PG。

同步 Bulk 默认及上限100，可配置1–100。按 chunk_order 升序 create-only，逐项检查数量、索引、ID、状态及错误。409 使用实时 GET 读取原文档，比较六个业务字段；相同则幂等，不同明确失败。非409错误不能忽略。部分成功保留已成功数据，下次以稳定 ID 重试。超时/连接错误返回 OUTCOME_UNKNOWN，不尝试覆盖或回滚 ES；协议、请求拒绝及 item 拒绝使用固定安全消息，不附原始 cause/body。

真实故障证据：同一 Bulk 中新增项成功，另一项发生409且内容不同；整体明确失败，新增项仍存在，原冲突数据未覆盖，随后稳定 ID 重试成功。另有 Fake 201+400 响应逐项检查测试；Fake 400 不代表真实 ES 400 故障注入。超时/连接失败使用 mock IOException/SocketTimeoutException 注入，未模拟真实请求已成功但响应丢失。

## 可见性与并发边界

写入不执行 refresh、不改变 IndexVersion/ImportTask 状态或发布指针。测试 refresh 后以版本过滤执行真实 BM25 查询，中文知识库、Spring Boot、Spring AI、Redis、RocketMQ 均命中。真实 Mapping 明确 BM25、两个 analyzer；节点插件信息确认 IK 8.18.0，server info 确认 ES 8.18.0。

ES 存在不等于正式可检索；正式查询必须使用 PG 已发布版本集合，本次不实现 KeywordRetriever。真实 PG/ES 组合检查所有 Chunk 字段写入前后相同，active_version_id 为空、版本 BUILDING，PG 发布集合为空。

PG 事务不能跨越 ES 请求；Service 拒绝在已有事务中运行。只读快照不持有 Document/IndexVersion 行锁；现有 PG 写入沿用 Document → IndexVersion 锁顺序，本次没有修改该协议。Chunk.version 只保护 PG，不能阻止迟到 ES 请求。create-only 防静默覆盖，不能阻止旧 Worker 创建缺失文档。M1-09 必须解决旧 Worker 迟到写入以及写入/验证/发布互斥。M1-10 负责统一 refresh、完整 ID 集合与可搜索验证，M1-11 负责首次发布。

## 实际验证

- `mvn spotless:apply spotless:check -DskipTests test-compile`：最终成功。补充协议异常分类时曾误用不存在的 JsonpException，改为 Jakarta JsonException 后重新编译通过。
- `mvn '-Dtest=EsIndexTests' test`：3项通过，0失败/错误/跳过。
- 首轮 `mvn '-Dtest=EsIndexIT' test`：2项通过（含继承的 M0 analyzer/BM25 测试）。
- 最终 `mvn '-Dtest=EsIndexTests,EsIndexIT' test`：6项通过（离线3、真实集成3含继承的M0测试），0失败/错误/跳过，BUILD SUCCESS；真实PostgreSQL 17.6及V1～V5迁移成功。
- `git diff --check`、`git diff --cached --check`：通过，完整暂存diff已审阅。

真实集成仅使用本地固定 knowledge-assistant-elasticsearch:8.18.0-ik8.18.0 和固定 digest pgvector 0.8.0/PG17，禁止拉取镜像。ES 测试索引为随机 m107-*，finally 仅删除本次测试索引；继承的 M0 测试仍使用其随机 m007-*。没有访问共享业务索引、修改 Docker 全局设置或调用真实模型。

未执行全量 clean verify、真实连接中断/超时及响应丢失注入、并发 Worker/fencing、发布协议、双索引完整性、吞吐/内存基准和生产 TLS/鉴权部署验证。没有修改 Flyway V1～V5。
