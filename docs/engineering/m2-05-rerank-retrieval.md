# M2-05：Rerank 适配与重排链路

M2-05 复用 M0 Reranker、DashScopeReranker 和 ModelFailures，不修改 HTTP 协议、响应解析、超时、重试或供应商配置。

RerankService 接受 M2-04 已融合并截断的候选，检查唯一 Chunk ID、有效证据、正 RRF 分数、未重排状态以及 rrfScore DESC / chunkId ASC 顺序。RankedChunk 构造器负责来源区间、通道成对字段和有限分数基础约束。服务不重新计算 RRF。

输入 ID 使用 chunkId.toString()，建立 ID 到完整证据的映射，不按正文或结果位置匹配。实际融合池有 N 项，就发送 N 项且 topN=N；fusionTopK 仅作为输入数量上限，finalTopK 不参与重排截断。只调用一次。

模型返回数量必须为 N，逐项检查非 null、已知 ID、无重复、完整 ID 集合及有限 [0,1] 分数。全部通过后按 rerankScore DESC 稳定排序，同分保留原融合顺序。集中复制全部原字段，仅增加 rerankScore，finalRank 保持 null，返回不可变列表，不修改输入。

ObjectProvider 允许模型 Bean 缺失时构造服务。空池直接返回，不访问模型；非空池缺失模型以安全 CONFIGURATION 异常失败。模型及协议错误通过 ModelFailures 安全分类，不传播底层消息或 cause，不返回部分候选，不记录查询、正文或响应。

M2-06 在调用边界记录 RERANK 耗时，并将失败映射至 Phase.RERANK / Failure.RERANK_FAILED；负责保证空池来自成功的上游融合，完成 finalTopK 截断和 finalRank。本服务不创建完整诊断，不推断上游成功状态。

测试使用 Fake Reranker：五项池 / finalTopK=2、乱序分数、同分保序、完整证据复制、不可变输出、空池、缺失模型、非法输入及非法响应。既有 RerankTests 使用本地 Fake HTTP；这些不代表真实供应商故障注入。

本轮执行 spotless:apply、spotless:check、skipTests test-compile 和指定四类回归；最终数量见本次提交的执行报告。未调用真实 DashScope，未启动 Docker，未验证真实 PG/ES/MinIO、端到端性能或排序质量。M0 历史一次在线验证不是本轮在线结果。

执行结果：四类共 26 个测试，失败 0、错误 0、跳过 0（RerankServiceTests 4、RerankTests 8、RrfFusionTests 4、ModelCapabilityTests 10）；格式检查与测试编译成功。

## 收尾阶段一：职责分包

- model：公共检索协议与数据模型。
- snapshot：发布文档、版本快照、读取与 terms 边界。
- embedding：查询向量及生成服务。
- vector：向量召回、Repository 与 Mapper。
- keyword：BM25 召回及 PG 权威证据补全。
- fusion：RRF；rerank：模型重排业务适配。

三个 XML 分别移动至 mapper/retrieval/snapshot、vector、keyword；更新 namespace、resultType 和嵌套 Row 全限定类型。项目无 mapper-locations 覆盖，核对当前 MyBatis-Plus 3.5.18 源码默认值为 classpath*:/mapper/**/*.xml，支持递归。主应用位于 com.lnjecit.knowledge.assistant，Spring 与 MyBatis 自动配置的 @Mapper 扫描覆盖全部新子包，无需新增 MapperScan。

逐类移除 package/import 与空白后比较方法体一致；XML 还原类型前缀后与基线完全一致，未改变 SQL、调用顺序、事务和异常语义。全局搜索无旧 retrieval 根包类引用。更新 M2-02 文档 XML 路径。

阶段一执行 spotless:apply、spotless:check、-DskipTests test-compile 成功；八类单测共 38 项通过（含 QueryEmbeddingTests）。初次尝试因 import 格式未完成被 Spotless 拦截，修正后重跑成功。
真实容器回归 RetrievalFoundationIT、VectorSearchIT、KeywordSearchIT 全部成功，确认新路径 XML 可加载并执行真实 PG 查询（含 ES 到 PG 补全）。未调用供应商模型。


## 收尾阶段二：测试覆盖矩阵

RerankServiceTests 从 4 个方法整理为 41 个独立测试方法（净增 37）。只修改测试和报告，生产行为无修改。

| 类别 | 独立验证内容 |
|---|---|
| 入口 | null/空白 query、null candidates、null 元素、null parameters、超过 fusionTopK、重复 Chunk ID |
| 融合前置 | 原始 VECTOR、原始 BM25、null/0/负 RRF、已有 rerankScore、已有 finalRank、空白正文 |
| 证据与顺序 | 四类 ID 分别验证 0 和负数；RRF 逆序、同分 Long 数值逆序 |
| 对象构造边界 | null ID、null content、NaN RRF、非法 finalRank 在 RankedChunk 构造器拒绝，不绕过不可变对象约束 |
| 正常 | 完整五项池且 finalTopK=2；实际三项池小于 fusionTopK；请求顺序、正文、query、topN、单次调用、准确分数、全部字段复制、输入不变、输出不可变 |
| 同分 | 使用融合顺序 30、2、3，Fake 返回 3、30、2，验证恢复融合优先级而非按数值 ID 排序 |
| 数量 | null、少于输入、多于输入 |
| ID | 两个输入配等长重复 ID、等长未知 ID、等长缺失 ID、含 null 元素；逐项校验不会被数量检查提前遮蔽 |
| 分数 | NaN、正负无穷、负数、大于 1 分别拒绝；0 和 1 接受 |
| 失败边界 | 空池不调用且无模型也成功；非空缺失模型为 CONFIGURATION；敏感异常转换为 OTHER，无 cause、suppressed 或敏感消息 |

所有非法输入测试通过同一轻量辅助方法分别检查 IllegalArgumentException 及调用计数为 0。模型响应失败检查 INVALID_RESPONSE 及恰好调用一次，异常退出不产生部分候选。

等长且缺失某个输入 ID 的响应必定含重复或未知 ID，因此会命中对应前置拒绝分支；通过完整唯一归属检查后，scores.size 与 evidence.size 不一致是数学上不可达的防御条件，不声称该条件被独立执行。Fake 模型故障与本地 Fake HTTP 不代表真实供应商故障注入。

阶段一真实集成测试共 6 项通过（KeywordSearchIT 3、RetrievalFoundationIT 2、VectorSearchIT 1），失败/错误/跳过均 0。阶段二仅修改测试，复用阶段一真实 PG Mapper 加载结论，不重复启动容器。两阶段均未请求真实 DashScope；本轮无真实 Rerank 性能、质量、在线故障验证，未开始 M2-06。

阶段二最终执行：mvn spotless:apply、mvn spotless:check、mvn -DskipTests test-compile 全部成功；mvn '-Dtest=RerankServiceTests,RerankTests,RrfFusionTests,ModelCapabilityTests' test 共 63 项成功（41+8+4+10）；mvn '-Dtest=RetrievalFoundationTests,QueryEmbeddingTests,KeywordRetrievalTests,KeywordProtocolTests' test 共 12 项成功。均无失败、错误或跳过。git diff --check 与 git diff --cached --check 通过。最终审阅仅测试和本报告变化，无生产代码改动。
