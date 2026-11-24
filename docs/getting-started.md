# 开始使用

## Windows 一键部署

启动 Docker Desktop 并切换到 Linux amd64 容器模式。在仓库根目录运行：

```powershell
.\start.ps1
```

如果 Windows PowerShell 因本机执行策略拒绝运行 Git 检出的脚本，可使用 `powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\start.ps1`，策略仅应用于该进程。

脚本生成 Git 忽略的 `.env.onboarding`，检查 Web 端口，准备 MinIO 镜像并构建应用，然后启动独立的 `knowledge-assistant-onboarding` Compose 项目。首次构建需要访问镜像仓库、Maven 仓库及 IK 安装包下载源。JDK、Maven 和 ILM 所需的 Python 随容器提供。PostgreSQL、Redis、Elasticsearch 和 MinIO 使用该项目的持久化卷。MinIO 初始化服务沿用 `staging/` 两天过期规则，发现冲突时停止启动并保留现有规则。数据库由应用启动时的 Flyway 迁移初始化。

已有匹配的 MinIO 本地镜像会直接复用。否则，脚本从固定的 MinIO Server 和 mc 上游 GitHub 提交获取源码，校验 SHA-256 后在 Docker 中编译。首次构建还需访问 GitHub 和 Go 模块下载源。

全新环境需要下载基础镜像和构建依赖，并编译 MinIO 和应用。耗时受网络、CPU 和磁盘速度影响。一次 Windows Docker Desktop 验收使用了独立的 Linux amd64 Docker Engine 29.10.0，起始镜像、构建缓存和数据卷均为空。三次脚本执行合计约 21 分钟，包含两次仓库认证失败及其后续续跑，且续跑保留了当次下载的层和构建产物。

该引擎可用资源为 8 个逻辑 CPU、约 7.7 GiB 内存，验收后引擎报告镜像约 4.3 GB、构建缓存约 8.8 GB。以上为一次实测，耗时和资源占用会随环境变化。请为镜像、构建缓存和持久化数据预留磁盘空间。

应用健康检查通过后，脚本打开 `http://127.0.0.1:8080/api/index.html`。使用 `-NoBrowser` 可跳过打开浏览器。端口被占用时可调整 `.env.onboarding` 中的 `KA_WEB_PORT`。基础设施端口仅在 Compose 网络内可达。

保留 `.env.onboarding` 以复用凭据，并与数据卷一起备份。脚本在检测到已有部署资源而配置缺失时会停止。原有 IDEA 开发环境继续使用下文的配置及项目。

首次启动时，脚本会提示在线调用可能产生费用，并可能将问题与文档内容发送给阿里云百炼。输入 `YES` 确认后，按提示输入百炼 API Key。密钥输入不会回显。确认后同时启用 Chat（`qwen-plus`）、Embedding（`text-embedding-v4`，1024 维）和导入 Worker。Rerank 在一键部署中保持关闭，IDEA 开发配置仍可单独启用 `qwen3-rerank`。

- 输入 `NO` 可继续启动，在线模型和 Worker 保持关闭。授权提示直接回车、密钥输入留空或按 Ctrl+C 可取消交互。
- 后续运行 `./start.ps1` 会复用已保存的授权或关闭状态。已有模型配置不完整时，脚本会重新询问授权。
- 运行 `./start.ps1 -ConfigureModel` 可启用在线模型或更换密钥。重新确认并输入有效格式的密钥后才替换已保存的配置。在该流程中拒绝或取消会保留原配置。
- 运行 `./start.ps1 -RevokeModelAuthorization` 会停止旧应用容器、清除本地密钥，并以在线模型和 Worker 关闭的配置启动。该操作只撤销本部署的授权。若要作废密钥本身，请在阿里云百炼控制台处理。

密钥和授权一起原子写入 Git 忽略的 `.onboarding-model/online.properties`。文件以明文保存在 Windows 本地，通过只读绑定挂载交给 Spring。Compose 环境变量只携带配置路径和非敏感版本号，`docker inspect` 可看到挂载路径。能读取宿主机文件或可通过 Docker 访问容器文件的用户仍可读取密钥。请限制该目录的访问权限，并避免分享配置文件。脚本仅检查密钥格式，实际可用性由后续在线调用验证。

### 一键部署故障排查

- 构建在 Docker Hub 匿名认证或镜像下载阶段超时时，检查 Windows 与 Docker Linux 引擎到 `auth.docker.io` 和镜像仓库的网络。两侧的网络路径可能不同。恢复构建客户端和引擎所需的网络访问后，重新运行 `.\start.ps1`，保留已生成的配置和数据卷。
- 如脚本提示 Web 端口不可用，调整 `.env.onboarding` 的 `KA_WEB_PORT` 后重试。若应用仍未启动，先用 `docker ps -a --filter label=com.docker.compose.project=knowledge-assistant-onboarding` 查看容器状态，再检查失败容器的日志。MinIO 初始化报告规则冲突时，按 [MinIO 生命周期说明](../infra/minio/README.md) 核对 Bucket 和现有规则。
- 可在应用容器内对 `https://dashscope.aliyuncs.com/compatible-mode/v1/models` 发起不带凭据的 HTTPS GET。证书校验通过且收到 HTTP 401 表明 DNS、TCP 和 TLS 路径已连通。密钥有效性与真实 Chat、Embedding 调用仍需另行验证。

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
