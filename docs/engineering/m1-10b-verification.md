# M1-10B Elasticsearch 完整性与 BM25 可搜索性验证

## 范围和入口

基线 `56be82397d310225d88a857a73e303670501aa86`，分支 feature/m1-ingestion-indexing，开始时工作区干净。

EsIntegrityService 接收 KB、Document、IndexVersion ID 及完整 List<EsChunkDocument> 快照。默认入口复用 EsIndexService.INDEX；显式索引入口供隔离测试使用。返回预期数量、ES 数量和搜索样本数量，任意错误统一返回固定 ES_VERIFICATION_FAILED，不附原始异常 cause，不记录正文、原始 ES 错误或凭据。

组件不读取 PG、不管理 PG 事务、不修改任何业务状态。EsIndexRepository 新增 validateIndex，共享原有 Mapping 检查逻辑，但缺失时禁止创建索引；原有 ensureIndex/create-only Bulk 行为不变。仍为六字段协议，不增加 title，不调整 IK、BM25 或字段类型。

## 完整遍历与一致性

1. 检查非空、最多 10,000 条的预期集合，拒绝重复 ID、非法 ID、空白正文和不匹配的归属。
2. 检查已有 Mapping，显式 Refresh；请求失败或 failed shards 非零均失败。
3. 使用官方 Java API Client 8.18.0 的 Scroll：每页 100 条、_doc 排序、1 分钟上下文，三个归属 term 过滤，读到空页才完成。不使用 from/size 深分页或默认前十条。限制总数量和页数，超过上限明确失败。
4. 逐项验证 _source 六字段存在及 JSON 类型，比较 _id 与 chunk_id，拒绝重复；以既有 EsChunkDocument 的完整 record equality 比较六字段。最终比较完整 ID 集合，不仅比较数量。ID 始终为十进制字符串，预期 ID 通过 Long 无损范围检查，不使用浮点转换。
5. 检查每次响应的 timed_out 和 failed shards；分页异常不返回部分成功。finally 使用最新 scroll_id 执行 clearScroll，清理失败同样不能返回成功。

Scroll 是本任务验证用的一次性快照读取方案；205 条真实数据测试证明跨三页及终止空页能够完整读取。没有使用 PIT，也没有需要关闭的 PIT 资源。验证不 Bulk 补写、不覆盖、不删除文档。

## 搜索验证

按 chunk_order、chunk_id 排序，确定性选择首、中、尾位置并去重。使用样本真实正文调用现有 ik_smart analyzer，取第一个分析词项并检查非空；无词项明确失败。执行 content match，附 KB、Document、IndexVersion 三项过滤，再附样本 ID 过滤，size=1。

样本 ID 过滤只消除其他合法 Chunk 的排名影响，不替代正文 match：没有倒排匹配仍返回空结果并失败，因此不是 GET 或 Count 存在性验证。不要求样本在整个版本搜索中排第一，不调用模型。

真实 ES 测试：205 条“中文知识库 Spring Boot 搜索验证”数据，Refresh 后首、中、尾三项全部命中；重复验证仍成功。标点正文 !!! 不能生成 IK 搜索词，明确失败。ES server info 断言 8.18.0，节点插件断言 IK 8.18.0，复用 Mapping 兼容检查确认 ik_max_word / ik_smart 和显式 BM25。

## 文件

- EsIntegrityService.java：验证入口及完整读取、比较、真实搜索。
- EsIndexRepository.java：复用 Mapping 校验的只检查入口。
- EsIntegrityTests.java：输入和安全异常离线测试。
- EsIntegrityFailureTests.java：Refresh、分页和无搜索命中的离线故障注入，检查清理及不修复。
- EsIntegrityIT.java：继承既有 ElasticsearchIT 固定镜像基础设施，随机 m110b-* 索引，finally 精确删除；包含继承的 M0 analyzerAndBm25 测试。
- pom.xml：将新增 IT 加入 Failsafe 清单。
- 本记录。

## 实际命令和结果

- `mvn spotless:apply spotless:check -DskipTests test-compile`：最终 BUILD SUCCESS。
- 首轮 `mvn '-Dtest=EsIntegrityTests,EsIntegrityIT' test`：4 项通过，包含离线 2 项和真实 ES 2 项。
- `mvn '-Dtest=EsIntegrityTests,EsIntegrityFailureTests,EsIntegrityIT,EsIndexTests' test`：11 项中 10 项通过、1 项离线故障测试断言失败。原因是深度 Mock 未明确返回空页，导致在搜索阶段前退出；修正测试空集合，不删除“实际调用搜索两次”的断言。该轮真实 ES 2 项及既有 M1-07 离线 6 项均通过。
- 修正后 `mvn '-Dtest=EsIntegrityTests,EsIntegrityFailureTests,EsIndexTests' test`：9 项通过，0 失败/错误/跳过。离线新增故障测试一个方法内部覆盖三个阶段，不宣称三个独立 JUnit 测试。最终实现的真实 ES 2 项和离线 9 项均已通过，各轮结果如上，不宣称全量工程测试通过。
- `git diff --check`、`git diff --cached --check`：通过，完整暂存 diff 已审阅。

真实测试覆盖正常完整索引、缺失、多余、同 ID 正文冲突、顺序冲突、_id/source 冲突、KB/Document/Version 隔离、多页读取、重复验证、失败后原文档未被修复。空预期、重复预期、安全异常、Refresh/分页故障和搜索无命中使用离线测试补充，不将 Mock 当成真实故障证据。

## 限制和后续

固定本地 knowledge-assistant-elasticsearch:8.18.0-ik8.18.0 镜像，禁止拉取；未访问共享 ka-chunks-v1、未调用在线模型、未修改迁移、PG 或 M1-10A。没有运行全量测试、真实网络中断注入、超过 10,000 条的性能基准或并发写入压力测试。10,000 是组件明确拒绝超限的边界，不是对产品容量的修改。

Scroll 数据快照与其后的搜索不是跨请求事务；验证成功不保证之后 ES 不被修改。只读验证成功不赋予发布可见性。M1-10C 后续负责 PG/ES 编排、最终 PG 快照复核及短事务 BUILDING → VERIFIED，本轮未实施；active_version_id、published_at、ImportTask 与 M1-11 均不涉及。
