# M4-D03-A 本地人工验收

IDEA 启动现有 KnowledgeAssistantApplication，保留既有 local profile；增加启动参数：

```
--server.address=127.0.0.1
```

未覆盖 server.port 时打开 http://127.0.0.1:8080/#evaluation 。若本地端口已配置，使用实际端口。

1. 点击“加载运行记录”，选择 d03-a-c01-history。
2. 切换两个 Chunking，核对 VECTOR / BM25 / HYBRID_RRF 的 11 项汇总值、分母与状态。
3. 切换 Q01～Q12 和 Strategy，检查 Top-K 顺序、正文、来源区间及人工标签。
4. 建议重点看 Q01（单证据）、Q03（表结构）、Q06（Unicode 区间），以及 Q12；比较各策略覆盖数。
5. 检查浏览器控制台，确认无 JS 错误。接口仅读取文件，不运行评估或调用模型。

API：GET /api/v1/evaluation/runs、GET /api/v1/evaluation/runs/{runId}。
评估 API 默认注册，仅供受控本地使用，不适合直接公网暴露。目录固定为 data/evaluation；历史记录损坏或不完整时列表排除，详情返回错误。

限制：Runner 不记录逐条 Gold 覆盖身份，因此每条显示未知，覆盖总数/首个及完整覆盖排名直接来自正式评分。逐题 Recall/CEC/Hit/MRR 数值未另行计算，展示其正式覆盖记录。历史创建时间缺失时显示未记录。Gold 来源校验失败时不提供正文。当前没有完成真实浏览器验收。
