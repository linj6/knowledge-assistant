# M4-D01 Answer Gold 人工评审准备

本材料的逐项表保留首轮评审建议；本轮授权修订以文末“争议项修订结果”为准，历史建议不代表当前数据。

本材料仅给出 Codex 建议，不代表人工确认；REQUIRED/OPTIONAL/REJECTED 是建议角色，不是 reviewStatus。数据集与协议均未改动，全部 16 例及 46 Fact 保持 PENDING_REVIEW，freezeStatus=PROPOSED。

## 核验范围与定位

核对三份指定输入，并读取既有 Gold 对应八篇完整原文，逐文件验证 SHA-256 与 Gold 相同。跨文档检查仅涵盖这八篇快照正文，不递归把链接目标当作已入库文档，不涉及当前停车业务 LIVE KB。原文代码点区间沿用旧 Gold；下列 E0/E1 是该题 goldEvidence 的零基索引，不是运行时 E1 引用编号。

- Q01：`../backend-interview-lab/spring/notes/04-transaction-boundaries-and-propagation.md`；E0=[741,836)。
- Q02：`../backend-interview-lab/spring/notes/04-transaction-boundaries-and-propagation.md`；E0=[992,1188)。
- Q03：`../backend-interview-lab/mysql/notes/01-index-and-sql.md`；E0=[770,1089)。
- Q04：`../backend-interview-lab/rabbitmq/labs/06-prefetch-throughput/evidence/results.md`；E0=[255,732)；E1=[1052,1305)。
- Q05：`../ai-application-lab/03-llm-api-function-calling-spring-ai/notes/05-spring-ai-model-client-and-tools.md`；E0=[47,204)。
- Q06：`../ai-application-lab/04-embedding-vector-search-rag/notes/04-chunking-and-context.md`；E0=[1042,1209)；E1=[1213,1344)。
- Q07：`../ai-application-lab/04-embedding-vector-search-rag/notes/04-chunking-and-context.md`；E0=[1348,1568)。
- Q08：`../ai-application-lab/04-embedding-vector-search-rag/labs/04-hybrid-retrieval/README.md`；E0=[6094,6262)。
- Q09：`../backend-interview-lab/rabbitmq/notes/05-ordered-messages.md`；E0=[1225,1311)；E1=[1686,1755)。
- Q10：`../backend-interview-lab/rabbitmq/notes/05-ordered-messages.md`；E0=[2321,2471)。
- Q11：`../backend-interview-lab/juc/notes/06-completable-future.md`；E0=[470,642)。
- Q12：`../backend-interview-lab/juc/notes/06-completable-future.md`；E0=[4640,4800)。

## 逐 Fact 建议

各行来源依据均来自上面该题对应原文；“直接支持”不等于该信息必须单列计分。通用 qualifications 不能替代具体实验条件。

### Q01：提交异常一定证明已回滚吗？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q01-F01|提交异常不一定证明已回滚|E0：“目标正常返回”不代表“事务成功提交”。rollbackOnly、提交阶段错误等都可能使代理最终失败。若提交期间连接故障，数据库最终状态甚至可能需要核实，不能绝对说所有提交异常都证明已回滚。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q01-F02|目标方法正常返回不保证事务成功提交|E0：“目标正常返回”不代表“事务成功提交”。rollbackOnly、提交阶段错误等都可能使代理最终失败。若提交期间连接故障，数据库最终状态甚至可能需要核实，不能绝对说所有提交异常都证明已回滚。|OPTIONAL|原文直接支持，但回答提交异常是否证明回滚并不必需另述目标返回；旧预期包含它，需用户裁定是否保留严格口径。|
|Q01-F03|提交期间连接故障需要核实数据库最终状态|E0：“目标正常返回”不代表“事务成功提交”。rollbackOnly、提交阶段错误等都可能使代理最终失败。若提交期间连接故障，数据库最终状态甚至可能需要核实，不能绝对说所有提交异常都证明已回滚。|REQUIRED|原文为“甚至可能需要核实”；建议恢复“可能/需核实后判断”，避免把所有连接故障一律表述为同一种结果。|
### Q02：默认回滚规则与吞异常的影响？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q02-F01|RuntimeException 和 Error 默认回滚|E0：默认规则对 RuntimeException 和 Error 回滚，检查异常默认不触发回滚。rollbackFor=IOException.class 明确改变规则。目标内部把异常吞掉、正常返回时，该异常没有到达代理；仅增加 rollbackFor 无法让代理感知它。Controller 的 catch 在 Service 代理外，代理已处理完相应事务后才捕获，不会撤销已完成的回滚或提交。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q02-F02|受检异常默认不触发回滚|E0：默认规则对 RuntimeException 和 Error 回滚，检查异常默认不触发回滚。rollbackFor=IOException.class 明确改变规则。目标内部把异常吞掉、正常返回时，该异常没有到达代理；仅增加 rollbackFor 无法让代理感知它。Controller 的 catch 在 Service 代理外，代理已处理完相应事务后才捕获，不会撤销已完成的回滚或提交。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q02-F03|目标内部吞异常并正常返回、异常未到达代理时，仅增加 rollbackFor 无法让代理感知|E0：默认规则对 RuntimeException 和 Error 回滚，检查异常默认不触发回滚。rollbackFor=IOException.class 明确改变规则。目标内部把异常吞掉、正常返回时，该异常没有到达代理；仅增加 rollbackFor 无法让代理感知它。Controller 的 catch 在 Service 代理外，代理已处理完相应事务后才捕获，不会撤销已完成的回滚或提交。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
### Q03：建表实验的列与索引是什么？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q03-F01|id 为 BIGINT 主键|E0：id BIGINT PRIMARY KEY|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q03-F02|user_id BIGINT 非空|E0：user_id BIGINT NOT NULL|REQUIRED|列及非空为旧预期要求；精确类型有直接 SQL 证据但较旧 expectedAnswerPoints 更细，建议类型作 OPTIONAL 子项，勿因漏写类型判整题失败；是否严格复述 DDL 待用户裁定。|
|Q03-F03|status TINYINT 非空|E0：status TINYINT NOT NULL|REQUIRED|列及非空为旧预期要求；精确类型有直接 SQL 证据但较旧 expectedAnswerPoints 更细，建议类型作 OPTIONAL 子项，勿因漏写类型判整题失败；是否严格复述 DDL 待用户裁定。|
|Q03-F04|created_at DATETIME 非空|E0：created_at DATETIME NOT NULL|REQUIRED|列及非空为旧预期要求；精确类型有直接 SQL 证据但较旧 expectedAnswerPoints 更细，建议类型作 OPTIONAL 子项，勿因漏写类型判整题失败；是否严格复述 DDL 待用户裁定。|
|Q03-F05|amount DECIMAL(10,2) 非空|E0：amount DECIMAL(10,2) NOT NULL|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q03-F06|索引顺序为 (user_id,created_at,status)|E0：KEY idx_user_time_status(user_id, created_at, status)|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q03-F07|另一索引顺序为 (user_id,status,created_at)|E0：KEY idx_user_status_time(user_id, status, created_at)|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q03-F08|引擎为 InnoDB|E0：) ENGINE=InnoDB|OPTIONAL|SQL 直接支持，旧预期包含；当前问题只问列与索引，存储引擎是额外信息，建议不作为漏答失败条件。|
### Q04：预取实验观察是否足以证明性能提升？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q04-F01|参数 (1,1) 暂停时 Unacked=1、活跃处理数=1|E0：- 单测：5项发布Confirm/Return/NACK/超时/连接异常、4项处理顺序/指标/异常不ACK/延时边界、3项H2事务幂等/冲突/回滚；H2不充当MySQL实测证据。 - 真实MySQL+RabbitMQ+Management：参数(1,1)对应RunId `it-376f1145-b134-4c8e-8bcb-3f2ec575ce75`；参数(4,2)对应 `it-3fd83d27-ce00-4306-8de7-8d801a97ab60`。每组24条独立事件，初始Ready=24，处理门闩暂停时Unacked分别为1、8，活跃处理数分别1、2，实际消费者数匹配配置；停止后Unacked=0且Ready>0，再启动最终Ready/Unacked=0，唯一事件24条、积分总和600。以上均由测试断言确认，不依据“启动成功”。 - 恢复过程为逻辑停止监听容器、关闭投递通道后重入队，没有OS kill或网络分区。 - 独立项目bil-rabbitmq-lab06，13321/5678/15678；Volume及测试数据全部保留。|REQUIRED|仅处理门闩暂停时的观测；建议补明参数含义为 prefetch/concurrency，不能推导吞吐率。|
|Q04-F02|参数 (4,2) 暂停时 Unacked=8、活跃处理数=2|E0：- 单测：5项发布Confirm/Return/NACK/超时/连接异常、4项处理顺序/指标/异常不ACK/延时边界、3项H2事务幂等/冲突/回滚；H2不充当MySQL实测证据。 - 真实MySQL+RabbitMQ+Management：参数(1,1)对应RunId `it-376f1145-b134-4c8e-8bcb-3f2ec575ce75`；参数(4,2)对应 `it-3fd83d27-ce00-4306-8de7-8d801a97ab60`。每组24条独立事件，初始Ready=24，处理门闩暂停时Unacked分别为1、8，活跃处理数分别1、2，实际消费者数匹配配置；停止后Unacked=0且Ready>0，再启动最终Ready/Unacked=0，唯一事件24条、积分总和600。以上均由测试断言确认，不依据“启动成功”。 - 恢复过程为逻辑停止监听容器、关闭投递通道后重入队，没有OS kill或网络分区。 - 独立项目bil-rabbitmq-lab06，13321/5678/15678；Volume及测试数据全部保留。|REQUIRED|仅处理门闩暂停时的观测；建议补明参数含义为 prefetch/concurrency，不能推导吞吐率。|
|Q04-F03|尚无人工性能对照采样，不能证明性能提升|E1：`Measure-Backlog.ps1`已做PowerShell语法检查，尚未运行人工性能对照采样；没有报告未经测量的吞吐率/延迟分位数。IT日志中的小样本耗时不作为性能结果。meanHandlerToAckMs是成功ACK回调的平均处理耗时，包含采样前日志开销；不含队列等待，不是端到端延迟。 IDEA点击启动、人工Management观察、大量消息/资源饱和、真实进程崩溃、网络故障均待未来执行。入口见README和Measure-Backlog.ps1。本轮无提交/推送，无删除Volume。 |REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q04-F04|IT 小样本耗时不能作为性能结果|E1：`Measure-Backlog.ps1`已做PowerShell语法检查，尚未运行人工性能对照采样；没有报告未经测量的吞吐率/延迟分位数。IT日志中的小样本耗时不作为性能结果。meanHandlerToAckMs是成功ACK回调的平均处理耗时，包含采样前日志开销；不含队列等待，不是端到端延迟。 IDEA点击启动、人工Management观察、大量消息/资源饱和、真实进程崩溃、网络故障均待未来执行。入口见README和Measure-Backlog.ps1。本轮无提交/推送，无删除Volume。 |REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
### Q05：专题使用哪些 Spring Boot、Spring AI 版本？通过什么接口调用哪一系列模型？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q05-F01|专题 Spring Boot 3.5.15|E0：本专题工程锁定 Java 17+、Spring Boot 3.5.15、Spring AI 1.1.8，以 OpenAiChatModel 连接百炼兼容接口。远程模型为 Qwen；类名含 OpenAi 不代表使用 OpenAI 的模型服务。工程手工声明相关 Bean，不把手工组装解释成 Starter 自动配置。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q05-F02|专题 Spring AI 1.1.8|E0：本专题工程锁定 Java 17+、Spring Boot 3.5.15、Spring AI 1.1.8，以 OpenAiChatModel 连接百炼兼容接口。远程模型为 Qwen；类名含 OpenAi 不代表使用 OpenAI 的模型服务。工程手工声明相关 Bean，不把手工组装解释成 Starter 自动配置。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q05-F03|通过百炼兼容接口调用 Qwen 系列|E0：本专题工程锁定 Java 17+、Spring Boot 3.5.15、Spring AI 1.1.8，以 OpenAiChatModel 连接百炼兼容接口。远程模型为 Qwen；类名含 OpenAi 不代表使用 OpenAI 的模型服务。工程手工声明相关 Bean，不把手工组装解释成 Starter 自动配置。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q05-F04|OpenAiChatModel 类名不代表 OpenAI 模型供应商|E0：本专题工程锁定 Java 17+、Spring Boot 3.5.15、Spring AI 1.1.8，以 OpenAiChatModel 连接百炼兼容接口。远程模型为 Qwen；类名含 OpenAi 不代表使用 OpenAI 的模型服务。工程手工声明相关 Bean，不把手工组装解释成 Starter 自动配置。|OPTIONAL|直接支持且能防混淆；问题已答百炼/Qwen 时，无需再逐字否定 OpenAI 供应商。|
### Q06：两种切分块数相同是否说明质量相同？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q06-F01|两种策略都得到四块|E1：按 Markdown 二级标题切、超长小节先按段落拆，也得到 4 块：S1 是 Confirm 定义（74 字符），S2 是超时与重试（47），S3 是 ACK 及“前一种确认”的结论（88），S4 是消费失败重试（89）。两种策略块数相同，不代表检索质量相同。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q06-F02|固定切分切断词和定义边界|E0：自编 RabbitMQ 教学样本包含 Publisher Confirm、Consumer ACK、消费失败重试。固定最多 100 个 Java 字符单位、无重叠的切分得到 4 块；它把“关联”和“重试流程”等词从中间断开，也把 ACK 标题与部分定义放到前块。这样的问题不是字数 100 的“数学错误”，而是边界没有尊重句子与概念。|REQUIRED|有直接证据；建议把“切断定义边界”准确写成 ACK 标题与部分定义分离，不泛化所有定义均被切断。|
|Q06-F03|块数相同不代表检索质量相同|E1：按 Markdown 二级标题切、超长小节先按段落拆，也得到 4 块：S1 是 Confirm 定义（74 字符），S2 是超时与重试（47），S3 是 ACK 及“前一种确认”的结论（88），S4 是消费失败重试（89）。两种策略块数相同，不代表检索质量相同。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
### Q07：补相邻块能保证 Confirm/ACK 证据完整吗？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q07-F01|S3 加 S2 仍缺 S1 的 Confirm 定义|E0：模拟命中 S3 时，用户要问“生产者收到 Confirm，能否认为消费者已处理完成？”S3 提到“前一种确认”，但定义在 Publisher Confirm 小节。按邻近顺序只补 S2，正文从 88 到 135 字符，仍缺关键定义 S1；再补 S1 达到 209，才补齐两种确认的关系。直接挑 S1 与 S3 的必要正文则是 162 字符。**补相邻块是一种候选方法，不是总能补齐证据的规则。**标题路径能帮助定位，但不会凭空补出正文条件。|REQUIRED|限模拟命中 S3、只补 S2；事实与 F02 是反例和结论，可同句覆盖，无须重复表达。|
|Q07-F02|补相邻块不能保证证据完整|E0：模拟命中 S3 时，用户要问“生产者收到 Confirm，能否认为消费者已处理完成？”S3 提到“前一种确认”，但定义在 Publisher Confirm 小节。按邻近顺序只补 S2，正文从 88 到 135 字符，仍缺关键定义 S1；再补 S1 达到 209，才补齐两种确认的关系。直接挑 S1 与 S3 的必要正文则是 162 字符。**补相邻块是一种候选方法，不是总能补齐证据的规则。**标题路径能帮助定位，但不会凭空补出正文条件。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
### Q08：RRF 如何计算和处理重复 ID？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q08-F01|RRF 对各路 1/(k+rank) 求和|E0：RRF 为 `Σ 1/(rankConstant + rank)`，rank 从 1 开始，默认 constant=60。未命中贡献 0；单路重复 ID 只使用首次命中，去重后赋连续排名；双路同 ID 累加，不把不同尺度的原始分数相加。JSON 分开保存余弦、BM25 和 RRF 分数；融合行同时记录两路排名。没有任一路结果也可运行。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q08-F02|rank 从 1 开始，实验默认 k=60|E0：RRF 为 `Σ 1/(rankConstant + rank)`，rank 从 1 开始，默认 constant=60。未命中贡献 0；单路重复 ID 只使用首次命中，去重后赋连续排名；双路同 ID 累加，不把不同尺度的原始分数相加。JSON 分开保存余弦、BM25 和 RRF 分数；融合行同时记录两路排名。没有任一路结果也可运行。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q08-F03|单路重复 ID 仅取首次命中|E0：RRF 为 `Σ 1/(rankConstant + rank)`，rank 从 1 开始，默认 constant=60。未命中贡献 0；单路重复 ID 只使用首次命中，去重后赋连续排名；双路同 ID 累加，不把不同尺度的原始分数相加。JSON 分开保存余弦、BM25 和 RRF 分数；融合行同时记录两路排名。没有任一路结果也可运行。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q08-F04|单路去重后赋连续排名|E0：RRF 为 `Σ 1/(rankConstant + rank)`，rank 从 1 开始，默认 constant=60。未命中贡献 0；单路重复 ID 只使用首次命中，去重后赋连续排名；双路同 ID 累加，不把不同尺度的原始分数相加。JSON 分开保存余弦、BM25 和 RRF 分数；融合行同时记录两路排名。没有任一路结果也可运行。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q08-F05|跨路同 ID 累加贡献|E0：RRF 为 `Σ 1/(rankConstant + rank)`，rank 从 1 开始，默认 constant=60。未命中贡献 0；单路重复 ID 只使用首次命中，去重后赋连续排名；双路同 ID 累加，不把不同尺度的原始分数相加。JSON 分开保存余弦、BM25 和 RRF 分数；融合行同时记录两路排名。没有任一路结果也可运行。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q08-F06|不相加不同尺度原始分数|E0：RRF 为 `Σ 1/(rankConstant + rank)`，rank 从 1 开始，默认 constant=60。未命中贡献 0；单路重复 ID 只使用首次命中，去重后赋连续排名；双路同 ID 累加，不把不同尺度的原始分数相加。JSON 分开保存余弦、BM25 和 RRF 分数；融合行同时记录两路排名。没有任一路结果也可运行。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
### Q09：单队列消费端保序需要哪些配置？前序消息失败时如何处理？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q09-F01|单队列使用 SAC|E0：使用单队列、Single Active Consumer（SAC）、prefetch=1、manual ACK、同步调用独立的事务 Service，事务成功提交后 ACK。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q09-F02|prefetch=1|E0：使用单队列、Single Active Consumer（SAC）、prefetch=1、manual ACK、同步调用独立的事务 Service，事务成功提交后 ACK。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q09-F03|manual ACK|E0：使用单队列、Single Active Consumer（SAC）、prefetch=1、manual ACK、同步调用独立的事务 Service，事务成功提交后 ACK。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q09-F04|同步调用独立事务 Service|E0：使用单队列、Single Active Consumer（SAC）、prefetch=1、manual ACK、同步调用独立的事务 Service，事务成功提交后 ACK。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q09-F05|事务成功提交后 ACK|E0：使用单队列、Single Active Consumer（SAC）、prefetch=1、manual ACK、同步调用独立的事务 Service，事务成功提交后 ACK。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q09-F06|前序失败时阻止依赖事件先完成业务，不能移走失败消息后直接放行|E1：前序消息失败时，必须阻止依赖它的后续事件先完成业务。可以暂停分区并受控恢复，不能将失败消息移走后仍宣称严格保序。缺点是吞吐受限、队头阻塞。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
### Q10：分区扩容为何不能直接改变取模参数？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q10-F01|直接改取模使旧新事件进入不同队列，可能并行且后事件先提交|E0：**扩容陷阱**：orderId=5，旧规则 5%4=1，新规则 5%8=5。E1 仍在 Queue-1，新事件 E2 却进入 Queue-5，两队列并行消费，E2 可能先提交。不能直接改取模规则。可采用稳定分区映射、停止发布并排空旧分区、有序迁移屏障或带版本路由。分区失败还可能阻塞同分区其他订单。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q10-F02|需要稳定映射或排空/有序迁移屏障/带版本路由等受控方案之一|E0：**扩容陷阱**：orderId=5，旧规则 5%4=1，新规则 5%8=5。E1 仍在 Queue-1，新事件 E2 却进入 Queue-5，两队列并行消费，E2 可能先提交。不能直接改取模规则。可采用稳定分区映射、停止发布并排空旧分区、有序迁移屏障或带版本路由。分区失败还可能阻塞同分区其他订单。|REQUIRED|旧预期包含方案，但当前“为何”问题可只解释风险；建议 REQUIRED 仅要求稳定映射或迁移控制原则，具体方案列表 OPTIONAL，不要求全列。|
### Q11：supplyAsync 与 join 何时提交和等待？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q11-F01|supplyAsync 调用时提交任务，不是 join 启动|E0：这是业务类型片段；完整可运行版本是 [实验 3](../labs/03-completable-future/CompletableFutureLab.java)。两个任务在调用 supplyAsync 时就提交，不是 join 时启动；有两个空闲线程时可以并行。调用者最后 join 仍会占用调用线程等待，不等于整个 HTTP 链路已非阻塞。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q11-F02|提交不代表立即执行或一定并行|E0：这是业务类型片段；完整可运行版本是 [实验 3](../labs/03-completable-future/CompletableFutureLab.java)。两个任务在调用 supplyAsync 时就提交，不是 join 时启动；有两个空闲线程时可以并行。调用者最后 join 仍会占用调用线程等待，不等于整个 HTTP 链路已非阻塞。 原文第123行：任务由指定执行器调度。|REQUIRED|并行有空闲线程条件，执行器调度亦有说明；“不保证立即执行”是调度语义概括，原文未逐字明确，建议改写为由执行器调度、不保证并行。|
|Q11-F03|join 仅在 Future 尚未完成时等待，已完成无需等待|E0：这是业务类型片段；完整可运行版本是 [实验 3](../labs/03-completable-future/CompletableFutureLab.java)。两个任务在调用 supplyAsync 时就提交，不是 join 时启动；有两个空闲线程时可以并行。调用者最后 join 仍会占用调用线程等待，不等于整个 HTTP 链路已非阻塞。|REJECTED|原文直接支持 join 等待调用线程，但未直接说明“已完成无需等待”；不能用 thenApply 已完成回调的文字证明 join。拒绝当前复合措辞作为已充分支持 Gold；建议改写保留等待主干，或另行补充来源后重审，不是删除 join 考点。|
### Q12：orTimeout 会终止底层查询吗？

|Fact ID|当前描述|来源依据|建议|争议与限定|
|---|---|---|---|---|
|Q12-F01|orTimeout 使 Future 超时异常完成|E0：orTimeout 在 Java 9+ 可用，使这个 Future 超时异常完成；不是创建一个 HTTP 读取超时，也不是自动中断底层查询。completeOnTimeout 用默认值完成也一样不保证停止实际计算。CompletableFuture.cancel(true) 的 true 不提供停止底层计算的中断保证。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|
|Q12-F02|不会自动中断底层查询或保证停止实际计算|E0：orTimeout 在 Java 9+ 可用，使这个 Future 超时异常完成；不是创建一个 HTTP 读取超时，也不是自动中断底层查询。completeOnTimeout 用默认值完成也一样不保证停止实际计算。CompletableFuture.cancel(true) 的 true 不提供停止底层计算的中断保证。|REQUIRED|直接支持；保留描述中的条件和否定，不要求固定措辞。|

## Q03 / Q11 决策点

- Q03：逐列拆分便于定位漏项，本身没有重复；但把每个列类型都强制纳入 ALL，会比旧 expectedAnswerPoints 增加要求。建议保留五列、非空、主键、amount 精度及两个索引顺序；user_id/status/created_at 类型作为可选子项，InnoDB 建议 OPTIONAL。不要要求索引名称、表名或完整 SQL 格式。当前数据没有自动变更。
- Q11：F01 明确直接支持；F02 的并行限制有原文依据，“不保证立即执行”属于调度概括；F03 的等待主干有依据，但“已完成无需等待”在八篇正文中没有直接说明。技术上合理不能替代快照证据。建议拒绝当前 F03 复合措辞并重写，或另行扩大来源版本；旧 expectedAnswerPoints 自身也有“尚未完成”限定，这种旧预期与证据不足的差异不能通过沿用标签消除。
- 其他：Q01-F02、Q05-F04 是有依据的解释性补充，建议 OPTIONAL；Q01-F03 需恢复原文不确定性；Q04 必须保留可答身份。Q08、Q09 的拆分无实质重复，跨 Claim 共用证据不是重复 Fact。

## U01–U04 快照核查

|样本|跨八篇核查结论及依据|合理拒答|编造/越界|建议|
|---|---|---|---|---|
|U01|results.md 第18行明确未性能采样；其他七篇没有该预取实验两组吞吐率/P99。CompletableFuture 第33行的349/333ms属于其他示例，不能移用。|无法提供未测量数值，可说明已有 Unacked/活跃数及均值指标边界。|给出吞吐/P99/提升比例，或将 ACK 均值说成端到端 P99。|保留候选；材料不足型。|
|U02|results.md 第8、19行明确仅逻辑停止，真实进程崩溃待执行；其他文档虽链接 ACK crash 实验，但链接不是此快照中的实验结果，也不是预取实验 OS kill 耗时。|说明未测真实 kill，不把逻辑停止等同崩溃。|编造 kill 恢复秒数、成功率。|可保留；与 U03 高度重复。|
|U03|results.md 第8、19行明确没有网络分区、网络故障待执行；其余七篇没有本实验网络分区恢复测量。|说明无测量，允许概述已验证逻辑停止边界。|编造网络分区耗时、丢失率，借其他实验替代。|建议优先替换；与 U02 同源同类仅换故障名。|
|U04|chunking-and-context.md 第29行明确人工模拟且未实测 Embedding 排名；hybrid README 的其他检索实验不能当作 B 中 S3 排名。|区分模拟命中与真实检索，说明不能提供排名。|声称 S3 排第几、给出未经测量召回率。|保留候选；模拟证据被误当真实测量。|

四题均可回答“材料不足及原因”，但不能回答请求的目标数值/排名；这是 knowledgeAnswerable=false 的任务目标口径。检查八篇后未发现能填充目标答案的其他文档，不等于证明整个外部仓库或当前线上全库没有答案。四题并非四种独立拒答场景：U01–U03 都是未执行测量，U02/U03 尤其重复。

建议 U03 替换方向（仅提议，未写入数据）：hybrid README 第95行 D07 明确“本公司 Redis 昨天 P99？没有生产监控数据”。可问“本公司 Redis 昨天的生产 P99 是多少？”，合理回答指出缺少公司监控与日期/服务范围，不能给延迟值或拿开发实验耗时替代。这可覆盖组织私有/时效数据缺失；其来源 SHA 已在八篇清单内，但正式替换仍需用户确认问题边界与身份，不自动新增或确认样本。

## 额外协议争议与验证

现协议要求模型拒答无引用，同时要求拒答中的实质事实 Claim 有引用支持；例如正确说明“本实验尚未测量”可能因此语义支持 FAIL。建议人工决定拒答理由是否独立按来源核验并免引用，或未来允许拒答引用；本轮不修改协议或后端。该矛盾应在冻结前解决。

本轮只新增本评审材料。离线核验八篇来源 SHA、46 个唯一 Fact ID 及本文覆盖、全部 PENDING_REVIEW；未运行 Maven、模型、Docker 或数据库。没有确认 Gold，没有更改数据集、生产代码或用户配置；无新增 Commit/Push/Merge。READY FOR D01 HUMAN REVIEW：是；冻结仍待用户决定。


## 争议项修订结果（当前）

本节取代上文涉及以下争议项的历史建议，不表示其他建议已被采用。

|项目|当前修订|依据与理由|
|---|---|---|
|Q01-F03|提交期间连接故障可能需要核实数据库最终状态|同题 E0 原文“甚至可能需要核实”；恢复非绝对限定。|
|Q03-F01|REQUIRED：id BIGINT 主键|DDL 明确支持，主键与精确列结构为核心。|
|Q03-F02|REQUIRED：user_id BIGINT 非空|保留原文精确类型与非空，不采用上一轮类型可选子项建议。|
|Q03-F03|REQUIRED：status TINYINT 非空|同上，列结构本身是问题目标。|
|Q03-F04|REQUIRED：created_at DATETIME 非空|同上。|
|Q03-F05|REQUIRED：amount DECIMAL(10,2) 非空|保留金额精度和非空。|
|Q03-F06|REQUIRED：(user_id,created_at,status)|保留联合索引字段及顺序。|
|Q03-F07|REQUIRED：(user_id,status,created_at)|顺序区别不能省略；不要求索引名。|
|Q03-F08|OPTIONAL：InnoDB，移入 optionalFacts|来源仍保留；引擎不属于列与索引，遗漏不判完整性失败。|
|Q11-F03|示例中调用者最后 join 会占用调用线程等待组合结果|同题 E0=[470,642)，“调用者最后 join 仍会占用调用线程等待”；限定示例，不追加已完成无需等待，也不泛化所有调用必然阻塞。|
|U03|缺少真实问题与预算时，要求给出通用最优 Chunk 字符/token 数精确值|chunking-and-context.md 第42行明确“只能通过真实问题与预算调试，不能单独指定为通用最优”；原文字节 SHA 与旧 Gold 相同，数据内附 sourceSpan。|

U03 已重新检查八篇完整正文：固定100字符、四块和具体示例长度是教学配置/观测，不提供适用于任意问题和预算的通用最优值；其余七篇也不支持该普适精确值。合理拒答为无法给出统一精确值，并说明缺少真实问题与预算调试；编造包括给出普适最优数值或把100字符包装成最优。可说明方法和已有观测，不要求沉默。此题目标是无法确定的精确值，“为什么不能确定”本身可以回答；该目标口径仍需人工最终认可。未使用动态日期，未采用先前 Redis 昨天的候选。

拒答引用争议已按本轮用户意见解决：ANSWERED 必须合法非空引用；INSUFFICIENT_EVIDENCE 无引用、Citation Semantic Support=N/A，说明仍评价事实准确性与合理性；FAILED 不是拒答。整例需所有适用维度通过。生产协议不变。

当前为12可答、4不可答候选，45 REQUIRED + 1 OPTIONAL，共46条 Fact。所有 case/Fact 继续 PENDING_REVIEW，freezeStatus=PROPOSED。Q01-F02、Q05-F04 的 OPTIONAL 建议及 Q11-F02 调度概括等首轮其他争议未在本轮扩大采用，仍待人工裁定。READY FOR D01 FINAL HUMAN APPROVAL：是，不代表已冻结或获准 D02。


## 最终裁决归档

用户在本次 ChatGPT 会话明确整体批准16 Cases、45 REQUIRED及1 OPTIONAL、当前协议，并指定修订 Q11-F02 为“两个任务通过执行器调度，有两个空闲线程时可以并行；提交任务不保证一定并行执行。”其余事实、题目、拒答边界及角色不变。此前建议仅为历史，不表示用户曾逐条独立审阅。

2026-11-14T08:34:36Z，reviewer=USER；datasetVersion=1，FROZEN，全部 Case/Fact CONFIRMED。指纹 `57c202a788d20e86a2ed166776c77dab6e66bed8b4ae08a1a925806458a5dbca`。当前无剩余待审 Gold 标签；历史争议由本轮整体批准裁决。模型输出和引用支持尚未评估；READY FOR M4-D02，不在本轮实施。
