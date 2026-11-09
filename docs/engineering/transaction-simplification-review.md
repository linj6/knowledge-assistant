# Spring 事务简化审计

## 范围与基线

分支 feature/m1-ingestion-indexing，基线 72eb443f8c581b3115d679e7ae1a47aeb99235bf；开始时工作区干净。
全文扫描 src/main/java、src/test/java 和 Mapper XML，检索事务注解、Propagation、Isolation、TransactionDefinition、事务模板、事务管理器、同步管理器、手动回滚及 FOR UPDATE / SKIP LOCKED / SQL 注解，并检查 Service、Repository、Controller、Worker 实际调用。
原有 10 个注解方法、1 个模板业务入口；特殊传播共 9 处：6 个 REQUIRES_NEW 注解、2 个 MANDATORY 注解、1 个 REQUIRES_NEW 模板配置。没有类级事务、rollbackFor 或生产手动提交/回滚。

## 全部事务入口

| 类与方法 | 原配置 → 本次配置 | 当前调用方及外层事务 | 行锁 / 独立提交 / 外部服务 | 判断 |
|---|---|---|---|---|
| PgIntegrityService.verify | NEW/readOnly/RR → REQUIRED/readOnly/RR | 诊断调用、测试；无生产事务调用方 | 无锁；不需独立提交；无网络 | 保留一致快照 |
| PgIntegrityService.snapshot | NEW/readOnly/RR → REQUIRED/readOnly/RR | IndexVerificationService；拒绝外层事务 | 无锁；不需独立提交；无网络 | 保留一致快照 |
| PgIntegrityService.complete | NEW → REQUIRED | IndexVerificationService；拒绝外层事务 | Document → IndexVersion；不需独立提交；无网络 | 加锁、完整复核、更新仍为同一事务 |
| VerificationRepository.lockSnapshot | MANDATORY → 无注解 | 仅 complete 正式调用，已有事务 | Document → IndexVersion；无网络 | 由 Service 管理锁生命周期 |
| VerificationRepository.markVerified | MANDATORY → 无注解 | 仅 complete 正式调用，已有事务 | 继承上述锁；无网络 | 零行更新异常继续触发完整回滚 |
| TaskService.claimNext | NEW → REQUIRED | Scheduler.scan，无事务 | 任务行 SKIP LOCKED；无网络 | 返回前提交领取短事务 |
| TaskService.claimById | NEW → REQUIRED | 当前测试/显式调用，无生产外层事务 | 任务行锁；无网络 | 无实际独立提交依赖 |
| TaskService.fail | NEW → REQUIRED | Worker 异常处理，Worker 拒绝外层事务 | 条件 UPDATE 行锁；无网络 | 处理器事务结束后单独开启失败事务 |
| ChunkService.initialize | REQUIRED 不变 | 显式初始化调用 | 无显式行锁；无网络 | Chunk 批量插入和数量初始化必须原子提交 |
| EmbeddingRepository.write | REQUIRED 不变 | EmbeddingService，拒绝外层事务 | Document → IndexVersion；无网络 | 保留批次原子性；不扩大 Service 事务到模型请求 |
| DocumentImportService.receive 模板 | NEW → REQUIRED | 上传 Controller，无事务 | INSERT/唯一约束；模板内无网络 | 上传后开启短事务；失败回滚后补偿并查询并发赢家 |

NEW 表示 REQUIRES_NEW，RR 表示 REPEATABLE_READ。所有特殊传播均已删除，没有需要保留的当前独立提交场景。没有把重复配置提升到类级，以免扩大事务到其他方法。

## 边界与其他模块

验证编排仍为 PG 快照事务结束 → ES 请求 → PG complete 事务。IndexVerificationService 的活跃事务拒绝检查保留，ES 请求不持有 PG 事务。capture 为私有方法，由代理入口调用，没有依赖类内自调用开启事务。Repository 的两处移除不会改变 Mapper 使用的线程绑定连接。

任务仍为 Scheduler → Service 代理领取并提交 → Worker 无事务处理 → Service 代理失败更新。TaskRepository 没有事务注解；TaskMapper 保留任务行锁及 SKIP LOCKED。没有合规路径持有文档锁后反向领取任务锁。

上传仍先 MinIO put，再模板内创建 Document、IndexVersion、ImportTask；模板异常退出并回滚后执行 delete 补偿和赢家查询。没有把 MinIO 网络操作纳入事务。KnowledgeBase 单条 INSERT 原子性及普通查询不增加事务。Controller 均无事务。Embedding 模型及 ES 索引请求入口拒绝外层事务，批次 write 保持原子性。Infrastructure Mapper 只负责 SQL，没有新增事务设施。

未发现当前生产调用链的长网络事务或提前释放的行锁。默认 REQUIRED 若未来从外层事务调用会加入该事务，不再提供挂起外层事务的语义；本次结论限于当前调用关系。上传入口尚无拒绝外层事务的保护，不能据此声称任意未来调用也能保持网络在事务外。未实现发布、恢复、超时处理或新框架，未改 Migration、SQL、API、ES 协议或算法。架构文档 Boot 3.x 与 pom 4.1.2 的已知差异未改动。

## 验证

验证结果在提交前依据实际执行补充。真实数据库测试使用仓库固定 Testcontainers 镜像，不调用供应商模型。

### 已执行命令与证据

- `mvn spotless:apply spotless:check test-compile -DskipTests`：成功（修改测试后再次成功）。
- `mvn -Dtest=ImportTaskIT,PgIntegrityIT,IndexVerificationIT,ImportPersistenceIT test`：成功，实际发现并执行 4 个测试，0 失败/错误/跳过。ImportPersistenceIT 不存在，上传真实 PG 验证实际属于默认回归中的 ImportPersistenceTests，不能将不存在的类计为通过。
- `mvn -Dtest=ImportTaskIT test`：新增 NOWAIT 和零行更新断言后成功，1 个测试，0 失败/错误/跳过。覆盖领取提交释放锁、并发唯一领取、重复领取、失败状态更新、数据库异常及零行更新回滚。
- IndexVerificationIT 的真实 PG/ES 场景覆盖成功 VERIFIED、缺失/多余/错误 ES 数据、Chunk 版本/正文变化、版本状态变化及触发器注入更新冲突；校验失败均不改变发布指针和任务记录。ES 回调显式断言无活跃事务，外层事务调用被拒绝。
- PgIntegrityIT 覆盖只读校验及不完整数据拒绝；REPEATABLE_READ 和最终 Document → IndexVersion 锁顺序还通过源码审阅确认。本轮没有新增并发写入夹在两次快照读取之间的真实 PG 测试，也没有新增最终验证锁顺序的阻塞探测测试，因此不把这两项报告为已通过的动态验证。
- 首轮 `mvn test`：126 个测试，5 失败、8 错误，含 OnlineConfiguration 类加载失败；期间并发重新编译可能干扰输出目录。完成编译后重新运行完整默认回归，最终结果见下。
- 完成编译后串行重跑 `mvn test`：126 项，124 通过、1 失败、1 错误、0 跳过。ImportPersistenceTests（真实 PG、内存存储替身）通过，覆盖上传记录原子写入、并发去重、失败回滚及补偿；未运行真实 MinIO 上传联测。
- 两个剩余问题：KnowledgeAssistantApplicationTests 在排除数据源的上下文中缺少 DocumentMapper；MybatisCompatibilityTests 第 57 行仍期望 4 条迁移，实际已有 V5，得到 5。没有放宽断言或修改无关配置。
- `git diff --check` 通过；完整 Java diff 已审阅，生产修改仅事务传播配置、无用 import 及对应注释。
- 在独立 detached 工作树以原始基线执行 `mvn -Dtest=KnowledgeAssistantApplicationTests,MybatisCompatibilityTests test`，复现相同的 DocumentMapper 缺失与迁移数量 4/5 断言失败，确认这两项为基线问题。
- 日志保留于当前工作树 `target/transaction-review/`；基线复现日志在相邻 `knowledge-assistant-tx-baseline/baseline-test.log`。未执行真实供应商请求，未修改冻结文档或基础设施版本。
