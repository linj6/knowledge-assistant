# MinIO 镜像交付与本地部署

[Compose](../../compose.yaml) 固定使用 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`（Linux amd64，内置 mc `RELEASE.2025-08-13T08-35-41Z`），并设置 `pull_policy: never`。交付使用已有镜像归档。此镜像仅用于本地学习与开发，不推荐用于公网生产部署。该旧版社区镜像的持续安全更新没有保证。

## 下载、校验与导入

**公开再分发状态：NOT READY。** 现有网盘归档的对应源码交付和组件许可核对仍有缺项，见[网盘再分发资料](#网盘再分发资料)。

| 项目 | 交付信息 |
| --- | --- |
| 网盘下载地址 | [百度网盘下载](https://pan.baidu.com/s/1edkb6uC5-KM-d_WkutpRMQ?pwd=n2ua) |
| 提取码 | `n2ua` |
| 镜像归档 | `minio-RELEASE.2025-09-07T16-13-09Z.tar` |
| SHA-256 | `033da3bb0531f4b071310c1ad44cade4017e694ec554dc482d6da38f01fbcd33`（本地导出归档的实测值） |
| 对应源代码下载 | **待补齐并验证**。上游版本入口见下文。 |

如已持有原始 tar 文件，在归档所在目录执行以下 PowerShell 命令。若使用其他目录，修改 `$archive` 为实际路径。校验失败时停止导入。已有同名镜像时先确认是否需要导入，避免覆盖既有标签。

```powershell
$archive = '.\minio-RELEASE.2025-09-07T16-13-09Z.tar'
$expected = '033da3bb0531f4b071310c1ad44cade4017e694ec554dc482d6da38f01fbcd33'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256 -ErrorAction Stop).Hash -ne $expected) {
    throw 'MinIO 镜像归档 SHA-256 不匹配'
}
docker load -i $archive
if ($LASTEXITCODE -ne 0) { throw 'MinIO 镜像导入失败' }
docker image inspect knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local --format '{{.Os}}/{{.Architecture}}'
```

预期返回 `linux/amd64`。SHA-256 用于核对归档文件；镜像 ID 和 Registry digest 是不同的标识。

## 启动与创建 Bucket

在仓库根目录，将 [`.env.example`](../../.env.example) 复制为 `.env`，已有文件则按需补齐。替换基础设施占位凭据，MinIO 使用 `KA_INFRA_MINIO_USER` 和 `KA_INFRA_MINIO_PASSWORD`。

```powershell
docker compose config --quiet
docker compose up -d --wait minio
docker compose ps minio
```

确认 MinIO 为 `healthy` 后，打开 `http://127.0.0.1:9001`，用上述凭据登录。在 **Buckets** 页面选择 **Create Bucket**，创建 `knowledge-assistant`。已有环境先确认应用配置指向的 Bucket 并复用。自定义名称须与应用的 `assistant.minio.bucket` 一致。S3 API 地址为 `http://127.0.0.1:9000`。

## 配置 staging 生命周期

创建或确认 Bucket 后，从仓库根目录运行（需要 Python 3 和 Docker CLI）。自定义 Bucket 时替换以下两条命令中的名称。

```powershell
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant --check
```

脚本在 Compose 容器内使用 ROOT 环境变量设置本地 alias，不打印凭据，不在上传时配置规则。底层命令为 `mc ilm rule add --prefix staging/ --expire-days 2 ka-ilm/knowledge-assistant`。只新增缺失规则；重复运行检测已有精确规则，不重复新增，不覆盖已有 archive 等其他规则。基础设施配置变更必须串行执行，避免其他管理员同时修改规则导致读改写竞争。

`--check` 仅读取生命周期配置（会配置容器本地 mc alias），缺规则时以非零状态退出；重新执行不带 `--check` 的命令修复。Bucket 不存在、访问失败或无法识别配置均失败，不当作空规则集处理。staging 规则冲突、存在覆盖整个 Bucket 或覆盖 originals 的过期规则时停止，不自动覆盖；先由管理员用 `mc ilm rule export` 备份和审阅，再按规则ID精确修复，禁止无条件替换整份生命周期配置。自定义 Bucket 必须通过 `--bucket` 指定并与应用配置一致。

冲突检查只处理启用的过期规则（包括非当前版本过期）。前缀按S3字面字符串比较，检查双向覆盖：`stag`、`staging`、`staging/子路径`均可能覆盖部分或全部staging对象；`originals`、`originals/`及其子路径会覆盖正式对象。无前缀也覆盖两者。除精确staging/且Days=2的简单规则外，相关过期规则均拒绝，不尝试推算多个规则优先级。

Tag或无法确定范围的过滤拒绝自动修改；And含明确前缀时只用于证明范围无关（例如archive/），与staging/originals有交集时仍拒绝复合规则。简单archive/30天规则、明确仅archive/的And规则以及禁用规则不阻塞配置。未知过滤结构、非字符串前缀或未知启用状态会给出人工审阅提示。不会修改或删除现有规则。

脚本测试：`python -B -m unittest discover -s infra/minio -p "test_*.py" -v`。这些是验证判断及mc调用边界的单元测试；真实mc/MinIO验证使用既有 `mvn -Dtest=StorageImportIT test`。

验收应看到 `verified=true`、`prefix=staging/`、`expireDays=2`。此结果仅验证规则配置；MinIO ILM 异步扫描，两天不是精确删除时刻，本轮没有等待两天观察删除。

## 网盘再分发资料

### 本机镜像核对

2026-11-23 通过 `docker image inspect`、`docker history`、`docker diff` 和已运行容器中的只读命令核对。本机标签与容器指向同一镜像 ID `sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e`，平台为 `linux/amd64`。镜像记录的拉取来源为 `docker.io/minio/minio`，与仓库既有记录一致。历史隔离验证记录了本地归档导入前后的文件系统层一致性。网盘文件本次未取回复核。

| 二进制 | 实测版本 | 实测提交 |
| --- | --- | --- |
| `/usr/bin/minio` | `RELEASE.2025-09-07T16-13-09Z` | `07c3a429bfed433e49018cb0f78a52145d4bedeb` |
| `/usr/bin/mc` | `RELEASE.2025-08-13T08-35-41Z` | `7394ce0dd2a80935aded936b09fa12cbb3cb8096` |

两者均报告 Go 1.24.6、GNU AGPLv3 和 MinIO, Inc. 2015–2025 年版权。实测 SHA-256 与镜像内各自的 `.sha256sum` 一致。用上游发布 Dockerfile 中的公钥验证了两份 `.minisig` 的二进制签名和签名内的文件名。本机两份二进制未发现版本不符或额外修改的证据。完整构建输入、依赖源码和整个镜像的构建证明仍未确认。[MinIO 发布 Dockerfile](https://raw.githubusercontent.com/minio/minio/RELEASE.2025-09-07T16-13-09Z/Dockerfile.release)

镜像标签及层历史指向 Red Hat UBI 9 Micro 9.6，`/etc/os-release` 报告 RHEL 9.6。层历史还显示复制了静态 curl、CA 证书包和入口脚本。实测 curl 8.11.0（musl）报告 libcurl 8.11.0、OpenSSL 3.1.7、zlib 1.3.1、libssh2 1.11.0 和 nghttp2 1.58.0。上游发布脚本通过 `latest` 获取静态 curl。实际命中的资产、补丁及完整构建和声明资料、CA 证书包的具体构建输入，以及 UBI 基础镜像的 Registry digest 仍未确认。[静态 curl 获取脚本](https://raw.githubusercontent.com/minio/minio/RELEASE.2025-09-07T16-13-09Z/dockerscripts/download-static-curl.sh)

### 源码、许可证及第三方声明

- 官方发布页关联的完整提交 SHA 与上表一致。可从两个发布页获取对应 tag 的源代码快照。两套源码均提供 Go 模块定义、`Makefile`、`LICENSE` 和 `CREDITS`。MinIO 发布 Dockerfile 下载并验签预编译的 Server 和 mc，再复制到最终镜像。这些入口提供上游源码和构建资料，完整对应源码仍需按实际产物核对。[MinIO Server 源码与发布页](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z) · [mc 源码与发布页](https://github.com/minio/mc/releases/tag/RELEASE.2025-08-13T08-35-41Z)
- 已有容器可用 `docker cp <容器名>:/licenses ./minio-image-licenses` 和 `docker cp <容器名>:/usr/share/licenses ./minio-system-licenses` 取出随镜像提供的声明。已确认 `/licenses/LICENSE` 为 AGPLv3，`/licenses/CREDITS` 含 Go 标准库等第三方声明，系统目录含 bash、glibc、libgcc 等包的声明。分发资料还应保留上游版权、无担保声明及两套上游的第三方声明。镜像内现有声明对 mc、静态 curl 及其依赖和 CA 证书包的覆盖仍待核对。[Server LICENSE](https://raw.githubusercontent.com/minio/minio/RELEASE.2025-09-07T16-13-09Z/LICENSE) · [Server CREDITS](https://raw.githubusercontent.com/minio/minio/RELEASE.2025-09-07T16-13-09Z/CREDITS) · [mc LICENSE](https://raw.githubusercontent.com/minio/mc/RELEASE.2025-08-13T08-35-41Z/LICENSE) · [mc CREDITS](https://raw.githubusercontent.com/minio/mc/RELEASE.2025-08-13T08-35-41Z/CREDITS)
- Red Hat 允许按 UBI EULA 再分发 UBI 镜像及适用的 UBI 包。其他 RHEL 包和额外组件须按各自的分发依据和许可证处理。镜像内 RPM 数据库可提供已安装包版本、许可证字段和源 RPM 名称，例如 `bash-5.1.8-9.el9.src.rpm`、`glibc-2.34-168.el9_6.23.src.rpm`。仍需把实际包与 Red Hat 的 UBI 内容及对应源 RPM 逐项匹配，并核对适用声明和源码提供义务。[Red Hat UBI FAQ](https://developers.redhat.com/articles/ubi-faq)

### 解除公开再分发阻断所需资料

1. 按已验签的 MinIO Server 和 mc 产物核对并整理完整、可机器读取的对应源码，包括实际依赖、适用补丁及控制构建和安装的脚本。
2. 用现有 RPM 数据库、上游发布记录和组件哈希核对 UBI 包、静态 curl 及其依赖和 CA 证书包的实际来源。收齐适用的许可证、声明、再分发依据和要求提供的源码。
3. 在网盘镜像下载处提供对应源码与许可资料的明确入口，并验证接收方可按 AGPLv3 第 6(d) 节以同等方式获取对应源码，无需额外付费。若源码由另一服务器提供，在镜像下载处指明地址并确保持续可用。[GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.en.html)

## M5-02D 隔离验证记录

使用 Docker Desktop 内的独立临时 Docker daemon（初始镜像与容器数均为 0，未发布端口）验证本地候选归档。校验 SHA-256 后，`docker load` 成功恢复 Compose 标签和 `linux/amd64` 平台。导入后的镜像 ID 与当前宿主机标签的 ID 不同，两者文件系统层一致。

测试容器使用新卷和 Compose 的存活探针，状态达到 `healthy`。创建 `knowledge-assistant` 后，通过 mc 上传并下载 `staging/probe`，内容的 SHA-256 一致。运行仓库脚本添加规则并用 `--check` 复核均成功，mc 导出的规则为启用状态，前缀 `staging/`、过期天数 2。验证后销毁临时环境。

网盘下载、完整 Compose 首次部署及两天后实际异步删除尚未验证。

## 正式对象与历史对象人工核对

ILM 不处理 originals/。即时补偿失败、复制后或PG提交前进程崩溃，仍可能遗留正式孤儿。旧 M1-12 扫描移除后，历史 originals 孤儿也不会自动被 ILM 接管。

默认仅报告候选，不自动批量删除：

1. 安排维护窗口，停止应用接收上传，确认所有上传请求/事务及旧进程已经结束；只停 Worker 不足以停止上传。
2. 用 `mc ls --recursive <alias>/<bucket>/originals/` 导出 Key 和时间，保留备份。优先核对至少两天以前的对象，但时间早不能代替引用校验。
3. 在 PostgreSQL 主库只读查询 `SELECT object_key FROM document WHERE object_key LIKE 'originals/%';`，与对象列表按完整 Key 精确比对；PG 不可用时停止，不能把查询失败当成无引用。
4. 人工逐个审阅差集、告警中本请求 Key 和文件来源；保留仍有引用或归属不明的对象。已有文档无须搬迁或重新上传。
5. 对明确候选使用参数化查询再次核对 `SELECT EXISTS(SELECT 1 FROM document WHERE object_key = :object_key);`。确认无引用、无在途上传且已备份后，人工审批并用 `mc rm <alias>/<bucket>/<exact-key>` 单个删除；不要以列表管道批量删除。恢复上传前记录本次处理结果。

告警 `ORIGINAL_OBJECT_CLEANUP_FAILED` 表示正式对象补偿未完成；`ORIGINAL_OBJECT_REFERENCE_UNKNOWN` 表示引用查询失败而主动保留；`ORIGINAL_OBJECT_REFERENCED_AFTER_COMMIT_ERROR` 表示异常后仍发现已提交引用，禁止按孤儿删除。`STAGING_OBJECT_CLEANUP_FAILED` 可由 ILM 补偿，但仍应检查部署规则存在。

## 2026-11-09 本地Compose部署检查

在基线ab8229a之后执行本轮检查：Docker Desktop可用，原Compose MinIO容器处于exited状态。首次 `python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant --check`因容器停止而失败。仅启动已有容器后再次只读检查，mc返回 **NoSuchBucket**，目标knowledge-assistant不存在。因此按任务要求停止实际部署，没有执行rule add，没有创建替代Bucket、覆盖规则、清理对象或重建数据卷；检查后恢复MinIO原停止状态。

本地目标Bucket尚未部署staging两天规则，也无法验证其originals规则冲突情况；不能将测试专用Bucket的ILM成功记为本地部署成功。需先由用户确认目标Bucket并恢复既有Bucket，再从只读检查开始，不要为了通过验收自动创建新Bucket。两天后异步删除仍未观察。
