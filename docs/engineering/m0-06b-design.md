# M0-06B-1 Rerank 与 Tokenizer 技术调查及接口设计

调查日期：2026-11-07。状态：待人工架构评审；仅调查与设计，未实现、未调用真实模型。本文件不代表 M0-06 或 M0 验收完成。

## 1. 目标与项目基线

已阅读 AGENTS.md 及其引用的 Product Spec、Architecture Spec、Implementation Plan、Code Style、任务模板和模型能力报告，并检查 infrastructure/ai、ModelProperties、application.yml、ApiExceptionHandler、ModelCapabilityTests、SpringAiCoreCompatibilityTests。

**本地检查确认**：开始时 feature/m0-foundation 工作区干净；pom.xml 为 Java 21、Boot 4.1.2、AI BOM 2.0.2，使用 openai/client-chat/vector-store，无 DashScope 依赖。Architecture Spec 仍写 Boot 3.x，这是已知冲突，本次不修改冻结文档。当前授权覆盖 AGENTS 中过期的停止指令。

**已有记录确认，非本次复测**：M0-06A qwen-plus Chat/JSON Mode 成功，Embedding 1024 维；Embedding 模型 ID 未确认。现有模型开关默认关闭、超时30秒、SDK重试0、Chat输出128。M0-06整体还要求 Rerank 真实成功、ID 映射、Token边界、成本和故障证据；本次只为后续提供设计，不声称达到整体验收。

## 2. Rerank 官方能力

**官方文档确认**：[文本排序 API](https://help.aliyun.com/zh/model-studio/text-rerank-api) 当前列出 qwen3.7-text-rerank、qwen3-rerank、gte-rerank-v2、qwen3-vl-rerank；旧 gte-rerank 已公告下线，不选用。推荐 qwen3-rerank：纯文本、支持中英文，符合当前 Markdown/TXT 范围。语言支持不等于本项目技术资料排序质量已经验证。

**官方文档确认**：推荐模型最多500篇；query及单篇各4000 Token，请求总量120000 Token，按 query Token数乘文档数再加所有文档 Token数计算。超长返回400，不应假设自动截断。其他模型限制不同，不共用该表；qwen3.7 表格与通用 query 字段描述存在长度差异，本方案不依赖该模型。

**官方文档确认**：HTTPS POST，Authorization: Bearer API Key，Content-Type: application/json。当前北京示例地址为 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank`。原生请求体包含 model、input.query、input.documents（字符串数组），parameters.top_n、return_documents、instruct。top_n省略返回全部，大于文档数返回全部。return_documents默认false；qwen3-rerank支持返回原文。instruct可省略采用默认问答排序。

**官方文档确认**：响应 output.results 每项含零基原始 index、relevance_score，开启原文返回时含 document.text；还有request_id、usage.total_tokens。分数范围[0,1]，只用于当前请求相对排序。兼容接口另有 `/compatible-api/v1/reranks`，请求字段扁平、结果在顶层results；不能混用两套DTO，也不能由“OpenAI兼容”推断当前Spring AI支持。

**官方文档确认**：[价格页](https://help.aliyun.com/zh/model-studio/model-pricing) 北京qwen3-rerank目录价每百万输入Token 0.5元，输出不计费。地域、优惠、权限以实际账户为准；没有本次账单或费用实验。top_n减少返回量不等于只对返回文档计费。

**尚未验证**：当前账户地域、业务空间Endpoint、权限、限额、真实延迟、费用及技术资料质量。旧Chat配置使用dashscope compatible-mode地址，不能直接拼接重排路径；实施前人工确认完整Endpoint，不自动迁移Chat配置。

## 3. Spring AI 2.0.2 本地证据

**本地 SDK 检查确认**：实际缓存位置 `D:/develop/repository/maven/org/springframework/ai/`，检查 client-chat、commons、model、openai、template-st、vector-store 的2.0.2 JAR。对每个执行 `jar tf`，搜索 Rerank/Ranker/TokenCount/Tokenizer：没有Rerank/Ranker类或DashScope实现。该结论限定当前实际依赖，不能外推所有第三方扩展。

**本地 SDK 检查确认**：对commons执行javap得到 TokenCountEstimator 的estimate(String)、estimate(MediaContent)、estimate(Iterable<MediaContent>)；JTokkitTokenCountEstimator有默认和EncodingType构造器。javap -c确认默认CL100K_BASE，字符串调用Encoding.countTokens。它可用于估算，不能证明Qwen兼容。

**设计推断**：推荐独立小型HTTP适配器，使用现有Web/Jackson能力，无须增加Spring AI Alibaba或DashScope SDK。强行套ChatModel/EmbeddingModel会扭曲query/documents及分数协议；额外集成还需验证Boot4/AI2兼容性，不值得本阶段承担。当前JAR检查没有运行Maven依赖树，版本解析历史证据见模型报告；未检查未安装扩展的API。

## 4. 最小接口与映射协议（设计推断）

仅设计 infrastructure/ai 的边界，不建立M2检索链路：

```java
interface Reranker {
    List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topN);
}
record RerankCandidate(String id, String text) {}
record RerankResult(String id, double score) {}
```

- query必须非空白；topN必须正数；列表及元素不可null，ID和text非空白。重复ID拒绝，不悄悄去重；不同ID允许相同正文。输入错误在调用前失败。
- 合法空列表返回空且不联网。非空列表N最多500，预先验证长度预算；超限拒绝，不静默截断或分批后混比分数。
- 复制不可变请求快照，以发送documents的下标建立index→候选映射。ID不发送给供应商，不根据正文匹配、不使用响应数组位置关联。
- 实际发送k=min(topN,N)，显式return_documents=false。响应必须恰好k条；k<N时未返回的N-k个候选合法，k=N时必须覆盖全部。少于k、多于k、缺失results均失败，不能补齐或静默降级。
- index必须JSON整数、0≤index<N且唯一；禁止字符串/小数强制转换。score必须数字、有限且在[0,1]；null、NaN、Infinity及缺失拒绝。任何一项异常整次失败。
- 校验全部响应后，按score降序、同分按原始index升序输出，只从快照取ID；不依赖供应商数组天然顺序。供应商正文不作为权威内容，也不引入新候选。
- 示例：输入[A,B,C]，响应[(2,.7),(0,.9)]，topN=2，输出[A .9,C .7]；未返回B合法；返回index=3或两次index=0非法。

## 5. Tokenizer 可选方案

**官方文档确认**：[Qwen概念文档](https://qwen.readthedocs.io/en/latest/getting_started/concepts.html)说明BPE、控制Token、Chat模板；[官方Transformers用法](https://qwen.readthedocs.io/en/latest/inference/transformers.html)提供AutoTokenizer方向。开源模型分词器不自动等同闭源qwen-plus的当前版本。

**尚未验证**：本次没有找到可证明匹配当前qwen-plus别名及服务端模板的官方Java精确实现，也没有确认当前DashScope模型专用计数API。[OpenSearch Token计算API](https://help.aliyun.com/zh/open-search/search-platform/developer-reference/token-calculation)属于另一产品，不能当成当前DashScope免费计数接口。

| 方案 | 准确性 | 复杂度/Java兼容 | 成本与网络 | 混合技术资料 |
|---|---|---|---|---|
| 供应商专用Tokenizer | 模型、词表、模板完全一致才可称精确；否则模型专用近似 | 需锁版本/词表，Python路线增加集成；Java官方支持未确认 | 本地运行无逐次网络，维护成本较高 | 有潜力，仍需代码/中文/Emoji样本验证 |
| 本地通用估算加余量 | 启发式，非精确 | Java最简单；已有JTokkit可选但属OpenAI编码代理 | 无调用费用和网络依赖 | 英文平均字符比例对中文/代码误差大 |
| 模型计数接口 | 若官方保证相同模板才可称精确 | HTTP容易，当前接口适用性未确认 | 增加网络/延迟；价格未确认 | 取决于官方覆盖 |

**设计推断**：OpenAI编码与Qwen不可视为相同，即使都用BPE；词表、合并规则、特殊Token和封装可能不同。仅文本精确也不等于整条Chat请求精确。系统提示、问题、证据标签、分隔符、JSON输出指令及角色/消息模板均有输入开销；输出应另留额度，实际usage才是该次请求的观测值。

## 6. 推荐P0预算策略（设计推断，未校准）

推荐先用本地通用启发式加可配置余量：以完整拼接内容的Unicode码点数作为保守初始估值，额外预留消息封装固定开销和20%安全余量；这些只是待评审初值，不是严格上界或供应商保证。长代码、罕见字符可能仍低估，过度估算则减少证据、损害召回。

后续M3按 `估算输入 + 封装预留 + 安全余量 + 输出额度 ≤ 已确认模型窗口` 选择证据；系统提示和问题本身超预算则拒绝，不删除规则凑预算。不沿用本次Chat冒烟128输出作为业务预算；窗口/输出上限需按锁定模型与地域确认。M0-06B-2最多实现独立估算器，不实现ContextBuilder。

后续获授权的真实调用记录模型版本、估算器版本、估算值、usage.input/output（或prompt/completion）和长度统计，不保存原文；覆盖中文、英文、Markdown、代码、Emoji和长标识符，观察实际/估算比及最大低估，调整系数与固定开销，保留独立样本复核。模型别名变化需重校准；没有usage不能冒充校准通过。

## 7. 错误分类与安全边界

**本地代码检查确认**：ModelFailures已有AUTHENTICATION/TIMEOUT/RATE_LIMIT/SERVER/INVALID_RESPONSE/CONFIGURATION/REQUEST_REJECTED/CONNECTION/OTHER；HTTP状态目前仅识别OpenAIServiceException，连接失败仅顶层OpenAIIoException，超时检查cause链。safe(stage,...)白名单没有RERANK；未知阶段变UNKNOWN。InvalidResponseException构造器私有，现有checks没有Rerank入口。

| 场景 | 推荐分类 | 最小新增处理（设计推断） |
|---|---|---|
| 401/403 | AUTHENTICATION | 显式将HTTP状态映射为已有Kind |
| 429 | RATE_LIMIT | 同上；不自动重试 |
| 其他4xx | REQUEST_REJECTED | 同上，不把供应商400冒充用户参数错误 |
| 5xx | SERVER | 同上 |
| DNS/拒绝连接/连接中断 | CONNECTION | 识别所选HTTP客户端的I/O异常及包装cause |
| 连接/读取/请求超时 | TIMEOUT | 超时优先于泛化I/O分类，覆盖实际客户端异常 |
| 200无效JSON/错误字段/错误业务体 | INVALID_RESPONSE | 包装为安全协议异常，不回显解析器消息 |
| 越界/重复index、非法score、数量异常 | INVALID_RESPONSE | 新增Rerank校验入口，复用安全异常类型 |

**设计推断**：给ModelFailures增加最小状态映射及RERANK阶段，安全包装需保留Kind而不是先丢失分类再检查。输入校验与供应商协议失败分开；不增加重试/熔断/回退，失败不得转为空结果冒充证据不足。ApiExceptionHandler已有固定消息及不记录原异常栈的边界可复用；当前通用500不能宣称已有面向业务的Rerank错误协议。

只记录固定阶段、分类、HTTP状态、耗时、候选数量及本地requestId。供应商request_id如记录需长度/字符白名单；不记录密钥、请求头、query/documents、原始body、异常message或cause。限制响应体大小、配置HTTPS固定可信endpoint，禁止重定向携带密钥；HTTP本地Fake仅供离线测试。配置错误不输出配置值。

## 8. M0-06B-2建议文件与最小测试（均未实施）

包前缀为 `com.lnjecit.knowledge.assistant`：infrastructure/ai下新增Reranker、RerankCandidate、RerankResult、DashScopeReranker；按需扩展ModelResponseChecks、ModelFailures；独立TokenEstimator返回明确估算标记。配置层增加独立Rerank开关/model/endpoint/超时及启动校验，不让未启用功能要求密钥。具体类拆分由人工评审，不增加供应商SDK依赖。

最小离线测试：Fake HTTP校验路径/鉴权/嵌套body/top_n/关闭原文；逆序响应及同分排序、稳定ID映射；空输入零请求、重复ID、非法topN、候选超限；k<N与k=N数量语义；缺失/负数/越界/重复/非整数index；缺失/非数字/非有限/越界score；null/缺失results、非法JSON、200错误体；401/403/429/400/503、连接失败和超时；每次只有一次请求；所有失败无原文、模拟密钥或cause泄漏。估算器只测试确定性、空文本、Unicode/中文/代码和估算标记，不断言等于Qwen真实Token。

在线冒烟必须另行授权、显式启用、无重试，记录模型/endpoint地域、脱敏映射、usage、耗时及费用边界；未执行不得记成功。本次不修改模型能力报告，不实现M2/M3业务逻辑。

## 9. 待人工确认

1. 是否采用qwen3-rerank及原生HTTP协议；确认账户、地域、业务空间Endpoint与权限。
2. 是否接受严格k条及异常整次失败、同分按输入顺序、无静默降级。
3. 是否接受码点启发式与20%初始余量；封装预留、窗口、输出预算数值待模型锁定和后续实测。
4. 是否需继续调查专用Tokenizer/计数服务；当前不具备qwen-plus精确计数依据。

## 10. 证据与验证边界

官方链接见各节，访问日期均为调查日期；在线网页可能更新，不是冻结SDK合同。本地证据命令为针对上述六个2.0.2 JAR的jar tf及commons的javap/javap -c；没有根据1.1.x缓存推断2.0.2。仓库历史实验与本次只读检查明确分开。

本次仅修改AGENTS.md过期句和本文；执行全文审阅、完整diff审阅、git diff --check、git status --short。未运行Maven编译/测试/格式化、真实模型或Docker，因为本次纯文档调查。完成提交后停止，等待架构评审，不进入M0-06B-2。
