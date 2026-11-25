# MEM-05A 离线记忆召回

基线：feature/p2-long-term-memory，82d9a590ef4a6736e253d3e89d5445c05a36ae8a。

[固定样本及 Gold](samples.json) 包含九条虚构记忆和六道质量题。Gold 为 Codex 在首次执行前按语义拟定的初标，待用户 Review。只有能帮助回答当前问题的记忆才标相关。文件首次执行前 SHA-256 为 `8270fb502e496c6518263a28631b88439634772541a8c3b400e6ec8a5808e586`。所有记录属于虚构账户 1，来源约定为 MANUAL，账户 Memory 总开关开启。

入口使用实际 MemoryRecallService，仓储替身提供已启用记录，按更新时间及 ID 倒序返回。上限为 10 条、1000 估算 Token。下表实际列按召回顺序排列。服务日志中的候选计数与逐题实际条数相同，本轮无名额或预算裁剪。

| 题目 | Gold | 实际 | TP | FP | FN | Precision | Recall | 估算 Token |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Q01 | 102, 104, 105 | 104, 105, 101, 102 | 3 | 1 | 0 | 0.7500 | 1.0000 | 308 |
| Q02 | 103 | 103, 101, 102 | 1 | 2 | 0 | 0.3333 | 1.0000 | 252 |
| Q03 | 101, 102, 106 | 106, 101, 102 | 3 | 0 | 0 | 1.0000 | 1.0000 | 231 |
| Q04 | 102, 107 | 107, 101, 102 | 2 | 1 | 0 | 0.6667 | 1.0000 | 251 |
| Q05 | 102, 108 | 101, 102 | 1 | 1 | 1 | 0.5000 | 0.5000 | 165 |
| Q06 | 空 | 109, 101, 102 | 0 | 3 | 0 | 0.0000 | N/A | 221 |

逐题 Precision 为 TP / 实际条数，Recall 为 TP / Gold 条数。零分母记 N/A，分别按指标对有定义的题等权求均值。**宏平均 Precision 54.17%（6/6），宏平均 Recall 90.00%（5/6）。** Token 使用生产估算器计算召回 JSON 数组（仅 type/content），空召回记 0。此开销仅覆盖召回内容，实际 Prompt 注入和供应商用量留待后续观察。

## 误召回和遗漏

- Q01、Q04、Q05 的 101 都通过全局规则进入候选。通用背景在这些具体问题上没有增加 Gold 要求的针对性。
- Q02 的 101、102 同样通过全局规则进入候选。题目要求最小代码，103 已给出直接可执行的示例偏好。
- Q03 全部命中，无误召回和遗漏。
- Q05 漏掉 108。问题采用「知识库问答」「文档分段」，没有出现其关键词 rag 或 chunk。主题召回按子串匹配，无法识别该语义对应。
- Q06 的 109 由引文中的「跑步」触发，101、102 由全局规则进入候选。原样输出是一项指令，三条记忆均不相关。
- 六题为小型人工样本，覆盖一次同义表达遗漏和一次引用中的误命中。默认配置下未观察到预算竞争，仓储排序和过滤由替身提供，指标反映服务召回的样本表现。生命周期、权限与模型生成质量另行验证。

## 生命周期与权限

| 案例 | 复用测试 | 本轮结果 |
| --- | --- | --- |
| L01 修改后读取最新内容 | MybatisCompatibilityTests#memoryRecallReadsOnlyLatestEnabledOwnedRowsInStableOrder | 未运行 |
| L02 停用后排除 | 同上，停用与删除后检查完整 ID 列表 | 未运行 |
| L03 删除后排除 | 同上 | 未运行 |
| L04 关闭总开关 | MemoryRecallServiceTests#disabledOrMissingUserDoesNotQueryMemories | 通过，召回为空且未查记忆仓储 |
| 权限：不存在的账户 | 同上 | 通过，抛业务异常且未查记忆仓储 |
| 权限：跨用户隔离 | 上述 MyBatis 测试，以及 AuthBusinessHttpTests#managesExplicitMemoryWithOwnershipAndCapacity | 未运行 |

生命周期 1 通过 / 1 已执行，另 3 未执行。权限 1 通过 / 1 已执行，另 1 未执行。集成用例依赖 Docker，沿用既有覆盖，本轮未启动基础设施。

## 复现

从仓库根目录运行以下命令。定向测试共 16 个通过，失败、错误及跳过均为 0，Maven validate 阶段 Spotless 检查通过。生成测试使用本地替身。在线 Chat、Embedding、Rerank 均为 0 次。完整回归留到 MEM-05C。

```powershell
mvn -B -ntp test "-Dtest=MemoryRecallEvaluationTests,MemoryRecallServiceTests,AnswerGeneratorTests"
```

[在线 A/B 案例与执行协议](ab-protocol.md) 待用户 Review 和在线调用授权。
