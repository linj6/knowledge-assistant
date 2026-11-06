# M0-03 基础设施真实验证

日期：2026-11-06。分支 feature/m0-foundation，起始工作区干净。已阅读产品、架构、计划、兼容矩阵与 Spring AI 2 核心兼容报告。仅实施基础设施，不新增业务表或应用集成。

## 已执行

- Docker Desktop 4.69.0 / Engine 29.4.0 / Compose 5.1.1，desktop-linux daemon 可用。
- `docker compose config --quiet`：退出 0。
- `mvn -B clean verify`：BUILD SUCCESS，23.620 秒；7 tests，0 failures/errors/skipped。
- `docker compose up -d postgres`：成功。首次立即连接遇到 socket 尚未就绪，初始化后重试成功。
- `SELECT version()`：PostgreSQL 17.6 Debian 17.6-1.pgdg12+1。
- 创建独立 m003_probe schema；`CREATE EXTENSION IF NOT EXISTS vector SCHEMA m003_probe` 成功，extversion=0.8.0。
- 创建 vectors(id int, embedding vector(3))，插入 (1,[1,0,0])、(2,[0,1,0])。
- `SELECT id,embedding <=> '[1,0,0]'::vector AS distance FROM vectors ORDER BY distance`：返回 1/0、2/1。
- `docker compose restart postgres` 后重复查询：仍返回 1/0、2/1，证明命名卷数据保留。
- `DROP SCHEMA m003_probe CASCADE` 清理测试表和此次测试扩展，无业务 schema、Flyway 或 HNSW。
- `docker compose ps`：postgres healthy，127.0.0.1:5432。

PG 复现：使用 `docker compose exec -T postgres psql -U ka -d ka -v ON_ERROR_STOP=1 -c "SQL"` 执行上述 SQL；重启前不要清理测试表，复查后再删除 schema。

## MinIO 阻断

- `docker pull quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`：HEAD manifests 返回 401 UNAUTHORIZED。
- 本机虽然存在同版本 Docker Hub 标签，`docker compose up -d postgres minio` 仍失败：`error walking manifest ... descriptor is neither a manifest or index`，未创建 MinIO 容器。
- 有限替代尝试 `docker pull registry-1.docker.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`：pull access denied / repository does not exist or may require docker login。
- Docker info 显示 HTTP/HTTPS Proxy=http.docker.internal:3128；Registry Mirrors 包含 docker.1ms.run、1panelproxy、华为云、daocloud 等多条，以及 your_preferred_mirror 占位地址。没有修改用户全局配置。
- 当前证据提示本机分发链路/缓存有风险，不能断言官方 tag 不存在，也不能断言单一根因。结合 M0-01 的 text/html 层错误，建议人工审查并移除失效镜像代理后有限重试官方 Hub/Quay。
- 维护风险沿用兼容矩阵：官方社区仓库归档/源码分发，AGPL-3.0，旧镜像没有持续安全更新保证；本次未重新完成许可证或漏洞审计。若评估官方源码构建，应先固定 release、源码哈希、工具链、许可证和更新责任，再单独评审，不采用第三方预装镜像。
- S3 创建 bucket、put/get 内容校验、重启对象持久化和清理均未执行，MinIO 不通过。

## 运维

见 README。本地 .env 已忽略；提交的 .env.example 仅占位值。初始化前必须替换。三服务使用默认同一网络和命名卷。ES 无认证仅适用本地开发。未执行 down -v，未改变技术选型。

## ES 与整体验收边界

`docker compose up -d --build` 实际触发官方 ES 8.18.0 下载，解析基础 manifest digest 为 sha256:70b37a809f04cd86e14460f959ff33c272a38f24268eee8b913c2cc73ac86c88。676.22 MB 大层下载至 389.02 MB 后连续约一分钟无进展，总下载超过 200 秒，本次按有限排查边界中断。没有完成基础镜像获取，Dockerfile 的 IK 下载/哈希校验/安装尚未执行成功。

因此 ES 版本运行命令、插件列表、ik_smart/ik_max_word 实际 token、临时技术词索引、BM25 和重启持久化全部未通过验证。后续样本应使用：Spring Boot、Spring AI、PostgreSQL、Redis、RocketMQ、向量检索、知识库、混合检索，并保存两种 analyzer 的 tokens；不能现在推断自定义词典是否必要。

`docker compose restart` 退出 0，但只重启实际存在的 PostgreSQL，不代表三服务通过。重复 `up -d postgres` 成功。全栈 up/build 未完成，三服务健康与整体持久化验收不通过。PG 命名卷保留，未删除任何已有数据卷。

`git diff --check` 退出 0（仅提示 README 换行将转 CRLF）。M0-03 **部分完成、验收阻断**：PG 独立通过；ES 镜像下载与 MinIO 获取/维护问题未关闭。没有进入 M0-04/05/06/07，无 PR 或合并。
