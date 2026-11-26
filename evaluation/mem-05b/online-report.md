# MEM-05B 在线 A/B 实验执行报告

使用阿里云百炼 qwen-plus 与已冻结的 AB01～AB06。两组沿用生产生成、解析和引用校验流程，引用校验使用冻结证据构造的候选。

- Chat 请求体发送 12 次，HTTP 层提交 12 次，实验记录 12 条。请求失败计入上限。
- 生成及引用校验成功 12 条，失败 0 条，可供人工盲评的完整配对 6 组。
- Embedding、Rerank、Query Rewrite、工具调用、在线检索和浏览器 E2E 均为 0 次。每题每组最多一次，无补跑。
- 聚合 Token 仅累计供应商返回的字段。N/A 表示供应商未返回该字段。

| Token 字段 | 已知合计 | 返回该字段的请求数 |
| --- | ---: | ---: |
| inputTokens | 12045 | 12 |
| outputTokens | 1570 | 12 |
| totalTokens | 13615 | 12 |

| 案例 | 组别 | 状态 | Prompt | Memory 估算 Token | Input | Output | Total | 耗时 ms |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| AB01 | A | SUCCESS | rag-answer-v1 | 0 | 678 | 175 | 853 | 10513 |
| AB01 | B | SUCCESS | rag-answer-v3 | 231 | 878 | 157 | 1035 | 3830 |
| AB02 | A | SUCCESS | rag-answer-v1 | 0 | 1764 | 238 | 2002 | 5927 |
| AB02 | B | SUCCESS | rag-answer-v3 | 308 | 1989 | 219 | 2208 | 5625 |
| AB03 | A | SUCCESS | rag-answer-v1 | 0 | 1017 | 93 | 1110 | 2333 |
| AB03 | B | SUCCESS | rag-answer-v3 | 252 | 1220 | 141 | 1361 | 3479 |
| AB04 | A | SUCCESS | rag-answer-v1 | 0 | 552 | 161 | 713 | 4047 |
| AB04 | B | SUCCESS | rag-answer-v3 | 297 | 772 | 171 | 943 | 4238 |
| AB05 | A | SUCCESS | rag-answer-v2 | 0 | 674 | 63 | 737 | 1716 |
| AB05 | B | SUCCESS | rag-answer-v3 | 251 | 818 | 60 | 878 | 1740 |
| AB06 | A | SUCCESS | rag-answer-v1 | 0 | 726 | 45 | 771 | 1386 |
| AB06 | B | SUCCESS | rag-answer-v3 | 317 | 957 | 47 | 1004 | 1468 |

## 协议偏差与局限

记录中未观察到协议偏差。

每题只执行一次配对。耗时是单次观测，样本不支持统计显著性判断。报告未做主观评分，质量结论留待人工评审。

逐请求原始结果见 [online-requests.jsonl](online-requests.jsonl)。评审时只分发 [blind-review.json](blind-review.json)，组别映射单独保存在 [unblinding.json](unblinding.json)。

## 验证

离线定向测试 17 个通过，Spotless 检查通过。在线入口运行一次，逐请求结果以上表为准。

基线 2e55278b02bcd59025de8b8b049d1b0193f48431。未执行全量回归，未提交、推送或合并。

## AI 辅助盲评与解盲

按已完成的 AI 辅助盲评汇总，结合上文的匿名材料和映射解盲，6 个配对中无记忆组略优 2 组，基本相当 4 组，有记忆组略优 0 组。

| 组别 | 输入 Token | 总 Token |
| --- | ---: | ---: |
| A 无记忆 | 5411 | 6186 |
| B 有记忆 | 6634 | 7429 |
| B 相对 A | +22.60% | +20.09% |

这次小样本未观察到明显回答质量收益。配对结果来自 AI 辅助盲评，每题只有一次观测。
