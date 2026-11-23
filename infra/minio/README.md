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
| 对应源代码下载 | [固定版本源码及许可清单](#源码许可证及第三方声明)。完整对应源码仍有缺项。 |

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

两者均报告 Go 1.24.6、GNU AGPLv3 和 MinIO, Inc. 2015–2025 年版权。实测 SHA-256 与镜像内各自的 `.sha256sum` 一致。用上游发布 Dockerfile 中的公钥验证了两份 `.minisig` 的二进制签名和签名内的文件名。两份已验签二进制的 Go 构建元数据均记录 `vcs.modified=true`，主模块版本均带 `+dirty`。这表明编译时工作树有变更，具体变更及其是否进入产物仍待确认。[MinIO 发布 Dockerfile](https://raw.githubusercontent.com/minio/minio/RELEASE.2025-09-07T16-13-09Z/Dockerfile.release)

本机镜像首层的 DiffID 与 Red Hat 官方 `ubi9/ubi-micro:9.6-1754467928` 的唯一文件系统层一致。官方 amd64 manifest digest 为 `sha256:f2516e808dd108b17014693eff2be3d29a3c987d845114e506aa0380e488672e`。其余层的历史显示复制了静态 curl、CA 证书包和入口脚本。镜像内 `curl-amd64` 对应文件的实测 SHA-256 与 static-curl v8.11.0 上游发布清单一致。CA 文件的 SHA-256 与历史 Alpine `ca-certificates-bundle-20250619-r0` APK 中的文件一致。多个 Alpine 分支含有同字节文件，构建镜像所用的 Alpine 分支和基础镜像 digest 仍未确认。[静态 curl 发布校验和](https://github.com/moparisthebest/static-curl/releases/download/v8.11.0/sha256sum.txt)

### 源码、许可证及第三方声明

| 组件 | 实际版本 | 源码及构建资料 | 许可证及核对状态 |
| --- | --- | --- | --- |
| MinIO Server | `RELEASE.2025-09-07T16-13-09Z` | [固定提交源码 tar.gz](https://codeload.github.com/minio/minio/tar.gz/07c3a429bfed433e49018cb0f78a52145d4bedeb) | AGPLv3，快照内有 `LICENSE` 和 `CREDITS`，与镜像内两份文件逐字节一致。发布工作树变更待确认。 |
| mc | `RELEASE.2025-08-13T08-35-41Z` | [固定提交源码 tar.gz](https://codeload.github.com/minio/mc/tar.gz/7394ce0dd2a80935aded936b09fa12cbb3cb8096) | AGPLv3，快照内有 `LICENSE` 和单独的 `CREDITS`。发布工作树变更待确认。 |
| Red Hat UBI 9 Micro 及基础 RPM | `9.6-1754467928`，包版本见资料包 `ubi-rpms.tsv` | 官方源码容器 `registry.access.redhat.com/ubi9/ubi-micro:9.6-1754467928-source`，已确认 manifest 可访问。[Red Hat 源码获取方式](https://access.redhat.com/articles/3410171) | [UBI EULA 适用范围](https://developers.redhat.com/articles/ubi-faq)及各 RPM 自身许可证。基础层已匹配，源码容器内容与 RPM 清单尚未逐项比对。 |
| 静态 curl | `v8.11.0` amd64，curl/libcurl 8.11.0 | [静态构建脚本及发布资产](https://github.com/moparisthebest/static-curl/releases/tag/v8.11.0) · [curl 8.11.0 源码](https://curl.se/download/curl-8.11.0.tar.gz) | curl 许可证，构建脚本为 MIT。发布资产哈希已匹配。 |
| 静态 curl 的链接依赖 | OpenSSL 3.1.7、zlib 1.3.1、libssh2 1.11.0、nghttp2 1.58.0、musl 版本未确认 | 上游版本参考 [OpenSSL](https://github.com/openssl/openssl/tree/openssl-3.1.7) · [zlib](https://github.com/madler/zlib/tree/v1.3.1) · [libssh2](https://github.com/libssh2/libssh2/tree/libssh2-1.11.0) · [nghttp2](https://github.com/nghttp2/nghttp2/tree/v1.58.0) | 已收集前四项的上游许可证，分别为 Apache-2.0、zlib、BSD-3-Clause、MIT。仍需确认 Alpine 历史 APK 修订、补丁及 musl 的实际版本和声明。 |
| CA 证书包 | 与 Alpine `ca-certificates-bundle-20250619-r0` 字节匹配 | [Alpine 20250619 源码](https://distfiles.alpinelinux.org/distfiles/v3.22/ca-certificates-20250619.tar.bz2) · [固定 APKBUILD](https://raw.githubusercontent.com/alpinelinux/aports/78856465c851ab2ce0cb1e12fe9f57d04204919a/main/ca-certificates/APKBUILD) | 历史 APK 声明 MPL-2.0 和 MIT。源码 SHA-512 与 APKBUILD 一致，源码内保留证书数据及其声明。 |

两个源码压缩包均实际取流并检查，含 `go.mod`、`go.sum`、`Makefile`、`buildscripts/gen-ldflags.go`、`LICENSE` 和 `CREDITS`。解压后可用 Go 1.24.6 在各源码目录执行 `go mod download`，按 `go.sum` 校验模块。二进制中记录的 241 项 Server 模块和 98 项 mc 模块均与各自 `go.sum` 的版本及内容校验值一致。Server 的嵌入式 Console 使用 `github.com/minio/console` 的固定伪版本，获取源码时包括其前端源文件及生成步骤。[Console 源码](https://proxy.golang.org/github.com/minio/console/@v/v1.7.7-0.20250905210349-2017f33b26e1.zip)

镜像内 `/licenses/` 提供 Server 的许可和第三方声明，`/usr/share/licenses/` 提供系统包声明。已有容器可通过 `docker cp` 读取这两个目录。本地资料包另含 mc 的独立声明、上表已收集的静态 curl 许可证、CA 证书包源码、RPM 包记录和两份 Go 构建元数据。维护者可在 Git 忽略目录 `target/minio-redistribution/` 找到 `minio-redistribution-materials.zip` 及相邻的 `SHA256SUMS.txt`。这份资料包可手工附在现有下载说明旁，当前公开下载处还需补齐完整对应源码入口。

AGPLv3 的系统库例外适用于满足其定义的系统库。实测 Server 和 mc 均记录 `CGO_ENABLED=0`，本次无需把 UBI 基础 RPM 纳入两者的对应源码清单。基础 RPM 作为独立分发的组件，仍按各自许可证履行义务，例如为 GPL/LGPL 包核对匹配的源 RPM。静态 curl 的链接依赖也应各自审查并保留声明。[GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.en.html)

### 解除公开再分发阻断所需资料

1. 向 MinIO 上游取得两次发布的构建输入或工作树差异，确认 `vcs.modified=true` 的原因，补齐进入产物的变更、生成步骤和所需依赖源码。
2. 用 Red Hat 对应源码容器比对资料包中的源 RPM 名称。用 static-curl 历史 CI 的 Alpine 镜像及构建记录确认实际链接库的 APK 修订和补丁，补齐相关声明。若历史构建记录无法取得，可另行评估由用户从可验证的上游渠道自行获取镜像，或在后续任务中采用可追溯输入构建替代镜像。
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
