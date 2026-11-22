# CONV-01 最终验收

## 完成范围

- 会话创建、列表、详情及消息历史分页，按登录用户和知识库归属校验访问。
- Turn 提交与异步执行，同一会话只允许一轮活跃任务。重复提交相同 Turn ID 和问题时返回已有轮次，问题不同时拒绝。保存问题、回答状态及引用，并通过 Turn 查询接口返回结果。
- 排队任务可取消，运行中的任务可取消该轮绑定的模型 HTTP 请求。条件更新保护终态。进程重启后，遗留的 `PROCESSING` 转为 `FAILED`（`TURN_PROCESS_RESTARTED`），`CANCEL_REQUESTED` 转为 `CANCELLED`。

## 验收结果

CONV-01E 最终定向验收共 11 项通过，0 失败、0 错误、0 跳过。Surefire 报告中 `ModelCancellationTests` 通过 10 项，`RetrievalServiceIT#answersConversationThroughHybridRetrieval` 通过 1 项。此前已验收会话权限、持久化和重启恢复。

- `1576089` 中的取消测试调整兼容 SDK 总超时和读超时两种异常表现，保留并核对超时时间、连接关闭、调用句柄清理及请求次数。
- 混合检索用真实 PostgreSQL/pgvector、Elasticsearch/IK 和 Redis 会话，配合固定 Embedding 和 Chat 测试替身，验证从认证、提交到向量与 BM25 检索及融合、生成、引用校验和落库的链路。请求和实际执行策略均为 `HYBRID_RRF`，未发生降级。
- 收尾时审查了已有测试报告和最终 diff，未重复执行测试，也未发现阻断问题。

## 已知限制

- 取消验收使用本地 HTTP 服务，覆盖客户端连接关闭及任务终态。供应商侧是否停止推理或计费未验证。混合检索用例未调用真实模型，也未评估模型输出质量。
- 重启恢复将中断任务收敛到终态，任务不会自动续跑。历史消息可通过 API 查询，多轮 Prompt 上下文属于后续任务。
