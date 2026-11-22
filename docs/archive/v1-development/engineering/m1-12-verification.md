# M1-12 基本重试、超时、恢复与孤儿对象清理验收

> 存储扫描与对象锁为历史实现，已由后续 [存储简化](m1-12-storage-simplification.md) 替代；本记录保留当轮真实测试证据。

## 范围与基线

分支 `feature/m1-ingestion-indexing`，起点 `2ff42d1901bbb0a456c2e98843346eeff66da0fc`；实施前工作区干净。未修改 Flyway V1～V5、依赖版本、上传外部 API、ES 六字段协议、M1-10 验证算法或 M1-11 发布事务。没有供应商模型请求，没有启动 Compose；仅启动测试专用容器并由 Testcontainers 回收。

## 已实现

- WorkerProperties 统一绑定 assistant.worker，默认禁用。扫描5秒、每轮1项（1～10）；租约15分钟、任务执行10分钟，启动校验执行时间小于租约。全部时长为1毫秒～365天的整毫秒。
- 最多执行4次，包括首次；attempt_count 表示已开始执行总次数。失败后的退避5/15/45秒，独立均匀±20%抖动；Java 只计算延迟，PG statement_timestamp() 决定绝对时间。
- TaskExecutionException.Code 固定安全码、可重试属性及中文描述。模型暂不可用/超时、搜索暂不可用、临时基础设施、任务超时可重试；格式、内容、业务校验及未知 RuntimeException 不重试。未知错误只保存 EXECUTION_FAILED 和固定描述，不保存异常正文。
- 失败 SQL 核对 task/document/version、RUNNING、owner、epoch、有效租约；清空租约并更新错误及数据库时间。永久失败或次数耗尽时 next_retry_at=NULL；失败更新非1行抛 AUTHORITY_EXPIRED。
- claimNext 和 claimById 使用同一 SQL 条件限制：PENDING 或到期且有重试时间的 FAILED，次数低于上限。领取递增 attempt_count 和 claim_epoch，清空 next_retry_at。只锁 Task，不反向锁 Document/IndexVersion。FAILED 对应未发布 VERIFIED/FAILED 版本也可重新领取，已发布版本仍拒绝，不更改版本状态或验证结果。
- TaskExecutionContext 用 System.nanoTime() 测量剩余时间，Instant 截止时间仅供展示。Worker 在调用前后检查，并把上下文传给处理器；超时作为可重试错误。没有强制线程中断、自动续租或自行写 SUCCEEDED。
- recoverNext 使用默认 REQUIRED 事务和 FOR UPDATE SKIP LOCKED，仅恢复过期且 owner 不同的 RUNNING；固定 WORKER_INTERRUPTED，清租约，按已执行次数安排退避或终止。恢复本身不增加次数；后续由正常领取流程递增。调度器每轮有界恢复；无处理器时整个任务扫描直接返回，不恢复也不领取，测试通过服务入口验证恢复能力。
- MinIO 仅列举 originals/，返回真实 lastModified；按配置限制每轮扫描并保留内存游标，扫描完重置，避免长期保留对象挡住后续对象。默认每小时100项，严格早于24小时保留窗口、PG 无 document.object_key 引用且对象未被本应用上传使用时才删除。
- OriginalObjectGuard 按 Key 互斥上传至PG提交/补偿的整个窗口与清理；锁按使用引用计数回收，不阻塞无关 Key。清理引用查询经 Repository/Mapper XML，PG 查询失败立即停止本轮删除，游标不前进；单对象删除失败安全记数并继续，下次轮转重试。MinIO 网络调用不在数据库事务内；有外层事务的清理入口明确拒绝。即时上传失败删除补偿保留。

## 真实环境和测试方法

PostgreSQL 固定镜像：`pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87`，禁止拉取。测试查询实际 PostgreSQL **17.6**、pgvector **0.8.0**。V1～V5 在测试数据库迁移成功。

MinIO 使用本地固定 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`，禁止拉取；容器内 `minio --version` 断言实际 RELEASE.2025-09-07T16-13-09Z。ES 回归沿用本地固定8.18.0/IK8.18.0镜像。

ImportTaskIT 保留并发领取、SKIP LOCKED、锁顺序、零行/触发器失败回滚、错误执行权及新上下文发现任务的既有断言；租约断言改为900秒，非零计数递增案例从2递增到3，以适应最多4次限制。

TaskRecoveryIT 使用真实 PG 验证全部三档退避范围、第四次终止、永久/未知错误、未来时间拒绝、到期领取、次数与代际、错误owner/epoch/关联ID/过期租约拒绝、有效租约内超时写 FAILED，以及不同owner过期恢复、同owner/未过期排除、租约清理、次数耗尽、新应用上下文恢复、VERIFIED未发布任务重新领取。通过更新测试数据库时间字段控制到期，不等待45秒退避。

OrphanObjectCleanupIT 使用真实 PG+MinIO 的上传、列举、查询、读取和删除。注入未来 Clock，使真实 lastModified 超过24小时，避免等待一天；不伪造对象列表或数据库引用结果。仅单对象删除失败通过薄装饰器注入，其余对象仍执行真实删除。覆盖引用对象、新对象、非originals对象、重复清理、使用中对象跳过、分页处理上限及游标推进。停止测试专用PG容器制造真实不可用，验证剩余对象没有被删除。

## 实际命令与结果

| 验证类别 | 实际命令与结果 |
|---|---|
| 格式化 | `mvn spotless:apply`：成功 |
| 格式检查与编译 | `mvn spotless:apply spotless:check -DskipTests test-compile`：最终成功；包含用户要求的格式化、格式检查和测试编译 |
| 首轮组合 | `mvn '-Dtest=ImportTaskTests,TaskExecutionTests,ImportTaskIT,TaskRecoveryIT,OrphanObjectCleanupIT,IndexPublishIT,IndexVerificationIT,PgIntegrityIT,EsIntegrityIT' test`：15项，2项测试准备错误，修复如下；其余13项通过 |
| 修复后组合 | `mvn '-Dtest=ImportTaskTests,TaskExecutionTests,ImportTaskIT,TaskRecoveryIT,OrphanObjectCleanupIT,IndexPublishIT' test`：11项全部通过，零失败/错误/跳过 |
| 最终实现复测 | `mvn '-Dtest=TaskRecoveryIT,OrphanObjectCleanupIT,ImportTaskTests,TaskExecutionTests' test`：9项全部通过，零失败/错误/跳过，覆盖最终对象锁和分页实现 |
| 默认测试 | `mvn test`：129项全部通过，零失败/错误/跳过；包括上传补偿及并发持久化回归 |

单元测试通过：ImportTaskTests 3项（状态机、Worker安全失败及不发布、无处理器不领取/扫描容错），TaskExecutionTests 3项（单调时钟及配置边界）。

真实 PostgreSQL 测试通过：ImportTaskIT 1项、TaskRecoveryIT 2项、IndexPublishIT 1项；M1-10 回归 PgIntegrityIT 1项、IndexVerificationIT 2项（同时用真实ES）、EsIntegrityIT 2项均通过。

真实 MinIO 测试通过：OrphanObjectCleanupIT 1个组合测试，使用真实PG和MinIO覆盖上述清理场景。真实供应商调用未验证，也未执行。

构建日志位于 `target/m112-build.log`、`target/m112-target.log`、`target/m112-final-build.log`、`target/m112-final-target.log`、`target/m112-final-recheck.log`、`target/m112-default.log`；最终补充断言的编译与PG复测日志为 `target/m112-last-build.log`、`target/m112-claim-recheck.log`。日志不提交。

最终补充 `claimNext` 正向重试断言后，`mvn '-Dtest=TaskRecoveryIT' test`：2项通过，零失败/错误/跳过。其后仅展开测试通配符 import，并重新执行格式化、格式检查及测试编译，不改变测试行为。`git diff --check`、`git diff --cached --check` 均通过；已审阅全部32个任务文件。

首轮编译发现上传单元测试构造参数需要同步，补齐对象互斥依赖后编译通过。首轮组合测试15项中2项失败：测试误把永久错误的 next_retry_at 改为过去，另一个测试漏计重启后可恢复的另一旧owner任务。修正测试准备与断言后复测，未移除业务断言或跳过必要测试。

## 未验证与运行限制

**M1-12 没有生产 TaskProcessor；完整导入链路留给 M1-13。** 真实处理器必须在每个耗时阶段检查截止时间，并确保外部客户端有自己的请求超时；协作式超时不能终止永不返回的调用。没有真实10分钟阻塞任务、kill -9、多进程重叠、网络分区、压力或长时间定时器运行测试；新建上下文恢复不等同于完整进程崩溃E2E。

仍由部署保证单 Worker，重启前必须确认旧执行进程已停止；租约到期不代表旧 Worker 已 fencing。对象锁仅在本应用内生效，当前上传流程生成唯一 Key，不复用旧对象；未来新增写入/引用流程或其他进程必须遵守该协议。游标不持久化，重启从头扫描，所有对象最终按有界轮转检查；失败对象可能要等下一轮完整扫描。未进入 M1-13。

## 修改文件清单

- `docs/architecture/architecture.md`
- `docs/engineering/configuration-baseline.md`
- `docs/engineering/m1-12-verification.md`
- `pom.xml`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/config/WorkerProperties.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/persistence/ImportTask.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/storage/MinioOriginalFileStore.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/storage/OriginalFileStore.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/storage/OriginalObjectGuard.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskExecutionContext.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskExecutionException.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskMapper.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskProcessor.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskRepository.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskScheduler.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskStateMachine.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/task/TaskWorker.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/DocumentImportService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanCleanupScheduler.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanObjectCleanupService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanObjectMapper.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanObjectRepository.java`
- `src/main/resources/application.yml`
- `src/main/resources/mapper/ingestion/OrphanObjectMapper.xml`
- `src/main/resources/mapper/ingestion/TaskMapper.xml`
- `src/test/java/com/lnjecit/knowledge/assistant/ImportTaskIT.java`
- `src/test/java/com/lnjecit/knowledge/assistant/ImportTaskTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/ImportTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/OrphanObjectCleanupIT.java`
- `src/test/java/com/lnjecit/knowledge/assistant/TaskExecutionTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/TaskRecoveryIT.java`
