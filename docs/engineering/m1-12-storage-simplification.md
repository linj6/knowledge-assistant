# M1-12 存储简化验收

## 范围与基线

分支 `feature/m1-ingestion-indexing`，基线 `e3fc170c4a02a4150311b66125cd14b4709cbabd`，开始前工作区干净。本轮只简化存储补偿，不改变任务重试、租约、超时、恢复、TaskScheduler、索引验证或发布事务，无数据库迁移、模型调用、HTTP协议变化。测试使用临时容器，未启动或修改现有Compose服务。

## 为什么删除扫描

原实现每小时列举正式对象，维护扫描游标、PG逐对象引用查询及上传/清理对象锁；为少数补偿失败路径增加了正常运行代码和配置。新实现利用MinIO原生ILM清理临时对象，正式对象保留即时补偿与安全告警，移除应用定时扫描。它降低维护复杂度，不承诺绝对无孤儿。

## 当前流程与安全边界

1. 原知识库、文件格式/编码/大小、SHA-256及重复导入检查保持。
2. 服务端生成两个独立随机UUID Key：`staging/{UUID}`、`originals/{UUID}`，不接受客户端Key。
3. 上传staging，SDK `CopyObjectArgs/CopySource`服务端复制到originals，不回读再上传。源必须staging前缀、目标必须originals前缀，异常仅固定安全描述。
4. 短PG事务原子创建Document、BUILDING版本、PENDING任务；Repository拒绝staging Key，数据库仅保存finalKey。
5. PG提交成功后尽力删除staging，失败只告警，不把成功响应改成失败；残留由两天staging ILM过期规则处理。
6. 复制请求失败也可能已生成目标，对本请求finalKey尽力删除。PG创建异常后先在主库查询finalKey引用：无引用才删除，存在引用则保留，查询失败保留并告警。随后尽力删除staging。
7. 并发唯一约束裁决赢家；失败请求只补偿自己生成的Key，重复上传仍返回赢家任务。未复用赢家对象Key。

MinIO调用均在PG事务外，receive明确拒绝外层事务，内部TransactionTemplate保留默认传播。PG提交响应异常但引用存在时保留文件、原异常仍交给原HTTP异常协议，不伪造成功。所有补偿告警仅含固定码及服务端随机Key，不带供应商原始异常、文件内容或凭据。

## ILM配置和运维

使用单Bucket，不创建第二Bucket。固定服务器镜像 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`，实际server `RELEASE.2025-09-07T16-13-09Z`；内置mc实际 `RELEASE.2025-08-13T08-35-41Z`，已运行帮助检查其支持 `ilm rule add --prefix --expire-days`。

新增 [配置脚本](../../infra/minio/configure-staging-ilm.py) 和 [部署/人工核对说明](../../infra/minio/README.md)，根目录执行：

```powershell
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant --check
```

脚本只新增缺失的`staging/`两天规则，不每次上传创建规则；重复运行不新增重复项，保留已有其他规则。缺失检查非零退出；冲突或覆盖originals/的过期规则会拒绝并要求人工审阅，不盲目覆盖。配置变更须串行进行。脚本只识别NoSuchLifecycleConfiguration为无规则，访问失败不能当成无规则。mc成功JSON的规则位于config字段，已按固定版本真实响应解析。

真实测试验证：预先设置archive/30天规则，再运行脚本两次，规则数保持2；再次导出规则完全一致，archive保留，新增规则前缀staging/、Days=2、Enabled，没有originals匹配。`--check`在缺失时失败、配置后成功。

**生命周期规则配置验证通过，不等于真实观察到两天后的自动删除。** 未等待两天，ILM异步扫描也不是精确两天时刻删除。上述配置在测试专用Bucket验证；未擅自向现有用户Compose Bucket写规则，部署时仍须执行脚本。

## 正式孤儿及升级兼容

staging规则无法清理已复制到originals但PG未成功创建的对象。补偿删除失败、复制后进程中断、主库引用查询失败仍可能留下正式孤儿，记录安全告警，不保留扫描框架。即时补偿不是绝对无孤儿保证。

现有originals文件读取路径不变，无需迁移或重传；原M1-12留下的历史正式孤儿不受staging ILM管理。维护窗口暂停上传并确认所有在途请求/事务和旧进程结束，用对象清单与主库document.object_key精确比对；默认只报告候选，逐个复核、备份、人工审批后单Key删除，PG不可用不删除。具体只读SQL与操作步骤见部署文档。

## 删除内容

- OrphanCleanupScheduler、OrphanObjectCleanupService、OrphanObjectRepository、OrphanObjectMapper及其XML。
- OriginalObjectGuard及上传/扫描互斥逻辑。
- OriginalFileStore.listOriginals、ObjectPage、StoredObject与扫描游标。
- WorkerProperties及application.yml中的orphan-cleanup-interval、orphan-object-grace-period、orphan-cleanup-batch-size；其余Worker参数保持。
- 旧OrphanObjectCleanupIT，使用StorageImportIT验证新流程；Failsafe包含项同步。

## 测试范围与真实结果

- `mvn spotless:apply spotless:check -DskipTests test-compile`：成功，实际包含格式化、格式检查和测试编译；最终日志 `target/storage-last-build.log`。
- `mvn '-Dtest=StorageImportIT,MinioIT,ImportTests,ImportApiTests,ImportPersistenceTests,ImportTaskIT,IndexPublishIT' test`：12项中11项成功，首次StorageImportIT因下述ILM解析错误失败，日志 `target/storage-target.log`。其中上传模块7项、MinioIT 2项、ImportTaskIT 1项、IndexPublishIT 1项通过。
- 修复后 `mvn '-Dtest=StorageImportIT' test`：1项通过，零失败/错误/跳过，包含真实ILM和跨PG/MinIO补偿验证；日志 `target/storage-real.log`。
- `mvn test`：129项全部通过，零失败/错误/跳过，日志 `target/storage-default.log`。默认test不等同全量Failsafe验收，真实IT按上述显式命令运行。
- 最终补充PG不可用保护后，`mvn spotless:apply spotless:check -DskipTests test-compile`成功；`mvn '-Dtest=StorageImportIT' test`再次通过1项，零失败/错误/跳过。日志 `target/storage-last-build.log`、`target/storage-final-real.log`。
- `git diff --check`、`git diff --cached --check`通过；完整变更已审阅。构建和测试日志保留于target，不提交。

生产源码和配置搜索：没有废弃OrphanCleanupScheduler、listOriginals或orphan扫描配置引用。TaskWorker/TaskScheduler及任务重试恢复代码无diff；Flyway、索引验证和发布代码无diff。

StorageImportIT使用真实PG 17.6/pgvector0.8.0固定digest镜像及上述本地MinIO镜像，均禁止拉取。验证staging上传、服务端复制内容一致、删除源后目标可读、旧对象读取、非法复制前缀与真实缺源失败、PG正式Key持久化、真实CHECK注入使任务插入失败时三实体回滚及文件补偿、并发重复上传赢家文件存续。

故障注入边界明确：薄存储包装类在真实复制前/后抛异常，验证无任务创建且不确定复制目标被补偿；包装staging或正式对象删除失败，验证成功业务响应不受影响及正式删除失败告警；事务管理器委托真实PG提交后抛异常，模拟提交响应丢失，验证已提交引用和正式文件保留。包装类断言网络操作不处在PG事务内。最后在真实PG提交后停止测试PG容器，使引用查询真实失败，验证正式对象保留并输出ORIGINAL_OBJECT_REFERENCE_UNKNOWN。正常上传、复制、列举、读取、删除、SQL及ILM均为真实基础设施调用，不以Mock证明跨存储行为。

首轮组合12项中11项通过，新增ILM测试因mc成功JSON包装解析错误失败；修复读取config后重新验证。未删除必要断言或跳过测试。

## 未验证

未等待ILM两天实际删除，未在现有用户Bucket执行配置，未实际清理历史正式对象。PG提交异常为真实提交后的响应异常注入，未模拟真实网络分区或进程kill；未承诺自动排除所有不确定提交竞态。仍无生产TaskProcessor，无M1-13完整导入E2E，无多Worker、供应商请求或性能测试。本轮完成后停止。

## 修改文件清单

- `README.md`
- `docs/architecture/architecture.md`
- `docs/engineering/configuration-baseline.md`
- `docs/engineering/m1-12-storage-simplification.md`
- `docs/engineering/m1-12-verification.md`
- `infra/minio/README.md`
- `infra/minio/configure-staging-ilm.py`
- `pom.xml`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/config/WorkerProperties.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/storage/MinioOriginalFileStore.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/storage/OriginalFileStore.java`
- `src/main/java/com/lnjecit/knowledge/assistant/infrastructure/storage/OriginalObjectGuard.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/DocumentImportRepository.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/DocumentImportService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanCleanupScheduler.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanObjectCleanupService.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanObjectMapper.java`
- `src/main/java/com/lnjecit/knowledge/assistant/ingestion/upload/OrphanObjectRepository.java`
- `src/main/resources/application.yml`
- `src/main/resources/mapper/ingestion/OrphanObjectMapper.xml`
- `src/test/java/com/lnjecit/knowledge/assistant/ImportPersistenceTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/ImportTests.java`
- `src/test/java/com/lnjecit/knowledge/assistant/OrphanObjectCleanupIT.java`
- `src/test/java/com/lnjecit/knowledge/assistant/StorageImportIT.java`
- `src/test/java/com/lnjecit/knowledge/assistant/TaskExecutionTests.java`

## 2026-11-09 ILM评审修复与本地部署检查

基线 `ab8229acbd4dfdc21bf8299f8187c6037f1efa86`，开始前分支正确且工作区干净。本轮仅改ILM脚本、Python测试和部署说明；上传、数据库、任务恢复、索引和发布代码未修改。

脚本补充前缀双向覆盖检查：无前缀、stag/staging宽前缀、staging子路径、originals/originals/及其子路径均识别。只允许相关的精确staging/两天简单过期规则；其他相关规则拒绝。Tag及不能判明范围的复合过滤保守拒绝，And中的明确archive/前缀仅用于证明与两种目录无交集。无关archive/过期规则及禁用规则保留，不修改或删除任何原规则，也不实现通用生命周期引擎。

新增 `infra/minio/test_staging_ilm.py`：空配置、精确规则、幂等、archive/30天、全Bucket、宽前缀、originals和子目录、不兼容天数、Tag/And/未知复合、禁用规则及失败不改变原配置。测试中mc传输使用替身，真实SDK/mc验证仍由StorageImportIT执行。

本地Compose：Docker Desktop可用，现有MinIO容器最初exited；首次只读检查失败。启动同一已有容器后重试，只读mc导出返回 `NoSuchBucket`，目标knowledge-assistant不存在。**按授权边界停止部署，未创建Bucket、添加或覆盖规则、清理对象或重建数据卷，之后恢复容器原停止状态。** 实际服务器仍为RELEASE.2025-09-07T16-13-09Z、mc为RELEASE.2025-08-13T08-35-41Z。

结论：本地目标Bucket没有可报告的配置前/后规则，originals冲突情况不能验证，staging两天规则尚未部署；不把测试Bucket的配置成功冒充本地部署成功。两天后的异步删除未实际观察。后续须先确认并恢复既有目标Bucket，再执行只读检查。部署细节见infra/minio/README.md。

本轮实际验证：

- `python -B -m unittest discover -s infra/minio -p "test_*.py" -v`：6组测试全部通过（含上述矩阵的subTest）；配置拒绝和--check路径验证原规则不变。
- `mvn spotless:check -DskipTests test-compile`：成功，Java未修改，编译为增量检查；日志target/ilm-fix-build.log。
- `mvn '-Dtest=StorageImportIT' test`：1项通过，零失败/错误/跳过；真实测试Bucket两次配置后规则数为2，archive/30天保留，staging/2天存在，导出不变，--check通过，originals不匹配。日志target/ilm-fix-real.log。
- 本地 `python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant --check`：容器停止时失败；启动已有容器后仍失败，随后只读mc导出确认NoSuchBucket。未执行本地配置命令。
- `git diff --check`、`git diff --cached --check`：通过。完整diff审阅，仅4个脚本、测试和说明文件；没有Java、数据库或业务配置修改。未重跑默认全量测试或其他PG/ES集成测试，因无对应代码变化。

本轮修改文件：infra/minio/configure-staging-ilm.py、infra/minio/test_staging_ilm.py、infra/minio/README.md、docs/engineering/m1-12-storage-simplification.md。独立提交后停止，不进入M1-13。
