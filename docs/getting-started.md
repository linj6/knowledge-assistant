# 开始使用

## 环境与基础设施

准备 JDK 21、Maven 3.9、Docker 与 Compose v2。执行 MinIO 生命周期脚本还需 Python 3。[Compose](../compose.yaml) 使用 PostgreSQL 17 / pgvector 0.8.0（固定 digest）、Redis 7.4.11、Elasticsearch 8.18.0 / IK 8.18.0，以及本地 MinIO 镜像。ES 构建需要获取基础镜像和 [IK 安装包](../infra/elasticsearch/Dockerfile)。Compose 将 PG、Redis、ES、MinIO API 和控制台分别绑定到回环地址的 5432、6379、9200、9000、9001 端口。

取得可信来源提供的 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local` 镜像后再启动。若使用离线归档，先比对交付方提供的 SHA-256，再用 `docker load -i <归档路径>` 加载并核对本地标签。Compose 设置了 `pull_policy: never`。历史本机读写、持久化和 save/load 有验证记录，空白环境的交付仍待验证。[MinIO 历史验证](archive/v1-development/engineering/m0-03-infrastructure-verification.md)

在仓库根目录将 [.env.example](../.env.example) 复制为 `.env`，替换基础设施占位凭据，再运行：

```powershell
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

新环境通过 MinIO 控制台创建与应用配置一致的 Bucket，默认是 `knowledge-assistant`。已有环境先确认原 Bucket。按 [MinIO 运维说明](../infra/minio/README.md)配置并检查 `staging/` 两天生命周期规则。

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

仓库内的 local Profile 将模型与重排设为启用且授权，凭据为占位值。初次检查保持外部样例中的关闭值。在线测试还有独立授权门禁，详见 [Maven profile](../pom.xml) 和 [授权测试](../src/test/java/com/lnjecit/knowledge/assistant/OnlineAuthorizationTests.java)。模型请求可能计费，密钥仅保存在本机外部配置或进程环境中。[local 默认配置](../src/main/resources/application-local.yml)

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
- **MinIO 镜像缺失**。先取得与 Compose 标签一致的本地镜像。交付到空白环境的完整流程待 M5-02 验证。

本流程用于本地开发。Compose 的 Elasticsearch 关闭认证，prod Profile 的 Session Cookie 要求 HTTPS。
