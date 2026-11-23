# MinIO 镜像交付与本地部署

[Compose](../../compose.yaml) 固定使用 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`（Linux amd64，内置 mc `RELEASE.2025-08-13T08-35-41Z`），并设置 `pull_policy: never`。交付使用已有镜像归档。此镜像仅用于本地学习与开发，不推荐用于公网生产部署。该旧版社区镜像的持续安全更新没有保证。

## 下载、校验与导入

| 项目 | 交付信息 |
| --- | --- |
| 网盘下载地址 | [百度网盘下载](https://pan.baidu.com/s/1edkb6uC5-KM-d_WkutpRMQ?pwd=n2ua) |
| 提取码 | `n2ua` |
| 镜像归档 | `minio-RELEASE.2025-09-07T16-13-09Z.tar` |
| SHA-256 | `033da3bb0531f4b071310c1ad44cade4017e694ec554dc482d6da38f01fbcd33`（本地导出归档的实测值） |
| 对应源代码下载 | **待填写：随镜像提供的完整对应源代码及许可证资料下载地址** |

从百度网盘下载原始 tar 文件。在下载目录执行以下 PowerShell 命令。若使用其他目录，修改 `$archive` 为实际路径。校验失败时停止导入。已有同名镜像时先确认是否需要导入，避免覆盖既有标签。

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

- MinIO Server 和 mc 的固定版本均采用 GNU AGPLv3，二进制均声明 MinIO, Inc. 2015–2025 年版权。验收镜像包含 `/licenses/LICENSE`、`/licenses/CREDITS` 及 `/usr/share/licenses/` 下的系统包声明。分发镜像时应附上许可证全文，保留上游版权、许可证和无担保声明，以及随镜像组件提供的第三方声明。项目本地标签用于 Compose 引用。上游版本入口见 [MinIO Server](https://github.com/minio/minio/tree/RELEASE.2025-09-07T16-13-09Z) 和 [MinIO Client](https://github.com/minio/mc/tree/RELEASE.2025-08-13T08-35-41Z)。
- 按 AGPLv3 第 6(d) 节通过网盘交付二进制时，在同一下载位置提供完整、可机器读取的对应源代码，并提供同等访问且不额外收费。对应源代码须匹配实际二进制，包含适用的补丁、构建与安装脚本及所需源码。若源码由另一服务器提供，须在镜像下载处明确标注获取方式，并确保等效访问和持续可用。上述上游版本入口用于溯源，完整对应源代码交付仍待确认。[GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.en.html)
- 镜像标签元数据标识基础层为 Red Hat UBI 9 Micro。分发前按实际镜像核对基础层及其他组件的许可证、版权声明和源码获取义务，保留所需资料。完整组件清单、对应源码包及其下载地址仍待补齐，再分发许可验收尚未完成。[Red Hat UBI FAQ](https://developers.redhat.com/articles/ubi-faq)

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
