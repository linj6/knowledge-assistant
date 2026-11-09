# MinIO staging 生命周期部署

使用现有 Compose 单 Bucket `knowledge-assistant`，固定镜像 `knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local`，内置 mc RELEASE.2025-08-13T08-35-41Z。不得换为未知远端镜像。

先按主 README 启动既有 MinIO、创建 Bucket。仓库根目录运行（需要 Python 3 和 Docker CLI）：

```powershell
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant
python infra/minio/configure-staging-ilm.py --bucket knowledge-assistant --check
```

脚本在 Compose 容器内使用 ROOT 环境变量设置本地 alias，不打印凭据，不在上传时配置规则。底层命令为 `mc ilm rule add --prefix staging/ --expire-days 2 ka-ilm/knowledge-assistant`。只新增缺失规则；重复运行检测已有精确规则，不重复新增，不覆盖已有 archive 等其他规则。基础设施配置变更必须串行执行，避免其他管理员同时修改规则导致读改写竞争。

`--check` 仅读取生命周期配置（会配置容器本地 mc alias），缺规则时以非零状态退出；重新执行不带 `--check` 的命令修复。Bucket 不存在、访问失败或无法识别配置均失败，不当作空规则集处理。staging 规则冲突、存在覆盖整个 Bucket 或覆盖 originals 的过期规则时停止，不自动覆盖；先由管理员用 `mc ilm rule export` 备份和审阅，再按规则ID精确修复，禁止无条件替换整份生命周期配置。自定义 Bucket 必须通过 `--bucket` 指定并与应用配置一致。

验收应看到 `verified=true`、`prefix=staging/`、`expireDays=2`。此结果仅验证规则配置；MinIO ILM 异步扫描，两天不是精确删除时刻，本轮没有等待两天观察删除。

## 正式对象与历史对象人工核对

ILM 不处理 originals/。即时补偿失败、复制后或PG提交前进程崩溃，仍可能遗留正式孤儿。旧 M1-12 扫描移除后，历史 originals 孤儿也不会自动被 ILM 接管。

默认仅报告候选，不自动批量删除：

1. 安排维护窗口，停止应用接收上传，确认所有上传请求/事务及旧进程已经结束；只停 Worker 不足以停止上传。
2. 用 `mc ls --recursive <alias>/<bucket>/originals/` 导出 Key 和时间，保留备份。优先核对至少两天以前的对象，但时间早不能代替引用校验。
3. 在 PostgreSQL 主库只读查询 `SELECT object_key FROM document WHERE object_key LIKE 'originals/%';`，与对象列表按完整 Key 精确比对；PG 不可用时停止，不能把查询失败当成无引用。
4. 人工逐个审阅差集、告警中本请求 Key 和文件来源；保留仍有引用或归属不明的对象。已有文档无须搬迁或重新上传。
5. 对明确候选使用参数化查询再次核对 `SELECT EXISTS(SELECT 1 FROM document WHERE object_key = :object_key);`。确认无引用、无在途上传且已备份后，人工审批并用 `mc rm <alias>/<bucket>/<exact-key>` 单个删除；不要以列表管道批量删除。恢复上传前记录本次处理结果。

告警 `ORIGINAL_OBJECT_CLEANUP_FAILED` 表示正式对象补偿未完成；`ORIGINAL_OBJECT_REFERENCE_UNKNOWN` 表示引用查询失败而主动保留；`ORIGINAL_OBJECT_REFERENCED_AFTER_COMMIT_ERROR` 表示异常后仍发现已提交引用，禁止按孤儿删除。`STAGING_OBJECT_CLEANUP_FAILED` 可由 ILM 补偿，但仍应检查部署规则存在。
