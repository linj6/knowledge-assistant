# M0～M1 首次本地启动与人工验收

## 本轮结论

2026-11-10（Windows 本地时间）执行。环境检查部分完成；Docker daemon 不可用，启动与动态验收 BLOCKED。没有调用在线模型、启动 Worker、修改业务数据、删除卷或创建提交。

## 环境与版本

- 分支：feature/m1-ingestion-indexing；初始工作区干净。
- 实际 HEAD：167e6bf3eee696666895f8dd1a7bf113ababa5da。
- 用户指定审核提交：d63abb3ab933725159dccb0148e67708ac92fb63。两者不同，差异涉及 6 个文件，含导入处理、发布、调度和上传 Java 代码。未切换或回退分支；本轮未将当前版本视为指定审核版本。
- Windows 11 amd64；Oracle Java 21.0.10；Maven 3.9.16。
- Docker CLI 29.4.0，context desktop-linux；Compose v5.1.1。服务端版本无法获取。

## Docker 检查证据

| 实际命令/检查 | 结果 | 判定 |
|---|---|---|
| docker version | 客户端可运行；服务端返回 Docker Desktop is unable to start | FAIL |
| docker compose config --quiet | 退出成功，无输出 | PASS（仅配置解析） |
| docker compose ps -a | Docker Desktop is unable to start | BLOCKED |
| docker image ls | 同上 | BLOCKED |
| docker volume ls | 同上 | BLOCKED |
| TCP Listen 检查 | 127.0.0.1:5432 被 PID 14552 com.docker.backend 占用；9200、9000、9001、8080 未发现监听 | PASS（仅端口盘点） |
| Docker Desktop 进程检查 | Desktop、backend、build 进程存在；不能据此认定 daemon 正常 | PASS（仅进程盘点） |

Compose 项目名 knowledge-assistant；声明 postgres-data、es-data、minio-data 三个卷，预期默认名称分别为 knowledge-assistant_postgres-data、knowledge-assistant_es-data、knowledge-assistant_minio-data。无法读取实际卷及容器挂载，因此尚不能确认已有卷身份及复用安全性。未运行 docker compose up -d，未拉取、构建、替换镜像，未执行 down 或删除操作。

Compose 声明 PG pgvector 0.8.0-pg17（固定 digest）、ES 本地镜像 8.18.0-ik8.18.0、MinIO 本地镜像 RELEASE.2025-09-07T16-13-09Z-local；这些不是运行版本的验证结果。

## Spring Boot 配置检查

- 已阅读 src/main/resources/application.yml 和 application-local.yml。
- config/application-local.yml 实际不存在（路径被 Git 忽略不代表文件存在）。
- 根目录 .env 存在；Compose 配置解析成功表明其所需变量可解析；未输出任何凭据。当前进程三个 KA_INFRA 凭据环境变量均未配置。尚未逐项检查 .env 内值，不能认定 Spring 已获得这些配置。
- local 默认数据库 jdbc:postgresql://localhost:5432/ka，用户 ka，资源文件密码未配置。
- ES 默认 http://localhost:9200；MinIO 默认 http://localhost:9000，Bucket knowledge-assistant。
- 基础配置 Worker、模型、在线授权、ES 均关闭；local 包含 DashScope URL，但不代表发生过请求。
- 静态配置 MVC servlet 前缀 /api；运行时 Profile、外部配置实际加载及最终有效值均未验证。Spring 不会仅因根目录存在 .env 自动加载它。
- 未启动 Spring Boot；后续须显式使用 local Profile、确认外部配置来源并设置 assistant.worker.enabled=false。未进行 Flyway 迁移。

## API 路径与待执行样例

从当前 Controller 映射及静态 /api 前缀组合：

```powershell
# 以下仅供恢复环境后执行，本轮没有发送这些请求。
$body = @{name='manual-m0m1-20261110-unique';description='本地人工验收'} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/knowledge-bases -ContentType 'application/json' -Body $body
Invoke-RestMethod http://localhost:8080/api/v1/knowledge-bases
# 使用实际创建返回的 id 查询详情；不存在 id 需先只读确认。
```

详情 GET /api/v1/knowledge-bases/{id}；任务 GET /api/v1/import-tasks/{taskId}；上传 POST /api/v1/knowledge-bases/{kbId}/documents:import。

## 完整验收矩阵

| 验收项 | 状态 | 实际证据或未执行原因 |
|---|---|---|
| Git、工具版本、端口及静态配置检查 | 部分完成 | 见上文；HEAD 不同 |
| 三个命名卷实际复用、容器健康、基础设施启动 | BLOCKED | daemon 不可用 |
| ka 连接、pgvector、ES 8.18.0/IK | BLOCKED | 无可用 daemon 证据 |
| MinIO Bucket、现有对象、staging 两天 ILM、originals 规则 | BLOCKED | 未连接；不沿用历史 Bucket 不存在结论，未创建 Bucket |
| Spring Context、8080、Flyway、Bean、日志、无意外扫描或模型请求 | BLOCKED | 应用未启动 |
| 创建/查询知识库、不存在知识库、非法参数、不存在任务、统一错误协议 | BLOCKED | 未发送请求，无 HTTP 状态码或响应证据 |
| PG 只读核对新知识库 | BLOCKED | 没有创建记录，未执行 SQL |
| 本地 Embedding HTTP Stub、1024 维契约、唯一 Worker 准备 | 未执行 | 基础 API 尚未通过 |
| A Markdown 202；B RECEIVED→READY；C IMPORT_* 日志 | BLOCKED | 应用和模型服务未启动 |
| D PG Document/IndexVersion/ImportTask/Chunk/1024 维 | BLOCKED | 未执行 SQL |
| E ES 稳定 ID、版本、中文 Match | BLOCKED | 未执行 ES 查询 |
| F originals 字节与 staging 清理 | BLOCKED | 未执行对象读取 |
| G TXT READY；H 同库重复 200；I 跨库独立归属；J 错误处理 | BLOCKED | 未上传 |

本轮无导入任务日志、PG SQL 结果、ES 查询结果或 MinIO 对象证据可保存；不以静态配置代替运行证据。后续异步任务应采用有截止时间的轮询。

## 阻断与恢复

1. Docker Desktop 进程存在但 daemon 报无法启动。尚未诊断其内部根因；未重启或重置 Desktop。先由本机界面检查 Desktop 错误提示，恢复引擎，禁止 Reset/Clean/Purge 或删卷。
2. 恢复后重新读取容器、镜像、卷与挂载并核实 5432 占用归属，再决定安全启动。
3. 确认本次验收应使用当前 HEAD 还是指定审核提交；不能将不同代码验收记作指定提交验收。
4. 配置本机 Spring 凭据及实际 Profile/覆盖来源，不将凭据写入此文档。之后关闭 Worker 启动并执行基础 API。

没有使用真实 DashScope，也没有执行 Stub 验收。未来 Stub 只能证明 M1 工程链路和向量维度契约，不能证明真实 Embedding 中文语义质量。

## 重新尝试记录（2026-11-10）

本节为最新结果，取代上文 daemon 不可用的当前状态；此前记录保留作为故障历史。

- Docker 已恢复：Desktop 4.99.0 (245822)，CLI/Engine 29.9.3，API 1.56。当前分支及 HEAD 未变，仍非用户指定审核提交。
- 再次执行 compose config --quiet 成功；ps、image ls、volume ls 均可读取。三个本项目本地镜像及三个命名卷均存在。
- 启动前 inspect 确认三个卷分别挂载到 PG /var/lib/postgresql/data、ES /usr/share/elasticsearch/data、MinIO /data。
- 实际执行 docker compose up -d --no-build --pull never，成功。Compose 实际重建了三个容器；没有构建或拉取镜像，没有删除或替换数据卷。启动后再次 inspect 确认三个同名卷仍挂载到原目标。
- PG、MinIO 健康检查 healthy；ES HTTP 已可响应，版本 8.18.0，_cat/plugins 显示 analysis-ik 8.18.0。
- PG 只读执行 SELECT current_database(),version()：ka / PostgreSQL 17.6；SELECT extname,extversion FROM pg_extension WHERE extname='vector'：vector 0.8.0。
- PG 只读执行 SELECT installed_rank,version,success FROM flyway_schema_history ORDER BY installed_rank：仅 V1，success=true。psql 的 \dt 显示仅 flyway_schema_history，没有业务表。
- MinIO 使用容器内 mc，凭据仅通过容器环境变量引用，未输出。临时客户端 alias acceptance 写入容器客户端配置；未修改服务端对象或规则。
- mc ls、mc stat 确认 knowledge-assistant Bucket 已存在，0 对象、0 B；未创建 Bucket。
- mc ilm rule ls 显示唯一规则为 Enabled、staging/、2 天；没有 originals/ 或空前缀过期规则，当前 originals 不受该规则影响。

### 最新验收矩阵补充

| 项目 | 结果 |
|---|---|
| Docker 引擎、Compose 配置解析、已有镜像及卷检查 | PASS |
| Compose 启动及三卷挂载复核 | PASS（容器发生重建，卷复用） |
| PG healthy、ka 可连接、pgvector | PASS |
| MinIO healthy、Bucket 存在、空对象统计、staging 两天 ILM、originals 无错误规则 | PASS |
| ES 实际版本及 IK 插件 | PASS |
| Spring Boot / Flyway / 基础 API / 完整导入 | BLOCKED：待确认迁移授权 |

### 当前停止原因

应用启用 Flyway，数据库仅 V1，仓库存在 V2～V5。启动应用将执行待应用迁移，创建和演进业务表。遵守用户“遇到迁移现有数据必须停止等待决定”的明确要求，未启动 Spring、未执行 V2～V5，也未禁用 Flyway 绕过。需要用户决定是否允许在现有 ka 数据库执行仓库 V2～V5；同时确认以当前 HEAD 作为验收对象。未调用在线模型，未创建 Commit。基础设施保持运行。

最终健康复核：三个服务均 healthy；ES _cluster/health 返回 green、timed_out=false。以上未创建 Commit 的描述指验收执行时状态；后续按用户授权提交本记录。

## 迁移前复核与备份（2026-11-10 15:10）

- 当前 HEAD：89a396a47bb5553558ad80b070d58adee8966d06；本轮开始工作区干净。应用代码基线仍为 167e6bf，89a396a 仅新增验收文档。
- 三个 Compose 服务均 healthy。只读复核 flyway_schema_history：仅 V1 enable vector extension，success=true；public 仅 flyway_schema_history。
- 通过容器内 pg_dump 17.6，以 custom 格式备份 ka，再用 docker cp 保存到仓库外，避免 PowerShell 文本重定向损坏二进制归档。
- 备份路径：D:\develop\knowledge-assistant-local-backups\ka-before-v2-v5-20261110-151002.dump；大小 3053 字节，已核验非空。容器临时归档位于 /tmp/ka-before-v2-v5-20261110-151002.dump。
- pg_restore --list 可解析归档，列出 vector 扩展、Flyway 表及数据、主键和索引。未进行恢复演练，不能认定恢复验证已通过。
- 外部 config/application-local.yml 仍不存在；local 资源配置数据库 localhost:5432/ka、用户 ka、密码为空。尚未启动应用，未验证实际加载配置。
- 当前等待用户明确同意执行 V2～V5；未执行迁移、Spring 启动、基础 API 或完整导入准备。本轮未调用真实模型，未创建提交；基础 API 完成后再提交实际结果。

## 授权迁移与基础 API 实测（2026-11-10 15:15～15:16）

本节为最新验收结论，取代此前等待迁移授权的状态。用户已明确授权在现有本地 ka 上由 Spring Boot 自动执行 V2～V5。

### 构建与启动

- HEAD 89a396a，应用代码基线 167e6bf；原有未提交修改仅为本验收文档，已保留。
- 为避免使用旧 jar，执行 mvn -DskipTests package：BUILD SUCCESS，Spotless 检查、主代码及测试代码编译通过；自动化测试明确跳过，未执行 clean verify。
- java -jar target/knowledge-assistant-0.0.1-SNAPSHOT.jar，命令行明确指定 spring.profiles.active=local、assistant.worker.enabled=false、assistant.model.enabled=false、assistant.model.online-authorized=false、assistant.rerank.enabled=false、assistant.elasticsearch.enabled=false。
- 数据库 URL/用户通过启动进程环境变量设置为 jdbc:postgresql://localhost:5432/ka / ka；密码从现有 PG 容器环境读取后仅传入应用环境，未写入文件或命令行，启动后移除启动器环境中的密码。
- 外部 config/application-local.yml 不存在，未声称加载该文件。启动日志确认 active profile local，Flyway 确认连接 ka；正常使用资源内配置及上述环境/命令行覆盖。
- PID 10644；日志确认 ApplicationContext 初始化、Hikari 连接成功、Tomcat 8080 启动及 Started KnowledgeAssistantApplication。端口只读检查确认 8080 归属 PID 10644。
- 应用及基础设施保持运行。Worker、模型关闭；未调用真实 DashScope，未进入完整导入。

### 迁移证据

15:15:19 日志先 Successfully validated 5 migrations，随后依次 Migrating 2 create core tables、3 comment core tables、4 rename document lifecycle tables、5 add chunk version，15:15:20 返回 Successfully applied 4 migrations，now at version v5。

只读复核：

```sql
SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;
SELECT conrelid::regclass,conname,pg_get_constraintdef(oid)
FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY 1,2;
```

结果：V1～V5 全部 success=true；public 包含 knowledge_base、document、document_index_version、document_chunk、document_import_task 和 flyway_schema_history。读取到 19 条约束，包括各表主键、文档 (knowledge_base_id,content_hash) 唯一、Chunk (index_version_id,chunk_order) 唯一、1024 维/text-embedding-v4 检查、状态和非负计数检查。未添加迁移未声明的约束。

原始备份仍保留于 D:\develop\knowledge-assistant-local-backups\ka-before-v2-v5-20261110-151002.dump，3053 字节。没有 clean、repair、清库、删卷、修改迁移脚本或手动修改业务状态。

### 基础 API 实际请求与结果

通过 PowerShell 的 System.Net.Http.HttpClient 发出真实 HTTP 请求，JSON 使用 UTF-8；下表路径均基于 http://localhost:8080。原始安全响应保存在仓库外 basic-api-evidence.json。

创建请求：

```json
{"name":"manual-m0m1-20261110-151614","description":"M0-M1 local manual acceptance"}
```

| 请求 | HTTP | code / 安全结果 | 判定 |
|---|---|---|---|
| POST /api/v1/knowledge-bases，以上 JSON | 201 | 0；id 字符串 2120052899884367873；Location /api/v1/knowledge-bases/2120052899884367873 | PASS |
| GET /api/v1/knowledge-bases/2120052899884367873 | 200 | 0；名称及描述与创建一致 | PASS |
| GET /api/v1/knowledge-bases | 200 | 0；列表含该记录 | PASS |
| GET /api/v1/knowledge-bases/9223372036854775807 | 404 | 40401，知识库不存在，data=null | PASS |
| POST /api/v1/knowledge-bases，{"name":"   "} | 400 | 40000，请求参数不合法，data=null | PASS |
| GET /api/v1/knowledge-bases/0 | 400 | 40000，请求参数不合法，data=null | PASS |
| GET /api/v1/import-tasks/9223372036854775807 | 404 | 40400，资源不存在，data=null | PASS |

错误响应实际样例：

```json
{"code":40401,"message":"知识库不存在","data":null,"requestId":"24ec1756-395c-4d6c-8106-8c2829cccd49"}
```

七项请求均返回非空 X-Request-Id，逐项查看与 JSON requestId 一致；创建请求为 6f68451b-a301-4e08-97bb-4375372b07f7。错误正文未暴露堆栈或凭据。404 日志包含对应 RequestId 与错误码。

### PG 记录复核

```sql
SELECT id,name,description,created_at FROM knowledge_base
WHERE name='manual-m0m1-20261110-151614';
SELECT count(*) AS tasks FROM document_import_task;
```

返回恰好一行：id=2120052899884367873，name=manual-m0m1-20261110-151614，description=M0-M1 local manual acceptance，created_at=2026-11-10 07:16:14.594983+00；任务数为 0。与 HTTP 创建结果一致，PASS。创建响应纳秒精度在数据库回读时舍入到微秒，未影响本轮验收。

### 日志与剩余事项

- 原始 stdout/stderr 保存在 D:\develop\knowledge-assistant-local-backups\spring-local.stdout.log 和 spring-local.stderr.log；HTTP 证据同目录 basic-api-evidence.json。
- 已检查本轮启动及请求日志；数据库密码精确匹配结果为 false；IMPORT_、TaskScheduler、DashScope/dashscope 匹配数为 0。未发现 Bean 装配失败、模型请求或意外任务扫描迹象。该检查仅覆盖本轮采集日志，不代表网络级流量审计。
- 原始阻断：Docker daemon 不可用，恢复后基础设施通过；缺少迁移授权，明确授权后正常迁移通过。没有通过生产代码或数据库手工修复绕过。
- 本轮最新矩阵：迁移 V1～V5、业务表与约束、应用启动/local Profile/8080、七项 HTTP 请求、RequestId、PG 记录复核均 PASS。
- 完整 Embedding 导入、Stub 运行及 1024 维 HTTP 契约仍未验收；遵照本轮边界未开启。未执行真实模型语义质量验证，未进入 M2。


## 完整导入与首次发布实测（2026-11-10 15:24～15:26）

本节为最新结果。开始时 HEAD 8245a2e1936069c548f1fc75e84be92330513207，工作区干净，应用代码基线 167e6bf；PG、ES、MinIO 均 healthy。

### 独立 Stub 与应用重启

现有 EmbeddingHttpStubTests 为进程内 JUnit 工具，包含 Mockito，不适合作为运行服务。已参考其 OpenAI 响应格式，在仓库外创建 Python 标准库 HTTPServer 辅助程序，没有修改生产代码或构建。程序仅绑定 127.0.0.1，随机端口 63305；启动方式：

```powershell
python D:\develop\knowledge-assistant-local-backups\embedding_stub.py
```

端口写入 embedding_stub.py.port。仅接受 /v1/embeddings、text-embedding-v4 和 dimensions=1024；每条输入返回独立 index 及 [1,0,...,0] 共 1024 维向量。只记录路径、batch、正文 SHA-256 和维度，不记录原文、凭据或完整请求。无外网访问代码。Stub 日志实际显示 Spring AI 自动在 base-url /v1 后附加 /embeddings，三次请求 batch 分别为 4、3、4。响应 index 为 0～batch-1。

核对 10644 命令行及 8080 归属后，使用专用 Python 辅助进程 AttachConsole/GenerateConsoleCtrlEvent 向其发送 Ctrl+C。旧日志确认 graceful shutdown complete、Hikari shutdown completed；未终止其他 Java 进程。

新进程 PID 25120，8080 归属该进程；进程盘点仅此 Java 应用实例。显式 local Profile、worker/model/elasticsearch.enabled=true；模型 URL http://127.0.0.1:63305/v1，embedding-model=text-embedding-v4，dimensions=1024，chat-model=local-unused-chat，API key 使用非真实占位值，online-authorized=false。未调用 Chat API，无公网回退。PG localhost:5432/ka，MinIO localhost:9000 / knowledge-assistant，ES localhost:9200；PG/MinIO 凭据只通过进程环境传入，未写入仓库。

没有覆盖 Worker 默认租约、预算、重试策略；单实例 TaskScheduler 串行执行，batch-size 默认 1。启动出现框架提示 No TaskScheduler/ScheduledExecutorService bean found for scheduled processing，但后续真实 pool-5-thread-1 完成领取及处理，不是 Worker 未运行。local Profile、Flyway 验证成功、应用启动成功。

### 固定测试文件与首次 HTTP

仓库外 manual.md 为无 BOM UTF-8，含中文标题 M1-MANUAL-20261110、18 段技术说明；manual.txt 含 M1-TXT-20261110 及技术术语。文件和辅助脚本均保留于证据目录，未进入生产源代码。

真实请求：curl.exe -F file=@D:\develop\knowledge-assistant-local-backups\manual.md http://localhost:8080/api/v1/knowledge-bases/2120052899884367873/documents:import

返回 HTTP 202，code=0，status=RECEIVED，documentId=2120055079429230593，importTaskId=2120055079517310978，RequestId=8db99c0c-6408-4655-8e74-65e6ed2a86b8。随后 GET /api/v1/import-tasks/{id} 返回 READY、failureCode=null。轮询设置 90 秒截止时间，以 READY/FAILED 为终止条件；首次轮询已读到 READY，不声称观察到了每个中间状态。TXT/跨库同样使用有截止时间的轮询。

### IMPORT 阶段原始日志

```text
2026-11-10T15:24:54.351+08:00  INFO 25120 --- [knowledge-assistant] [nio-8080-exec-1] c.l.k.a.i.upload.DocumentImportService   : event=IMPORT_RECEIVED taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 attemptCount=0 elapsedMs=676
2026-11-10T15:24:58.470+08:00  INFO 25120 --- [knowledge-assistant] [pool-5-thread-1] c.l.k.a.ingestion.task.TaskScheduler     : event=IMPORT_TASK_CLAIMED taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 elapsedMs=16
2026-11-10T15:24:58.582+08:00  INFO 25120 --- [knowledge-assistant] [pool-5-thread-1] c.l.k.a.i.task.IngestionTaskProcessor    : event=IMPORT_CHUNK_COMPLETED taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 attemptCount=1 chunkCount=4 elapsedMs=82
2026-11-10T15:25:00.116+08:00  INFO 25120 --- [knowledge-assistant] [pool-5-thread-1] c.l.k.a.i.task.IngestionTaskProcessor    : event=IMPORT_EMBEDDING_COMPLETED taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 attemptCount=1 chunkCount=4 elapsedMs=1532
2026-11-10T15:25:01.863+08:00  INFO 25120 --- [knowledge-assistant] [pool-5-thread-1] c.l.k.a.i.task.IngestionTaskProcessor    : event=IMPORT_ES_COMPLETED taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 attemptCount=1 chunkCount=4 elapsedMs=1746
2026-11-10T15:25:02.707+08:00  INFO 25120 --- [knowledge-assistant] [pool-5-thread-1] c.l.k.a.i.task.IngestionTaskProcessor    : event=IMPORT_VERIFIED taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 attemptCount=1 chunkCount=4 elapsedMs=843
2026-11-10T15:25:02.729+08:00  INFO 25120 --- [knowledge-assistant] [pool-5-thread-1] c.l.k.a.i.task.IngestionTaskProcessor    : event=IMPORT_PUBLISHED taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 outcome=FIRST_PUBLISH elapsedMs=22
2026-11-10T15:25:48.353+08:00  INFO 25120 --- [knowledge-assistant] [nio-8080-exec-8] c.l.k.a.i.upload.DocumentImportService   : event=IMPORT_DUPLICATE taskId=2120055079517310978 documentId=2120055079429230593 indexVersionId=2120055079517310977 attemptCount=1 elapsedMs=12
```

### PG 与 ES 只读核验

```sql
SELECT d.id,d.knowledge_base_id,d.active_version_id,v.id,v.status,v.published_at,
       t.id,t.status,t.attempt_count,t.lease_owner,t.lease_until,t.last_error_code,t.last_error_message
FROM document d JOIN document_index_version v ON v.document_id=d.id
JOIN document_import_task t ON t.index_version_id=v.id;
SELECT id,knowledge_base_id,document_id,index_version_id,chunk_order,
       source_start,source_end,content,vector_dims(embedding)
FROM document_chunk ORDER BY id;
```

只读 HTTP：GET /ka-chunks-v1/_mapping、GET /ka-chunks-v1/_search?size=100；POST /ka-chunks-v1/_search，query.bool.must 为 match content=中文知识，filter 为首个 index_version_id。

逐条对比 PG/ES 的 ID 集合、KB/Document/Version 归属、chunk_order 及正文全部相等；11 个 Chunk 均有非空 1024 维向量。按源文件字符位置切片与每个 PG Chunk 正文逐条相等。Markdown 为 4 块（order 0～3，范围 0:800、680:1480、1360:2160、2040:2650），TXT 为 3 块，跨库 Markdown 为 4 块。

ES _id 等于 PG Chunk ID；content 映射 text、ik_max_word、search_analyzer=ik_smart、similarity=BM25。限定首次版本的中文 Match 返回 4 个命中。此处仅为 M1 索引验收，不代表 M2 检索 API 已实现。

三份文档均 active_version_id=对应版本，版本 PUBLISHED 且 published_at 非空，任务 SUCCEEDED、attempt_count=1；租约和 last_error_code/message 均 null。没有直接更新数据库。

| KB | Document | Version | Task |
|---|---|---|---|
| 2120052899884367873 | 2120055079429230593 | 2120055079517310977 | 2120055079517310978 |
| 2120052899884367873 | 2120055297256214530 | 2120055297256214531 | 2120055297256214532 |
| 2120055306357854209 | 2120055306596929538 | 2120055306596929539 | 2120055306596929540 |

### 其他真实 HTTP 结果

| 验收 | HTTP | 安全响应摘要 | 判定 |
|---|---|---|---|
| txt：/api/v1/knowledge-bases/2120052899884367873/documents:import | 202 | code=0；data={"importTaskId": "2120055297256214532", "documentId": "2120055297256214530", "status": "RECEIVED", "createdAt": "2026-11-10T07:25:46.252100100Z", "updatedAt": "2026-11-10T07:25:46.252100100Z", "failureCode": null} | PASS |
| duplicate：/api/v1/knowledge-bases/2120052899884367873/documents:import | 200 | code=0；data={"importTaskId": "2120055079517310978", "documentId": "2120055079429230593", "status": "READY", "createdAt": "2026-11-10T07:24:54.305137Z", "updatedAt": "2026-11-10T07:25:02.725807Z", "failureCode": null} | PASS |
| create-second-kb：/api/v1/knowledge-bases | 201 | code=0；data={"id": "2120055306357854209", "name": "manual-cross-kb-20261110-1526", "description": null, "createdAt": "2026-11-10T07:25:48.415855Z"} | PASS |
| cross-kb：/api/v1/knowledge-bases/2120055306357854209/documents:import | 202 | code=0；data={"importTaskId": "2120055306596929540", "documentId": "2120055306596929538", "status": "RECEIVED", "createdAt": "2026-11-10T07:25:48.481028300Z", "updatedAt": "2026-11-10T07:25:48.481028300Z", "failureCode": null} | PASS |
| unsupported：/api/v1/knowledge-bases/2120052899884367873/documents:import | 400 | code=40000；data=null | PASS |
| oversize：/api/v1/knowledge-bases/2120052899884367873/documents:import | 413 | code=40000；data=null | PASS |
| invalid-kb：/api/v1/knowledge-bases/0/documents:import | 400 | code=40000；data=null | PASS |
| missing-kb：/api/v1/knowledge-bases/9223372036854775807/documents:import | 404 | code=40401；data=null | PASS |

TXT 与跨库任务均轮询到 READY。重复上传复用首次 Document/Task；最终 PG 仅 3 个文档、3 个版本、3 个任务，重复上传未创建额外版本。跨库 KB=2120055306357854209，使用相同文件产生独立文档和版本。不支持格式为 .pdf；超限测试为 2097153 字节 .txt；非法参数 kbId=0，不存在 KB=9223372036854775807。所有错误返回统一 code/message/data/requestId，证据中保存 X-Request-Id。所有数据保留，未清理。

### MinIO 验证

首个 originals/57f0fc8f-e8fb-4c0b-b397-bc0f50cd8e21 存在，下载后 SHA-256 与 manual.md 完全一致：54418E34F3B441C18CF1E955C51C62F3DDFCD757402CEDD2F658781227376329。staging 递归列表为空。最终 Bucket 仅有三个本轮 originals 对象，另两个为 originals/4459eaf6-61b7-4d3e-a693-681c89ccc5da 和 originals/1aab0fa2-688f-4af4-a329-83aeb43a7c10。ILM 仍为唯一 staging/ 两天规则，未覆盖 originals。没有主动覆盖或删除其他对象。

### 最终验收矩阵与限制

| 项目 | 状态 |
|---|---|
| 环境复核、旧应用正常停止、唯一应用/Worker 启动 | PASS |
| 本地真实 Embedding HTTP、批量/index、1024 维契约 | PASS |
| Markdown 202→READY、attempt=1、七阶段日志 | PASS |
| PG 归属、分块位置、向量、三项发布事实、诊断租约清理 | PASS |
| ES 稳定 ID、正文一致、IK/BM25、中文 Match | PASS |
| Markdown originals 原始字节、staging 清理、ILM | PASS |
| TXT READY、同库复用、跨库隔离 | PASS |
| 不支持格式、超限、非法 KB 参数、不存在 KB | PASS |
| 真实 Embedding 语义质量及在线 DashScope | NOT VERIFIED |
| 破坏性故障注入 | NOT VERIFIED |

正常导入没有出现失败，无生产代码或数据库修复。辅助检查中尝试读取不存在的 MinioProperties.java，随后改为读取实际配置和运行验证；未影响服务。未执行额外自动化测试，本轮为实际 HTTP/PG/ES/MinIO 验收。应用与 Stub 保持运行，Worker 当前启用；后续停止应用或 Stub 前应先确认无处理中任务。

证据目录 D:\develop\knowledge-assistant-local-backups：embedding_stub.py、embedding_stub.py.port、stub.log/stub.err、manual.md/manual.txt、markdown-upload.txt、markdown-poll.json、additional_checks.py、additional-http.json、verify_indexes.py、pg-es-verification.json、import.stdout.log/import.stderr.log、manual-original.md。PG/ES 证据含本轮自建测试正文，不含凭据；Stub 日志不含正文。

Stub 固定向量仅证明传输、持久化、双索引和发布工程流程；不证明真实中文语义质量。未调用真实 DashScope，没有手动修改任务/发布状态，没有删卷、清库、修改迁移或生产代码；未提交、Push、Merge 或进入 M2。

## 最终收尾结论（2026-11-10 15:31～15:32）

本节为最终状态，取代此前“应用与 Stub 保持运行”的描述。

- 收尾起始分支 feature/m1-ingestion-indexing，HEAD 8245a2e1936069c548f1fc75e84be92330513207；工作区仅本验收文档有未提交变更。应用代码基线 167e6bf 未修改。
- 停止前只读查询 document_import_task：3 个 SUCCEEDED，RUNNING=0；无处理中任务。
- 核对 PID 25120 为本项目 jar、local Profile、模型 URL 127.0.0.1:63305/v1 的 Java 应用；PID 17856 为仓库外 embedding_stub.py。8080/63305 分别归属上述进程。
- 按应用先、Stub 后的顺序，由独立辅助进程发送 Ctrl+C；没有按进程名批量终止或强制终止。应用日志 15:31:37 确认 Graceful shutdown complete 和 Hikari Shutdown completed。Stub 收到控制台中断退出。两 PID 均已消失，8080、63305 均无监听。
- 停止后再次只读核验：V1～V5 全 success=true；3 文档、3 版本、11 Chunk、3 SUCCEEDED、0 RUNNING。三个 Docker 服务仍 healthy；没有停止基础设施、删卷、清表、清索引或删除 Bucket/对象。原数据库备份保留。
- 脱敏证据归档：D:\develop\knowledge-assistant-local-backups\final-acceptance-evidence-20261110。包含 16 个日志、请求响应、PG/ES、Stub 脚本及测试文件，加 manifest.json（逐文件字节数与 SHA-256）。归档时对现有 PG/MinIO 凭据精确替换，并替换本地 Stub 占位值；没有复制数据库凭据配置或进程环境。原始备份和证据保留在仓库外，没有随 Git 提交。

### 最终人工验收矩阵

| 验收项 | 最终状态 |
|---|---|
| 本地基础设施、版本、原始卷复用、Bucket/ILM | PASS |
| 迁移前备份、Flyway V1～V5、业务表及约束 | PASS |
| local 启动、基础 API、统一错误与 RequestId、PG 回读 | PASS |
| 本地 HTTP Stub 批量/index/1024 维契约 | PASS |
| Markdown/TXT/跨库真实生产链路到 READY | PASS |
| attempt=1、七阶段日志、原子发布事实、诊断/租约清理 | PASS |
| PG/ES 11 个 Chunk 的 ID、归属、正文和向量维度 | PASS |
| ES IK/BM25 映射及中文 Match | PASS |
| Markdown MinIO 原始字节、staging 清理、originals ILM 边界 | PASS |
| 同库重复复用、跨库隔离、格式/超限/非法参数/404 | PASS |
| 无 RUNNING、应用及 Stub 有序退出、基础设施保留 | PASS |
| 真实模型 Embedding 语义质量 | NOT VERIFIED |
| 本轮破坏性故障注入及备份恢复演练 | NOT VERIFIED |

最终结论：M0～M1 本地工程链路人工验收通过；这是本地 Stub 条件下的工程验收，不证明真实模型语义质量。没有真实 DashScope 请求，没有进入 M2。测试业务数据、ES 索引及三个 originals 对象继续保留，本轮不清理；今后真实模型评估应明确区分这些固定 Stub 向量。

本轮只修改验收文档，审阅完整文档变更并执行 git diff --check，不运行 Maven 编译或自动化测试。按用户授权创建仅包含本验收文档的独立提交；不 Push、不 Merge。
