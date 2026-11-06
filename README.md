# Knowledge Assistant

当前仅完成 M0-02：单 Maven 模块的 Spring Boot 工程骨架。

使用 JDK 21 和 Maven 3.9（本机验证版本 3.9.16）：

```shell
mvn clean verify
mvn spring-boot:run
```

也可在构建后执行 `java -jar target/knowledge-assistant-0.0.1-SNAPSHOT.jar`。
应用默认监听 8080；停止时按 Ctrl+C。当前没有业务 API，访问根路径返回 404。
构建、上下文测试与启动均不需要 Docker、数据库、Elasticsearch、MinIO 或模型密钥。
本地敏感配置使用已忽略的 `.env` 或 `application-local.yml` 等文件；当前应用不会自动加载 `.env`。

根包为 `com.lnjecit.knowledge.assistant`，启动入口位于根包以覆盖后续组件扫描。
按架构规格，未来逻辑模块职责如下；目前均无实际业务实现，因此不创建空包或占位类：

| 子包 | 职责 |
|---|---|
| knowledge | 知识库、文档元数据与知识库边界 |
| ingestion | 上传、解析、切分、双索引构建验证与首次发布 |
| retrieval | 向量/BM25 检索、RRF、Rerank 与策略编排 |
| rag | 上下文构建、回答生成与引用校验 |
| evaluation | 数据集、指标、评估运行与报告 |
| infrastructure | 存储、模型客户端适配、配置与观测 |

以上均为同一个 Maven 模块内的 Java 包，随对应任务实现。
外部中间件、Flyway、模型调用和配置/错误协议由后续任务完成，当前构建通过不代表这些集成已通过验证。

## 本地基础设施（M0-03 验证中）

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
应用尚未接入这些服务。修改 PG 初始化凭据不会修改已有卷中的数据库密码。
停止保留卷；不要使用 `down -v`，该命令会删除数据。备份应在升级或主动删除卷之前完成。
ES 关闭认证仅用于本地单节点开发；生产需要认证、TLS、权限与独立安全设计。
MinIO 镜像尚未通过运行验收，不能据此配置宣称全栈可用。见 [验证记录](docs/engineering/m0-03-infrastructure-verification.md)。

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
