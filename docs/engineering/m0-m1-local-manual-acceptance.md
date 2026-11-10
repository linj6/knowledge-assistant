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
