# M4-C01 OFFLINE Runner

仅支持已冻结的 retrieval-metrics-v1，不启动 Spring、不请求模型或基础设施。新增测试源码集 OfflineEvaluationRunner 和定向测试；未修改生产代码、pom、Fixture、Gold 或 B03 归档。

## 执行

在仓库根目录执行（Java 21、Maven）：

```powershell
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.lnjecit.knowledge.assistant.evaluation.runner.OfflineEvaluationRunner" "-Dexec.classpathScope=test"
```

重复生成须追加 `"-Dexec.args=--overwrite"`。固定 Exec Plugin 3.5.0 完整坐标，无需修改 pom。拒绝其他参数及含未知文件的覆盖目录。来源原文字节仍依赖相邻 backend-interview-lab 仓库；缺失即 INPUT_ERROR，不跳过 SHA 校验。

## 产物

默认 `target/evaluation/m4-c01-offline-v1/`：

- manifest.json：OFFLINE、SUCCESS、schemaVersion、runnerVersion、dataset、三个输入文件 SHA-256、完整 frozenPool 及三个非 manifest 产物 SHA-256。manifest 不自引用。
- retrieval-results.jsonl：保留原始 72 次运行，含排名、Chunk 正文与来源信息。
- metrics.json：复用 B03 的 frozenPool/runs/groups；六组追加 evidence，包含七项指标 numerator/denominator/value 及执行、质量和不可评分计数。逐题保留 Evidence 覆盖计数、First Rank、Complete Rank 和 Ranking 状态。
- report.md：六组关键指标、有效分母、Ranking 状态与数据来源。HALF_EVEN 三位展示；JSON 不提前舍入，无时间戳。

冻结指纹保持 `4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c`。C01 同时锁定该指纹，拒绝更换 Gold 或确认标签。六组结果见生成 report.md，与 [B04](m4-b04-retrieval-evaluation-summary.md) 的 Evidence/Ranking 列一致；人工 Semantic Complete Coverage 不在 C01 重算范围。

## 验证与限制

定向测试逐项比较 B03 原始归档（移除新增 evidence 汇总后完全一致），比较 B02 六组七项精确分子与分母，比较两次运行四文件完整字节，验证默认拒绝覆盖、显式覆盖、错误输出路径、非法参数及缺失输入的非零退出且无成功产物。

失败分类为 INPUT_ERROR、FREEZE_ERROR、METRIC_ERROR、OUTPUT_ERROR。先在同级临时目录完成全部产物再移动发布；显式覆盖时删除旧 manifest 后替换，失败可能失去旧产物，不提供事务恢复。manifest SUCCESS 表示离线执行完成，质量失败和不可评分结果仍按指标状态记录。

不运行全量回归、Docker、PG、ES、MinIO 或在线模型。仅限当前 12 问、2 种切分、3 策略、72 runs、24 pools、186 labels；不支持 LIVE、动态池或外部数据集。本轮不 Commit、Push、Merge，HEAD 保持 82ce629c1366faf13a0e3ed358303ed53e36598b。

实际验证：Spotless apply/check、test-compile、首轮四类定向测试 12 项通过；补充缺失输入与 B02 全分子断言后 C01 三项重跑全部通过。Maven CLI 首次运行及显式覆盖运行均成功；四文件两次独立生成逐字节一致。git diff --check 与新增文件空白检查无错误。READY FOR M4-C02：是，仅表示 C01 完成，不授权实施 C02。
