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

## M0-03b：独立关闭 ES，MinIO 等待全局配置确认

2026-11-06，起点 f4a2740，feature/m0-foundation，工作区干净。未修改 Docker 全局配置，未删除镜像缓存或数据卷。

### 获取环境实测

- Docker info：Mirrors 仍含原有 19 项，HTTP/HTTPS Proxy 均为 http.docker.internal:3128。
- PowerShell Invoke-WebRequest，单请求 TimeoutSec=8：your_preferred_mirror/v2/ DNS 无法解析；1panelproxy/v2/ 超时；1ms.run/v2/ 返回 401；docker.elastic.co/v2/ 返回 401（Registry 认证挑战本身不表示镜像不可获取）；registry-1.docker.io/v2/ TLS 建连失败。
- `docker buildx imagetools inspect minio/minio:RELEASE.2025-09-07T16-13-09Z`：连接 registry-1.docker.io 解析地址 31.13.95.17:443 超时。此结果值得检查 DNS/网络路径，不足以确定单一根因。
- `docker image inspect minio/minio:RELEASE.2025-09-07T16-13-09Z` 存在 RepoDigest sha256:ceac4e5b13320ceb8fea08c5b4ca7c4782431ce2a27164d8ea301b90455875be，但再次 `docker pull` 报同 digest 的 unexpected media type text/html，无法解包。标签和 digest 元数据存在不代表镜像有效。
- 下一步建议：经人工确认后暂清空 Registry Mirrors 为 []，保留当前代理，重启 Docker 后有限重试官方源。影响所有项目的镜像拉取路径，重启暂时中断容器，不删除数据卷。尚未获批准，未实施。

### ES/IK 独立验收通过

`docker compose build elasticsearch` 成功：官方 docker.elastic.co/elasticsearch/elasticsearch:8.18.0，大层完整下载解包（约 274 秒）；基础 digest 与前记录一致。官方 IK stable ZIP SHA256 校验输出 /tmp/ik.zip: OK，安装 analysis-ik 成功。构建提示插件使用 legacy Security Policy，并请求 outbound_network entitlement；保留该上游兼容提示，不等于安装失败。

初次本地镜像 manifest list sha256:74a5e1cc4d81a43b4f7eac6eb05dc445d7c1ccc4a85357664b5984f64a60721f；再次缓存构建因 provenance 生成新 list digest 7205c0434d1a0e3d5b0939cc50bdcf8b7fb473c1e4836bedb912aee7884c5d1c，不能把本地构建 digest 当官方发行 digest。

`docker compose up -d elasticsearch` 成功；插件 CLI list=analysis-ik；GET / 版本 8.18.0，Lucene 9.12.1；GET /_cat/plugins?format=json 返回 analysis-ik 8.18.0；Compose healthy。

POST /_analyze 输入 `Spring Boot Spring AI PostgreSQL Redis RocketMQ 向量检索 知识库 混合检索`：

- ik_smart：spring, boot, spring, ai, postgresql, redis, rocketmq, 向量, 检索, 知识库, 混合, 检索
- ik_max_word：spring, boot, spring, ai, postgresql, redis, rocketmq, 向量, 检索, 知识库, 知识, 库, 混合, 检索

复现：POST JSON {"analyzer":"ik_smart","text":"上述样本"}，再替换 analyzer。英文短语和部分中文术语拆分属实际结果；若后续要求术语整体匹配/召回，可评估自定义词典，本任务未添加。

PUT /m003b-probe：number_of_replicas=0；settings.similarity.m003b_bm25.type=BM25；content 类型 text，analyzer=ik_max_word、search_analyzer=ik_smart、similarity=m003b_bm25。
PUT /m003b-probe/_doc/1?refresh=true：content="Spring Boot 与 Spring AI 支持知识库向量检索和混合检索"；文档2 content="PostgreSQL Redis RocketMQ 技术学习"。
POST /m003b-probe/_search，query.match.content="知识库 混合检索 Spring AI"，explain=true：仅命中 id=1，score=3.4239612；explanation 明确包含 freq/(freq+k1*(1-b+b*dl/avgdl))、k1、b、avgdl。

`docker compose restart elasticsearch` 后首次过早请求 ResponseEnded；待服务 healthy 再查：_count=2，同查询 id=1 / score=3.4239612，持久化通过。DELETE /m003b-probe acknowledged=true，临时索引清理完成。

### 回归与剩余阻断

- compose config --quiet 退出0。
- PG 在事务内创建 m003b_probe / vector 扩展和测试表，extversion=0.8.0，余弦结果 id1=0、id2=1；ROLLBACK 清理，回归通过。
- mvn -B clean verify：BUILD SUCCESS，1分13秒，7 tests、0 failures/errors/skipped。
- compose up -d --build：ES 缓存构建通过，随后 MinIO 创建失败 descriptor is neither a manifest or index，整体退出1。
- compose ps：PG、ES healthy，MinIO 不存在。MinIO S3 Put/Get、校验、重启持久化未执行，仍不通过。
- MinIO 未构建源码、未改变选型；维护风险沿用前记录。候选方案是评审固定官方 release 源码/二进制、独立可信哈希、许可证及工具链后再决定本地镜像构建；本次没有证明该构建路径可用，不直接实施复杂构建。
- M0-03 仍为部分完成；只关闭 ES/IK 独立运行与持久化阻断。等待 Docker 全局配置确认。未进入 M0-04，无 PR/合并。

## M0-03b 补充：两个历史 MinIO RELEASE 的有限排查

2026-11-06，起点 af70893，feature/m0-foundation，工作区干净。用户已取消清空 Registry Mirrors 和修改 Docker Desktop 全局配置的计划；此前的配置修改确认请求失效，不再等待或执行。现有 Docker 环境、镜像缓存和数据卷均未修改/删除。

### 官方候选与发布时间（UTC）

仅选择以下两个版本，先核实官方 GitHub Release 页面，再依次拉取官方 Docker Hub 命名空间；没有猜测新 Tag：

| RELEASE | 官方来源 | GitHub 发布时刻 | 尝试的镜像地址 |
|---|---|---|---|
| RELEASE.2025-04-22T22-12-26Z | https://github.com/minio/minio/releases/tag/RELEASE.2025-04-22T22-12-26Z | 2025-04-22T22:44:34Z | docker.io/minio/minio:RELEASE.2025-04-22T22-12-26Z |
| RELEASE.2024-12-18T13-15-44Z | https://github.com/minio/minio/releases/tag/RELEASE.2024-12-18T13-15-44Z | 2024-12-19T13:50:56Z | docker.io/minio/minio:RELEASE.2024-12-18T13-15-44Z |

GitHub API 查询遇到未认证 rate limit，改用官方 Release HTML 核实。PowerShell Invoke-WebRequest 页面成功，提取 `<relative-time class="no-wrap" ... datetime="...">` 得到上述发布时刻；发布时刻与 tag 内构建时刻不同，尤其 2024 候选实际于次日发布。官方页面确认 RELEASE 存在，不等于本次已证明 Docker Hub 当前仍正确分发对应镜像。

### 实际拉取：每版本仅一次

```powershell
docker pull minio/minio:RELEASE.2025-04-22T22-12-26Z
docker pull minio/minio:RELEASE.2024-12-18T13-15-44Z
```

两个命令均退出 1，关键输出完全相同：

```text
ceac4e5b1332: Already exists
failed to unpack image on snapshotter overlayfs: unexpected media type text/html for sha256:ceac4e5b13320ceb8fea08c5b4ca7c4782431ce2a27164d8ea301b90455875be: not found
```

不同历史标签均解析到同一异常内容 digest，是分发链路/缓存异常的证据线索，不能据此认定真实官方镜像内容相同，也不能证明官方版本不存在；未定位到具体镜像源或代理根因。

### 结论与停止边界

两个版本均失败，按用户要求停止镜像排查，不尝试更多历史版本，不改用其他对象存储，不构建源码，不修改全局配置。未获得可运行的候选，故不更新 compose.yaml；MinIO 实际版本、健康检查、S3 Bucket/PutObject/GetObject/内容一致性及重启持久化均未执行，不通过。PG/ES 既有独立验收结论保持，M0-03 仍部分完成、MinIO 阻断未关闭。

这些历史版本即使未来能运行，也仅能证明本地部署可用，不能证明仍受安全维护；官方页面当前显示仓库于 2026-04-25 归档。没有将旧版本描述为当前受支持版本。

本轮只更新本验证文档，未改 Java、Compose 或依赖，因此未重复 Maven/PG/ES 测试；此前回归结果见上一节。git diff --check 与暂存区 diff --check 均退出 0。未进入 M0-04，无 PR、合并或下一任务。
