# MEM-05B 离线预检报告

**READY FOR ONLINE EVALUATION**

基线为 `feature/p2-long-term-memory` 上的 `2e55278b02bcd59025de8b8b049d1b0193f48431`。本轮仅执行离线预检。实际供应商 Chat、Embedding、Rerank、工具调用、在线检索和 Query Rewrite 均为 0。在线成功、失败及可评配对均为 0，供应商 input/output/total Token 与生成耗时均为 N/A。实际回答、引用、匿名评审材料和解盲映射待授权运行后生成。

## 冻结输入

[cases.json](cases.json) 按 AB01～AB06 冻结原始问题、历史及有序证据。各段包含案例内证据 ID、仓库相对路径、从 1 起算且含首尾的来源行范围、逐行原文及 SHA-256。正文哈希基于 UTF-8，来源行以 LF 连接，保留选中行中的空行及空格。[cases.sha256](cases.sha256) 校验整个案例文件，计算前将 CRLF 统一为 LF。问题与证据范围按 [MEM-05A 协议](../mem-05a/ab-protocol.md) 冻结。记忆来自 [原始样本](../mem-05a/samples.json)，仅 AB06 的内存副本追加 110。

## 本地结果

每题将同一个 AssembledContext 传给生产 AnswerGenerator。Stub 捕获了 12 个最终 Prompt，核对了原始问题、有效历史、Evidence 正文、ID 与顺序。AB05 的一轮历史在两组均完整保留，其余为空。两组配置均为 qwen-plus、temperature 0.1、输出上限 1024、SDK 重试配置 0、生产请求总预算 12000 估算 Token（含输出预留）。

| 案例 | A / B Prompt 版本 | B 召回及注入 ID（顺序相同） | 生成阶段裁剪 | B Memory 估算 Token |
| --- | --- | --- | ---: | ---: |
| AB01 | rag-answer-v1 / rag-answer-v3 | 106, 101, 102 | 0 | 231 |
| AB02 | rag-answer-v1 / rag-answer-v3 | 104, 105, 101, 102 | 0 | 308 |
| AB03 | rag-answer-v1 / rag-answer-v3 | 103, 101, 102 | 0 | 252 |
| AB04 | rag-answer-v1 / rag-answer-v3 | 108, **109**, 101, 102 | 0 | 297 |
| AB05 | rag-answer-v2 / rag-answer-v3 | 107, 101, 102 | 0 | 251 |
| AB06 | rag-answer-v1 / rag-answer-v3 | **110**, 106, 101, 102 | 0 | 317 |

A 组召回及注入均为空，Memory 开销为 0。B 组 Memory Trace 与 Prompt 中实际的类型、正文和顺序一致，AB04 的无关记忆 109 及 AB06 的错误记忆 110 均进入最终 Prompt。估算值仅计量注入的 Memory JSON 数组。结构化输出解析及引用校验使用 Stub 回答和冻结证据构造的候选，覆盖引用映射，未验证线上检索发布快照。MEM-05B Stub 捕获点为 SDK 调用前；既有 AnswerGeneratorTests 另用本地回环 HTTP 验证 SDK 请求参数及 429、401 不重试。

## 复现与边界

```powershell
mvn -B -ntp test '-Dtest=MemoryAbPreflightTests,MemoryRecallEvaluationTests,MemoryRecallServiceTests,AnswerGeneratorTests'
git diff --check
```

定向测试 17 个通过，失败、错误、跳过均为 0，Maven validate 中 Spotless 检查通过。未执行完整回归、Docker 集成或浏览器业务 E2E。在线阶段需要明确调用授权，按原协议每题只跑一次配对，最多 12 次 Chat 请求，失败计入上限，禁用工具与自动重试。该设计比较实际召回、预算和 Prompt 共同组成的方案，且样本规模不足以声称统计显著性。质量结论留待人工盲评。
