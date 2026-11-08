# M1-03 文件接收与导入任务验收

日期：2026-11-08。分支 `feature/m1-ingestion-indexing`，起点 `116fc1a6e70a24187d3f04483d0c82abb34accb4`，初始工作区干净。未修改冻结规范、Flyway 或既有依赖版本。

## 实现与接口

- Controller → Service → Repository → Mapper；基础设施使用简单 OriginalFileStore 接口及 MinIO SDK 8.6.0。补充 okhttp-jvm 5.1.0，与现有依赖树解析出的 okhttp 5.1.0 一致，解决 JVM 类缺失。
- `POST /api/v1/knowledge-bases/{kbId}/documents:import` 接收名为 `file` 的单文件字段；同字段多文件拒绝。首次成功为 202；同库原始字节 SHA-256 重复为 200，返回已有 documentId、importTaskId 和当前状态，不创建或重新执行任务。跨库允许相同内容。
- `GET /api/v1/import-tasks/{taskId}` 返回 DTO，含任务/文档 ID、外部状态、创建/更新时间和安全 failureCode。不存在为 404。Long 保留 Java 类型，使用现有 Jackson 全局字符串序列化。
- 状态映射：PENDING→RECEIVED、RUNNING→PROCESSING、SUCCEEDED→READY、FAILED→FAILED。仅 FAILED 返回固定 `IMPORT_FAILED`；不公开自由文本诊断、租约或内部 Entity。
- 文件名非空、最多 255 个 UTF-16 单元，拒绝目录分隔符及控制字符，扩展名忽略大小写且仅 `.md`/`.txt`。MIME 不作为内容可信依据。严格 UTF-8 解码验证，拒绝错误和截断字节序列，不解析正文。
- UTF-8 BOM 允许并原样保存，参与原始字节哈希；有无 BOM 是不同原文件。后续原文位置按原始解码文本的 Unicode 码点计量，包括开头 U+FEFF；解析若忽略 BOM，必须保留回原文的映射。
- 落实 M0 初始基线：业务上限 10 MiB，Servlet 单文件 10 MiB、请求总量 12 MiB，为 multipart 开销留余量。业务配置 `assistant.upload.max-file-bytes` 可降低（启动校验 1..10485760），不能绕过 Servlet 上限；若未来提高硬上限，需同时调整并验证这三处限制。按上限加一读取，避免仅信任声明大小。解码临时内存与原始字节均有界，未做并发内存压力测试。
- Bucket 由 `KA_MINIO_BUCKET` 配置，默认 knowledge-assistant；须由操作者预建，应用不自动创建 Bucket。Endpoint 为 `KA_MINIO_ENDPOINT`，凭据沿用 Compose 的 `KA_INFRA_MINIO_USER`、`KA_INFRA_MINIO_PASSWORD`。未配置凭据可离线启动，实际上传明确失败。对象 Key 为服务端生成的 `originals/{UUID}`，不包含客户端文件名；按原始字节保存。
- 上传在 PG 事务外。TransactionTemplate 的 REQUIRES_NEW 边界创建 Document、BUILDING IndexVersion、PENDING ImportTask，使用 ASSIGN_ID；初始次数和代际为零，activeVersionId/publishedAt 为空，不创建 Chunk、Embedding、ES 或发布事实。
- 先查重用于减少上传；最终裁决为数据库唯一约束。并发冲突在新事务回滚后重新查询赢家，清理仅使用输家本请求 UUID 对象 Key。数据库失败执行即时尽力删除，删除失败记录 `ORIGINAL_OBJECT_CLEANUP_FAILED` 和服务端 Key，不掩盖数据库异常。上传失败也尝试清理本请求对象。

请求示例：

```powershell
curl.exe -F "file=@notes.md" http://localhost:8080/api/v1/knowledge-bases/123/documents:import
curl.exe http://localhost:8080/api/v1/import-tasks/456
```

首次 HTTP 202 和重复 HTTP 200 均复用 Result，示意响应（时间与 ID 以实际值为准）：

```json
{"code":0,"message":"成功","data":{"importTaskId":"456","documentId":"789","status":"RECEIVED","createdAt":"2026-11-08T05:00:00Z","updatedAt":"2026-11-08T05:00:00Z","failureCode":null},"requestId":"example"}
```

## ChunkingConfig 实施协议

```json
{"version":1,"strategy":"FIXED_CODE_POINT","chunkSize":800,"chunkOverlap":120}
```

默认值放在 `assistant.chunking`。不可变 record 校验版本必须为 1、策略必须为 FIXED_CODE_POINT、chunkSize > 0、0 <= chunkOverlap < chunkSize；启动和构造均拒绝非法配置。创建 IndexVersion 时序列化当前配置为独立 JSON 字符串快照，保存至 chunking_config，不使用 `{}`、null 或临时值。现有创建流程没有更新快照的操作。

M1-05 必须反序列化并校验所属 IndexVersion 的快照，不能读取届时 application.yml 默认值。Markdown/TXT 共用固定窗口策略；Markdown 标题路径为独立元数据，不影响切分决策。所有窗口长度及原文偏移统一使用 Unicode 码点 `[start,end)`，不能直接使用 Java UTF-16 字符索引。800/120 是待后续评估验证的初始参数，未经证明最优。本任务没有实现切分算法，也未进入 M1-04。

## 实际验证

已执行 `mvn spotless:apply`、`mvn spotless:check`、`mvn -DskipTests test-compile` 和 `git diff --check`。

```powershell
mvn "-Dtest=ImportTests,ImportApiTests,ImportPersistenceTests,KnowledgeBaseApiTests,ApiBaselineTests,BusinessExceptionTests" test
mvn "-Dit.test=DatabaseIT,MinioIT" failsafe:integration-test failsafe:verify
```

最终局部测试 22 项通过，0 失败、0 错误、0 跳过，包含补强后的快照与清理日志断言。真实基础设施测试 4 项通过，0 失败、0 错误、0 跳过（DatabaseIT 2、MinioIT 2）。首次编译发现 okhttp JVM 类缺失，补齐同版本 JVM 产物后重新完成格式、编译与上述测试。已审阅全部新增文件及完整 diff。

PostgreSQL 使用 Testcontainers 固定 pgvector 0.8.0-pg17 镜像，实际 PostgreSQL 17.6，执行 V1→V2→V3；验证三表关联、初始状态、空发布指针、无 Chunk、同库去重、跨库导入。两个线程在上传阶段汇合，确保均已完成预查询，再由真实数据库唯一约束裁决，返回相同任务且仅一方为重复，多余对象被删除。临时 import_task CHECK 约束注入失败，确认 Document/IndexVersion 同时回滚；模拟存储删除失败确认原数据库错误保留并产生诊断日志。

PG 测试使用内存文件存储隔离故障注入，不据此宣称 MinIO 已验证。MinioIT 使用仓库已有本地固定镜像及独立临时 Bucket，实际通过 SDK 上传、读取 BOM/CRLF 原始字节、校验完全一致、删除并确认无法再读取，另保留现有 mc 往返测试。仅启动临时测试容器，未启动 Compose 或清理持久卷。

未执行全量测试、真实供应商 API、ES、Worker 或后续索引功能；本任务不涉及这些链路。没有执行并发内存压力、断电/提交应答丢失或线上环境端到端验证；延迟孤儿对象清理留待 M1-12。进程崩溃仍可能留下孤儿对象，即时补偿不构成跨存储原子事务。
