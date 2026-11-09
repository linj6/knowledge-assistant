# M1-13A 生产导入编排与接口适配

## 范围与基线

分支 feature/m1-ingestion-indexing，开始 HEAD f499d07f300fbae4ce9d09588aa0f331e00a1155，工作区干净。本次只实现 M1-13A，不修改迁移、冻结架构、依赖版本或调度默认开关。

## 生产路径

IngestionTaskProcessor 在模型和 ES 均启用时注册。先拒绝外层事务，校验领取参数和 ImportTask / Document / IndexVersion 归属、领取 epoch、执行者和状态。对象 Key 只取 Document，限制 originals/ 服务端 Key；不接受调用者传对象路径。

- BUILDING：MinIO 原始字节 → SHA-256 比对 → 根据文件名解析 Markdown/TXT → ChunkService.initialize → EmbeddingService.embed → EsIndexService.index → IndexVerificationService.verify → IndexPublishService.publish。
- VERIFIED：跳过原文件、解析、Chunk、Embedding 和 ES 写入，重新验证后发布。
- 存在发布指针、发布时间或 PUBLISHED 状态：只调用已有发布事务核对完整幂等事实，状态不一致由事务拒绝，不重写索引。
- 发布响应异常时读取任务状态，再复用原发布事务确认完整事实；尚未成功的任务重试发布前仍检查预算。失败更新保留既有 owner / epoch / 数据库租约 / RUNNING 条件，不覆盖成功状态。

编排不建立外层事务；数据库写入继续使用现有 Service 的短事务。没有复制切分、向量持久化、ES 写入、验证或发布算法。

## 超时与错误

沿用 System.nanoTime 的十分钟协作预算和数据库十五分钟租约，不续租、不强制中断线程。各耗时阶段之间检查预算；Embedding 新增 Runnable 检查回调重载，旧双参数 API 保留，批次调用前、响应后写入前和写入后检查。发布后无额外检查；Worker 不再在处理器返回后检查超时。

MinIO 显式连接 5 秒、读写各 30 秒、单调用 60 秒；ES 连接与连接池等待各 5 秒、socket 30 秒；Embedding 复用现有模型 timeout 配置（默认 30 秒、允许 1～300 秒）且 SDK 重试关闭。单次请求可能跨越剩余任务预算，返回后协作检查阻止后续阶段，不承诺精确到点终止网络请求。

解析格式错误映射 UNSUPPORTED_FORMAT，非法内容映射 INVALID_CONTENT，归属/哈希/状态校验映射 VALIDATION_FAILED。模型安全异常保留类型化 Kind：超时 MODEL_TIMEOUT，连接/限流/服务端故障 MODEL_UNAVAILABLE；鉴权、配置和非法响应不可自动重试。ES 既有安全连接/超时错误码映射 SEARCH_UNAVAILABLE；验证阶段只对明确网络异常提供此码。MinIO 明确 SocketTimeout/ConnectException 映射 INFRASTRUCTURE_TEMPORARY；其他存储失败保持安全固定消息且不自动重试。未知 RuntimeException、Chunk 冲突和索引数据冲突保持不可重试的默认行为。不传播供应商正文或异常 cause。

## 离线 Embedding

选择方案 A：本地 JDK HttpServer 返回确定性的 OpenAI-compatible 1024 维响应，使用生产 ModelConfiguration 创建真实 OpenAiEmbeddingModel，再调用真实 EmbeddingService；仅 Repository 使用 Mock。保留模型快照和维度校验、向量响应处理及写入调用路径，没有引入生产 Fake 模型或复制持久化算法。测试也验证 HTTP 响应后预算耗尽不调用写入。未请求 DashScope。

## 验证边界

聚焦 Mock 测试只证明调用顺序、停止边界、错误分类和外层事务拒绝，不证明真实数据库事务、ES Refresh 或 MinIO 读写正确。HTTP Stub 证明离线模型接口方案可行，不能证明真实 PG 向量持久化。M1-13B 仍需验证三套基础设施完整导入、故障恢复和发布响应丢失场景；单 Worker 部署约束与协作式超时限制沿用基线。

## 实际执行结果

- `mvn spotless:apply spotless:check -DskipTests test-compile`：成功。
- 最终串行命令：`mvn spotless:apply spotless:check test-compile test -Dtest=IngestionTaskProcessorTests,EmbeddingHttpStubTests,EmbeddingServiceTests,EmbeddingBaselineTests,TaskExecutionTests,ImportTaskTests,EsIndexTests,EsIntegrityTests,EsIntegrityFailureTests,DocumentParserTests,DocumentSplitterTests,ConfigurationTests`：BUILD SUCCESS，74 项，0 失败、0 错误、0 跳过。日志 target/m1-13a-final.log。
- 其中新增编排测试 16 项、本地 HTTP Stub 测试 1 项；其余为本次影响范围回归。不是全量测试，也未运行 IT、在线供应商测试或启动 Docker。
- 初轮回归出现 1 个失败：Embedding 配置安全错误码在类型化适配中丢失；已恢复固定配置错误码，最终该测试通过。没有遗留失败测试。
- `git diff --check` 通过；提交前审阅生产与测试 diff，未修改无关业务、依赖或数据库结构。

已知限制：Mock 发布确认不代表真实提交响应丢失已验收；最终数据库事实与短事务行为仍需 M1-13B 使用真实基础设施验证。编译有既有弃用 API 与 Mockito 泛型桩的 unchecked 提示，不影响本轮检查通过。
