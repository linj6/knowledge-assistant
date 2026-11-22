# Knowledge Assistant

当前为 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2 的单 Maven 模块工程。M0-01～M0-07 已有实现与验证记录；M0-01、M0-03、M0-07 仍为部分验证，M0 尚未完全验收。详见 [M0 验收报告](docs/archive/v1-development/engineering/m0-verification-report.md)。M1 导入、M2 检索及 M3 单轮 RAG 已实现；M4 系统评估尚未完成，详见 [M3 最终验收](docs/archive/v1-development/engineering/m3-final-acceptance.md)。

使用 JDK 21 和 Maven 3.9（本机验证版本 3.9.16）：

```shell
mvn test
mvn spring-boot:run
```

也可在构建后执行 `java -jar target/knowledge-assistant-0.0.1-SNAPSHOT.jar`。
应用默认监听 8080；停止时按 Ctrl+C。已提供知识库创建/查询、文档上传和导入任务查询 API；访问根路径返回 404。
默认测试不调用在线模型；部分持久化 Tests 和默认 IT 需要 Docker 及预置镜像。应用启动需要配置数据库。
本地敏感配置使用已忽略的 `.env` 或 `application-local.yml` 等文件；当前应用不会自动加载 `.env`。

根包为 `com.lnjecit.knowledge.assistant`，启动入口位于根包以覆盖后续组件扫描。
按架构规格，未来逻辑模块职责如下；knowledge、ingestion 与 infrastructure 已有实现；其余随后续任务实现：

| 子包 | 职责 |
|---|---|
| knowledge | 知识库、文档元数据与知识库边界 |
| ingestion | 上传、解析、切分、双索引构建验证与首次发布 |
| retrieval | 向量/BM25 检索、RRF、Rerank 与策略编排 |
| rag | 上下文构建、回答生成与引用校验 |
| evaluation | 数据集、指标、评估运行与报告 |
| infrastructure | 存储、模型客户端适配、配置与观测 |

以上均为同一个 Maven 模块内的 Java 包，随对应任务实现。
Flyway、配置/错误协议及模型适配已实现；PostgreSQL、Elasticsearch/IK、MinIO 本机功能和 Chat、Embedding、Rerank 历史接入已验证。M1 导入与首次发布、M2 检索、M3 单轮 RAG 已实现；构建成功不代表 M0 完全验收。

## 本地基础设施（M0-03 本机功能已验证，交付部分验证）

复制 `.env.example` 为 `.env`，替换所有占位凭据，再执行：

```shell
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100
docker compose restart
docker compose stop
```

PG/ES/S3/MinIO Console 分别绑定本机 5432/9200/9000/9001。服务共享项目默认网络，使用三个命名卷。
应用已配置 PostgreSQL 与 Flyway；ES/MinIO 已有容器功能探针，M1 业务接入已有实现。修改 PG 初始化凭据不会修改已有卷中的数据库密码。
停止保留卷；不要使用 `down -v`，该命令会删除数据。备份应在升级或主动删除卷之前完成。
ES 关闭认证仅用于本地单节点开发；生产需要认证、TLS、权限与独立安全设计。
MinIO 历史远端获取失败保留原记录；固定本地镜像运行、对象读写及重启持久化已验证，空白隔离环境恢复仍未验证。见 [验证记录](docs/archive/v1-development/engineering/m0-03-infrastructure-verification.md)。

## MinIO 固定本地镜像与离线交付

Compose 使用项目专属 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`，`pull_policy: never`，不会从远端拉取 MinIO。此标签来自已核验的本地镜像，不能从公共 Registry 直接获取。

已有正确镜像时先核实版本和 ID，再创建标签（不要覆盖同名的其他镜像）：

```powershell
docker run --rm --pull never minio/minio:latest --version
docker image inspect minio/minio:latest --format '{{.Id}}'
docker tag minio/minio:latest knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local
```

预期 Image ID 为 `sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e`。离线交付命令（路径自行选择，不提交归档）：

```powershell
docker save -o minio-RELEASE.2025-09-07T16-13-09Z.tar knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local
Get-FileHash minio-RELEASE.2025-09-07T16-13-09Z.tar -Algorithm SHA256
# 接收方先比较交付方提供的 SHA256，再加载
docker load -i minio-RELEASE.2025-09-07T16-13-09Z.tar
docker image inspect knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local --format '{{.Id}}'
docker run --rm --pull never knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local --version
docker compose up -d --build
```

本机 save/load 与加载后启动已验证；未验证空白隔离 daemon 的完整层恢复，不能等同异机恢复通过。PG/ES 首次部署仍需各自镜像及 IK 构建来源，不是完整离线栈交付包。官方 MinIO 远端获取此前失败，本次未重试；历史社区版长期维护与安全更新风险仍存在。本地运行通过不代表官方恢复分发或仍提供安全维护。

## M0-04 配置与错误协议

`assistant.model` 默认 enabled=false，timeout-seconds=30（合法范围 1–300）。
通过环境变量 ASSISTANT_MODEL_API_KEY 设置密钥；ASSISTANT_MODEL_ENABLED=true 时要求非空。
此节描述 M0-04 配置基线；后续 M0-05 已接入 DataSource/Flyway，M0-06 已验证 Chat、Embedding、Rerank 接入。Chat/Embedding 模型 ID 通过本地配置提供，历史 Embedding 模型 ID 无可靠记录，不推测补填。
请求和响应头 X-Request-Id 接受 1–64 位 ASCII 字母、数字、下划线、短横线；其他输入生成 UUID。
同步处理期间写入 MDC requestId，finally 清理；异步线程传播不在当前范围。
错误格式：`{"code":"INVALID_ARGUMENT","message":"请求参数不合法","requestId":"safe-123"}`。
400/405/415/500 对应 INVALID_ARGUMENT/METHOD_NOT_ALLOWED/UNSUPPORTED_MEDIA_TYPE/INTERNAL_ERROR。
404 保留 MVC 默认行为。未知异常日志只记录 RequestId、错误码、异常类型，不记录消息或堆栈。
不记录配置对象、环境变量全集、请求正文、Prompt、文档或密钥。本地开发基线不包含认证和审计。

数据库连接、环境变量与真实集成测试命令见 [M0-05 验证记录](docs/archive/v1-development/engineering/m0-05-database-verification.md)。

代码格式与提交前检查见 [代码格式规范](docs/archive/v1-development/engineering/code-style.md)。

## M0-07 测试入口

- `mvn test`：默认 *Tests（含本地回环 HTTP 桩与真实 PG 持久化测试），需要 Docker，无需模型密钥。
- `mvn verify`：默认 *Tests 及 pom.xml 白名单中的 12 个 IT 类；需要 Docker 与预置镜像，容器端口随机，不使用开发库或开发卷。
- `mvn -Ponline-model test`：仅 ModelOnlineIT、RerankOnlineIT；必须另获真实请求授权。Chat/Embedding 要求 `ASSISTANT_MODEL_ONLINE_AUTHORIZED=true`、`ASSISTANT_MODEL_ENABLED=true` 及模型配置；Rerank 要求既有 `ASSISTANT_RERANK_ONLINE_AUTHORIZED=true`、启用及配置。缺授权失败，不跳过。不使用该 profile 执行 verify。

局部容器验证先执行 `mvn test-compile`，再执行 `mvn failsafe:integration-test failsafe:verify`（只运行 pom.xml 明确白名单中的容器类）。不要用 `-Dtest=*IT` 扩大选择。Failsafe 缺测试、Docker 不可用、缺镜像或启动失败均导致失败，不将 skip 当作通过。

测试采用 Boot BOM 的 Testcontainers，所有镜像（包括 Ryuk）使用禁止拉取策略；不会在测试中下载或安装 IK。预置与 Compose 相同的 PG digest、`knowledge-assistant-elasticsearch:8.18.0-ik8.18.0`、`knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`。ES 在测试之外按既有 infra/elasticsearch 构建流程准备，须单独授权构建；本轮不构建。MinIO 按上文校验归档哈希、load、核对 image ID。目标 daemon 还须准备当前 Testcontainers 的 Ryuk 镜像 `testcontainers/ryuk:0.14.0`（本轮实际使用见验证报告），不能关闭 Ryuk 掩盖缺失。

离线环境须预缓存 Maven 依赖和所有容器镜像，然后可用 Maven `-o`；本机 load 记录不代表空白 daemon/异机恢复已验证。容器测试清理自己的 schema/index/bucket，由 JUnit 和 Ryuk 清理容器，不删除开发数据卷。

六类业务参数已记录为 [V1 初始配置基线](docs/archive/v1-development/engineering/configuration-baseline.md)，尚未实现或完成业务验证，不添加未使用配置。M0-07C 历史完整构建已通过（46 项、零失败/错误/跳过），本轮未重跑；隔离环境镜像恢复及负面测试仍未完成。

本轮实施证据见 [M0-07B 验证记录](docs/archive/v1-development/engineering/m0-07b-verification.md)。

MinIO 上传使用 staging/originals 单 Bucket 流程；部署前配置并检查 [staging 两天生命周期规则](infra/minio/README.md)。已有正式对象仍可直接读取，历史孤儿需按该文档一次性人工核对。

## M1 文档导入与首次发布

当前验收与默认测试发现边界见 [M1 最终综合验收](docs/archive/v1-development/engineering/m1-final-acceptance.md)。上传上限 2 MiB，支持 UTF-8 Markdown/TXT；新上传202、重复内容200。Worker默认关闭，启用前配置数据库、MinIO、ES、Embedding，并保持单实例单Worker。MinIO staging ILM部署见 [运维说明](infra/minio/README.md)。本轮不实现检索或RAG。

WEB-01 正式入口：`http://localhost:8080/api/index.html`，启动方式同上。保留 `spring.mvc.servlet.path=/api`。`/api/` 实测返回 200；正式访问请使用明确的 index.html 路径，支持 `#chat`、`#knowledge`、`#retrieval`。
