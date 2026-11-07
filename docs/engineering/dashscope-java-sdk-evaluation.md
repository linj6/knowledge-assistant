# DashScope Java SDK 可行性调查

调查日期：2026-11-07。结论：本轮建议 A，保留当前 JDK HttpClient 实现。官方 Java SDK 已有可用于该接口的能力，不能以“不支持 qwen3-rerank”为由排除；但直接采用 TextReRank 不能保留当前全部严格协议和安全边界，补齐这些能力会抵消本项目单一 rerank 接口的代码收益。后续架构评审可重新决定，本轮不迁移。

## 版本、来源和验证边界

固定调查坐标为 `com.alibaba:dashscope-sdk-java:2.23.1`，选择可从 Central 同时取得正式二进制、源码和 POM 的非 SNAPSHOT 版本作为可复核基线，不宣称它是最新版或最安全版本。

- [官方仓库及安装说明](https://github.com/dashscope/dashscope-sdk-java)：使用上述 Maven 坐标；README 仅作为安装证据。
- [Central 正式版本目录](https://repo.maven.apache.org/maven2/com/alibaba/dashscope-sdk-java/2.23.1/)：本地实际下载 `dashscope-sdk-java-2.23.1.jar`、`-sources.jar` 和 `.pom`。其他正式版本见 [Central 版本页](https://central.sonatype.com/artifact/com.alibaba/dashscope-sdk-java/versions)，本报告不将网页显示的最新版本当作已验证版本。
- [官方 rerank 接口说明](https://help.aliyun.com/en/model-studio/text-rerank-api)：明确列出 qwen3-rerank 和 DashScope HTTP 接口。该页面的服务能力不等同于 Java 示例或 Java 运行验证，未借用 Python SDK 作证。
- 二进制 SHA-256：`9CAD1D1F8FECD89FF70E63E4990F969AA29FED37CB19C0038DA61C419CB0CED6`。
- 源码 JAR SHA-256：`D90DC5BA566B56E06FCD1B4151E1ABE9370E456A519894C7330D2C772FA9CDAF`。

本地解包源码，并对实际二进制执行 javap，确认 TextReRank 构造器、call、usage 和结果 getter 存在。未运行 SDK 请求、未运行依赖融合后的项目编译或集成测试；因此“源码确认”不等于线上模型成功或完整运行兼容。下载物位于系统临时目录，不加入仓库。下述源码路径均相对于该固定版本 sources.jar 中的 `com/alibaba/dashscope/`，可直接从上述 Central 目录复核，不能以主分支源码替代。

## API 与功能对照

| 能力 | 证据、具体 API | 结论与边界 |
| --- | --- | --- |
| 模型 | `rerank/TextReRank.java`、`rerank/TextReRankParam.java`：继承的 `model(String)` builder 参数写入 HTTP body，validate 不限制模型枚举 | 官方服务文档支持 qwen3-rerank；源码允许 `.model("qwen3-rerank")`。Models 只有 GTE_RERANK_V2 常量不代表禁止其他模型；真实服务调用未确认 |
| 输入 | `TextReRankParam.builder()` 的 `query(String)`、`documents(Collection<? extends String>)`、`topN(Integer)`、`returnDocuments(Boolean)` | 源码确认序列化为 input.query/documents、parameters.top_n/return_documents；validate 只检查 query 和非空 documents，不接管项目完整校验 |
| 同步调用 | `TextReRankResult TextReRank.call(TextReRankParam)`，声明 ApiException、NoApiKeyException、InputRequiredException | 源码及本地 javap 确认；底层 SynchronizeHalfDuplexApi.call → OkHttpHttpClient.send → Call.execute |
| index、分数 | `TextReRankOutput.Result.getIndex(): Integer`、`getRelevanceScore(): Double` | 源码及 javap 确认，原始输入 index 可用于业务 ID 映射；不是响应位置 |
| usage | `TextReRankResult.getUsage(): TextReRankUsage`；`TextReRankUsage.getTotalTokens(): Integer` | 源码映射 usage.total_tokens，usage 可为空；后者已 javap 确认。不能凭类型存在宣称每次服务都返回 usage |
| 超时 | `TextReRank(String protocol, String baseUrl, ConnectionOptions)`；`ConnectionOptions.builder().connectTimeout(Duration).readTimeout(Duration).writeTimeout(Duration)` | 源码及构造器 javap 确认；不是总调用时限。ConnectionOptions getter 默认连接120秒、读300秒、写60秒，但工厂仅应用显式设置的超时，不能将 getter 默认值直接当作最终客户端配置 |
| Key | `base/HalfDuplexParamBase.java` 的 apiKey 字段及继承 builder `apiKey(String)` | 可逐请求显式提供；不要依赖全局共享 Key，不输出 param.toString |
| Endpoint | `TextReRank(String protocol, String baseUrl)`；`utils/Constants.java` 的 baseHttpApiUrl / DASHSCOPE_HTTP_BASE_URL | 使用 protocol="http" 选择 HTTP 协议实现，baseUrl 可为 HTTPS 地域基址；当前配置是完整请求 URL，不能原样当基址。由 SDK 拼接服务路径；地域、账户和模型可用性仍须匹配，未线上验证 |
| 重试 | `protocol/okhttp/OkHttpClientFactory.java` 创建 OkHttpClient.Builder，没有配置 retryOnConnectionFailure | rerank 同步链路未见应用级 retryWhen；不能据此说“绝无自动重试”。底层 OkHttp 默认恢复及特定状态跟进仍适用；TextReRank/ConnectionOptions 无关闭开关，useDefaultClient=false 对 HTTP 分支不提供自定义客户端注入 |
| HTTP 错误 | `OkHttpHttpClient.send(HalfDuplexRequest)` 对非成功响应 parseFailed 后抛 ApiException；`exception/ApiException.java` 的 `getStatus()` | 401/403/429/5xx 均沿此通道，需适配 statusCode 到现有分类，不是独立 Java 异常类；send 再包装异常但保留 ApiException.status |
| 网络异常 | send 捕获 Throwable 包装 `ApiException(Throwable)`，非 ApiException 时 statusCode=-1，code=NETWORK_ERROR | 需遍历 cause 区分 timeout/connection/other；不能把所有 ApiException 当服务端错误 |
| 池及释放 | OkHttpClientFactory.getOkHttpClient() 单例；getNewOkHttpClient(ConnectionOptions) 使用单例.newBuilder() | 派生客户端共享底层池和 dispatcher。响应 body.string() 消耗并关闭 body；不能据此声称所有异常路径均已运行验证。TextReRank 不实现 AutoCloseable，也无 close；底层 close(int,String) 只取消 SSE，不关闭连接池/线程池 |

## 严格校验与安全差异

已阅读当前 DashScopeReranker、Reranker、RerankCandidate、RerankResult、RerankProperties、ModelFailures、RerankTests、RerankOnlineIT。

SDK 可接管请求 DTO、JSON 编码、Authorization 与同步 HTTP、基本结果 DTO 和 usage 解码。项目仍须保留稳定业务 ID、输入快照、唯一 ID、非空正文、最多500项、topN截断、空集合不请求，以及原始 index 到 ID 的映射；供应商无需收到 ID。返回数量、index 范围与唯一性、分数有限且在[0,1]、整次失败和按分数降序/index升序的确定排序均不能交给 SDK。

当前实现还拒绝字符串数字和浮点 index、code 字段、非法 JSON，限制响应262144字节，禁止重定向，并用可取消 future 实现覆盖响应体读取的总时限。SDK 将结果经 Gson 转成 Integer/Double 后，原始 JSON 类型信息可能已丢失；对 DTO 再检查范围无法恢复这些信息。故仅替换成 TextReRank.call 后遍历 getter，不能承诺保持 RerankTests 的全部语义。工厂也没有配置禁止重定向、总 callTimeout 或响应体大小上限。

安全边界尤其需要注意：`TextReRankResult.fromDashScopeResult` 在没有 output 时执行 `log.error("Result no output: {}", dashScopeResult)`，可能输出供应商原始内容；ApiException.getMessage/toString 包含 status JSON，保留 cause 也可能泄漏正文。外层捕获后脱敏无法撤销 SDK 已写出的日志。必须约束该类日志、网络日志和 DTO 日志，并验证失败路径；向上只传播无原始 message/cause 的 SafeModelException。

ModelFailures 当前认识 OpenAI 异常而不认识 DashScope ApiException。迁移必须新增适配：401/403→AUTHENTICATION、429→RATE_LIMIT、5xx→SERVER、其他4xx→REQUEST_REJECTED；协议缺陷→INVALID_RESPONSE；网络/超时保留现有分类。不能直接把 SDK 异常传给现有 classify 后宣称分类兼容。

## 依赖兼容性

固定版本 POM 编译目标 Java 8，Java 21 可读取该字节码；这只是静态兼容依据，未完成 Java21 运行烟测。SDK 无 Spring Boot/Spring AI 绑定，适配在现有 Reranker 后即可，不能因此推断 Boot4.1.2/AI2.0.2 已通过集成。

POM 直接引入 RxJava2 2.2.21、Gson2.8.9、SLF4J2.0.7、Kotlin stdlib-jdk8 1.8.21、Okio3.6.0、OkHttp及 SSE/logging-interceptor4.12.0、jsonschema-generator4.31.1；Lombok为provided，JUnit/mockwebserver等为test，不应算作应用直接运行依赖。对单一同步 rerank 用途，新增依赖明显多于 JDK HttpClient 路径。

SDK rerank 使用 Gson，未直接依赖 Jackson3 API，不会直接替换项目 tools.jackson 的 JsonMapper。jsonschema-generator 还可能带入 Jackson2生态的传递依赖；Jackson2 与3包名不同可共存，但不能把“包名不同”当作完全无冲突。Boot BOM 可能管理 Gson、OkHttp、Kotlin、SLF4J、Jackson等实际解析版本，SDK声明版本不等于项目最终版本。本轮未修改 POM、未在临时工程解析完整 Boot/AI 合并依赖树，因此最终版本收敛、二进制链接、漏洞和运行兼容均尚未确认，不作已通过结论。不擅自升级任何依赖。

## A/B 决策与后续条件

| 方案 | 收益 | 成本 |
| --- | --- | --- |
| A：当前 JDK HTTP | 保持已明确的协议类型、响应上限、总时限、无重定向及安全错误边界；无需新依赖 | 继续维护小范围请求编码和响应解析；当前业务结果不暴露 usage |
| B：官方 SDK | 删除一部分通信和 DTO 代码，得到现成 usage 字段与官方接口演进 | 仍保留全部业务校验；补齐原始JSON校验、池生命周期、重试关闭、日志和传输约束需要额外适配，且引入依赖收敛验证 |

明确建议当前选 A，理由是这些可定位的行为差异，而非当前代码已完成。SDK 支持模型和基本接口，但尚不是本项目严格边界下的低成本替换。usage 可以由 SDK 获取，也可在原生 HTTP 解析中增加；两者都需要另行授权设计，不能为了 usage 擅自改变 Reranker 返回类型。如果未来所需模型或接口不受 SDK 支持，应继续原生 HTTP，而不是绕用 Python 能力或伪装成其他模型。

若评审选择 B，最小范围应限定为 DashScopeReranker 内部适配、必要 POM 依赖及 ModelFailures 的供应商分类；保留 Reranker/Candidate/Result 和配置校验，设计完整 URL 到 SDK 基址的明确转换。先证明无需私有反射即可控制重试、重定向、总时限、响应大小和原始类型校验；否则终止迁移或重新评审 SDK低层API方案，不能默认降低严格性。usage 仅作可空内部指标，公开接口演进单独决策。

回归计划：复用 RerankTests 本机 Fake 覆盖所有合法映射、同分排序、非法数字类型/溢出/缺失/重复/数量、code字段、超限响应、401/403/429/400/503/302、拒绝连接和慢响应；增加逐字节慢流总时限、失败实际请求次数、重定向不携带Key、SDK缺output日志脱敏、资源共享与关闭测试。临时依赖工程验证最终 dependency tree 和 Java21 编译运行，再执行受影响测试。RerankOnlineIT 仍须显式授权，单次调用且不为 usage 重试；本轮不运行。

## 本轮检查

仅新增此文档。完成官方网页核对、正式JAR/POM/源码下载、源码审阅及本地javap检查；未执行真实模型请求、Docker、Maven编译、项目测试或全量测试。提交前审阅完整文档差异并执行 git diff --check。Architecture Spec 的 Boot3.x 与当前POM4.1.2已知差异不在本任务修改范围，本报告以当前POM为依赖分析基线。提交后停止，等待人工架构评审。
