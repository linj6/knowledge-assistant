# M2-05：Rerank 适配与重排链路

M2-05 复用 M0 Reranker、DashScopeReranker 和 ModelFailures，不修改 HTTP 协议、响应解析、超时、重试或供应商配置。

RerankService 接受 M2-04 已融合并截断的候选，检查唯一 Chunk ID、有效证据、正 RRF 分数、未重排状态以及 rrfScore DESC / chunkId ASC 顺序。RankedChunk 构造器负责来源区间、通道成对字段和有限分数基础约束。服务不重新计算 RRF。

输入 ID 使用 chunkId.toString()，建立 ID 到完整证据的映射，不按正文或结果位置匹配。实际融合池有 N 项，就发送 N 项且 topN=N；fusionTopK 仅作为输入数量上限，finalTopK 不参与重排截断。只调用一次。

模型返回数量必须为 N，逐项检查非 null、已知 ID、无重复、完整 ID 集合及有限 [0,1] 分数。全部通过后按 rerankScore DESC 稳定排序，同分保留原融合顺序。集中复制全部原字段，仅增加 rerankScore，finalRank 保持 null，返回不可变列表，不修改输入。

ObjectProvider 允许模型 Bean 缺失时构造服务。空池直接返回，不访问模型；非空池缺失模型以安全 CONFIGURATION 异常失败。模型及协议错误通过 ModelFailures 安全分类，不传播底层消息或 cause，不返回部分候选，不记录查询、正文或响应。

M2-06 在调用边界记录 RERANK 耗时，并将失败映射至 Phase.RERANK / Failure.RERANK_FAILED；负责保证空池来自成功的上游融合，完成 finalTopK 截断和 finalRank。本服务不创建完整诊断，不推断上游成功状态。

测试使用 Fake Reranker：五项池 / finalTopK=2、乱序分数、同分保序、完整证据复制、不可变输出、空池、缺失模型、非法输入及非法响应。既有 RerankTests 使用本地 Fake HTTP；这些不代表真实供应商故障注入。

本轮执行 spotless:apply、spotless:check、skipTests test-compile 和指定四类回归；最终数量见本次提交的执行报告。未调用真实 DashScope，未启动 Docker，未验证真实 PG/ES/MinIO、端到端性能或排序质量。M0 历史一次在线验证不是本轮在线结果。
`n执行结果：四类共 26 个测试，失败 0、错误 0、跳过 0（RerankServiceTests 4、RerankTests 8、RrfFusionTests 4、ModelCapabilityTests 10）；格式检查与测试编译成功。
