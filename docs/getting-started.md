# 开始使用

## 环境与基础设施

准备 JDK 21、Maven 3.9、Docker 与 Compose v2。执行 MinIO 生命周期脚本还需 Python 3。[Compose](../compose.yaml) 使用 PostgreSQL 17 / pgvector 0.8.0（固定 digest）、Redis 7.4.11、Elasticsearch 8.18.0 / IK 8.18.0，以及本地 MinIO 镜像。ES 构建需要获取基础镜像和 [IK 安装包](../infra/elasticsearch/Dockerfile)。Compose 将 PG、Redis、ES、MinIO API 和控制台分别绑定到回环地址的 5432、6379、9200、9000、9001 端口。

### 首次部署

1. 已持有可信且匹配的本地镜像时可直接使用。历史归档 `minio-RELEASE.2025-09-07T16-13-09Z.tar` 的公开再分发状态为 **NOT READY**，原[百度网盘地址](https://pan.baidu.com/s/1edkb6uC5-KM-d_WkutpRMQ?pwd=n2ua)和提取码 `n2ua` 保留供核对。已有归档可按 [MinIO 镜像交付说明](../infra/minio/README.md#下载校验与导入)校验 SHA-256 后执行 `docker load`，并确认镜像标签为 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`、架构为 `linux/amd64`。
2. 在仓库根目录将 [`.env.example`](../.env.example) 复制为 `.env`，替换基础设施占位凭据。已有环境保留实际凭据。
3. 从仓库根目录启动基础设施，等待服务健康。MinIO 使用已导入的镜像，`--build` 用于 Compose 中配置了构建的 Elasticsearch 服务。

   ```powershell
   docker compose config --quiet
   docker compose up -d --build --wait
   docker compose ps
   ```

4. 打开 MinIO 控制台 `http://127.0.0.1:9001`，使用 `.env` 中的 MinIO 凭据登录，在 **Buckets** 页面创建默认 Bucket `knowledge-assistant`。已有环境复用与应用配置一致的 Bucket。
5. 配置并检查 `staging/` 两天生命周期。若使用自定义 Bucket，将下方名称替换为应用配置中的值。[规则与冲突处理](../infra/minio/README.md#配置-staging-生命周期)

   ```powershell
   python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant
   python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant --check
   ```

   检查结果应包含 `verified=true`、`prefix=staging/` 和 `expireDays=2`，然后继续配置应用。

## 应用配置与启动

将 [配置样例](../config/application-local.yml.example) 复制到 `config/application-local.yml`。已有文件时按需补齐。该路径已被 Git 忽略。Compose 的 `.env` 不会自动进入 Spring Boot 进程。将数据库密码填入外部配置，使其与初始化凭据一致，并通过启动进程的环境变量提供 `KA_INFRA_MINIO_USER`、`KA_INFRA_MINIO_PASSWORD`，或在外部 YAML 设置 `assistant.minio.access-key` 和 `assistant.minio.secret-key`。

| 配置 | 本地使用方式 |
| --- | --- |
| PostgreSQL | local Profile 默认连接 `jdbc:postgresql://localhost:5432/ka`，用户为 `ka`，Flyway 启动时迁移 |
| Redis | 默认连接 `localhost:6379`，用于登录会话 |
| Elasticsearch | local Profile 默认启用，端点为 `http://localhost:9200` |
| 导入 Worker | 默认关闭。数据库、MinIO、ES 和 Embedding 就绪后设置 `assistant.worker.enabled=true`，本地使用单应用实例 |
| Chat / Embedding | 获得真实调用及费用授权后，再设置 `assistant.model.enabled=true`、`online-authorized=true`，填写真实 API Key，并核对端点、模型 ID 和维度。local 内置配置使用 DashScope 兼容端点、`qwen-plus`、`text-embedding-v4`、1024 维 |
| Rerank | 单独授权后才打开 `assistant.rerank.enabled` 和 `online-authorized`，核对端点、`qwen3-rerank` 和独立 API Key 配置 |

仓库默认关闭 Chat、Embedding 和 Rerank，选择 local Profile 后仍然关闭。每组客户端只有在对应的 `enabled` 和 `online-authorized` 同时为 `true` 时才会初始化。

确认允许向供应商发送数据并承担调用费用后，在 Git 忽略的 `config/application-local.yml` 中为需要使用的组填写 API Key，并将该组的两个开关都设为 `true`。从仓库根目录启动并选择 local Profile 时，Spring Boot 会读取这份个人覆盖配置，后续启动可沿用。模型启用后，问答、向量检索及文档导入等操作可能向供应商发送数据并产生费用；混合重排使用独立的 Rerank 配置。密钥仅保存在个人配置或启动进程的环境变量中。[local 默认配置](../src/main/resources/application-local.yml)

在线测试也要求对应的两个开关均为 `true`，Embedding 基线测试还要求 `assistant.embedding.baseline-authorized=true`。仅选择 local Profile 不会授权在线测试。[Maven profile](../pom.xml) 和 [授权测试](../src/test/java/com/lnjecit/knowledge/assistant/OnlineAuthorizationTests.java)

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

## 首次使用与排查

1. 打开 `http://localhost:8080/api/index.html`，注册并登录，进入「知识库」创建和选择知识库。
2. 启用并配置导入 Worker 后上传一份不超过 2 MiB 的 UTF-8 Markdown 或 TXT，等待任务完成及索引发布。
3. 在「检索」尝试 BM25。配置 Embedding 后可使用向量与混合检索，另行配置 Rerank 后可使用混合重排。
4. 在「问答」基于已发布文档提问，确认引用可打开正文，再追问同一主题。

- **登录或写请求失败**。确认 Redis 可用；直接调用 API 的客户端需保留 Session Cookie，并使用 `GET /api/v1/auth/csrf` 返回的令牌及对应请求头。
- **PG 密码错误**。修改 Compose 变量只影响新卷初始化，已有库需使用其实际密码。
- **导入长时间排队或失败**。检查 Worker 开关、导入任务状态、Bucket 权限、ES / IK 和 Embedding 配置。文件上传成功只表示已接收，待索引发布后才能检索。
- **MinIO 镜像缺失**。确认当前 Docker context 已导入与 Compose 标签一致的镜像，并按[镜像交付说明](../infra/minio/README.md)复核归档。

MinIO 镜像仅用于本地学习与开发，不推荐用于公网生产部署。Compose 的 Elasticsearch 关闭认证，prod Profile 的 Session Cookie 要求 HTTPS。
