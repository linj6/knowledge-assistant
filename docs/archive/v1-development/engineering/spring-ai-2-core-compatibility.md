# M0-02b — Spring AI 2.0.2 核心组件兼容性验证

日期：2026-11-06；分支：`experiment/spring-ai-2-upgrade`；起点：`f92577fe9ed66e8e498594a1019656ea918da9ef`，开始时工作区干净。
前阶段见 [最小升级实验](spring-ai-2-upgrade-experiment.md)。本报告不替换冻结架构或兼容性矩阵。

## 依赖与范围

新增两个 compile scope 普通库，均由现有 BOM 管理为 2.0.2：

- `org.springframework.ai:spring-ai-client-chat`：提供 ChatClient、Advisor，传递引入 spring-ai-model（ChatModel、EmbeddingModel、ChatMemory）。
- `org.springframework.ai:spring-ai-vector-store`：提供 VectorStore、SearchRequest，不带具体数据库实现。

未额外直接声明传递依赖；没有模型供应商 Starter、AI 自动配置、数据库或其他基础设施。生产类、application.yml 不变。依赖放在编译作用域，让独立 JAR 启动覆盖这些库在生产 classpath 中的加载兼容性。

## 测试设计

新增 `SpringAiCoreCompatibilityTests`，6 个测试分别验证：

| 测试 | 覆盖范围 | 不能推导的结论 |
|---|---|---|
| chatClientCallsMockModel | 真实 ChatClient 调用 Mock ChatModel，检查 Prompt 与回答 | 供应商协议和真实模型效果 |
| embeddingDefaultMethodCallsMockModel | 真实 embed(String) 默认方法构造 EmbeddingRequest，Mock call 返回固定向量 | 真实向量维度、语义质量或供应商批量协议 |
| chatMemorySavesReadsAndClearsMessages | 真实 MessageWindowChatMemory 的内存保存、读取、清理与会话隔离 | 持久化、并发和复杂窗口裁剪 |
| memoryAdvisorPassesHistoryToModelAndSavesResponse | 真实 MessageChatMemoryAdvisor 同步链注入历史并保存本轮问答，模型为 Mock | 流式调用、工具调用、重试及多 Advisor 顺序 |
| vectorStoreAcceptsSearchRequestAndDocumentTypes | VectorStore / SearchRequest / Document 编译加载及 Mock 查询 | 实际存储、相似度计算和向量检索 |
| coreBeansCanBeManuallyAssembled | Spring 容器手动注册 ChatModel、ChatMemory、Advisor、ChatClient 并模拟调用 | 供应商 Starter 自动装配；EmbeddingModel / VectorStore 的 Bean 装配 |

原有 Boot 上下文测试保留。所有测试使用固定虚构文本，不请求网络、不读取密钥。

## API 依据与 1.1.8 差异

核对了标示 2.0.2 的官方 [ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、[Advisor](https://docs.spring.io/spring-ai/reference/api/advisors.html)、[ChatMemory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html) 文档。
同时从 Maven Central 下载并解压 `spring-ai-model`、`spring-ai-client-chat`、`spring-ai-vector-store` 的 **1.1.8 与 2.0.2 sources.jar**，对具体文件执行 `git diff --no-index`，没有用印象推断迁移变化。

源码地址模式：`https://repo.maven.apache.org/maven2/org/springframework/ai/{artifact}/{version}/{artifact}-{version}-sources.jar`。
本机解压位置为 TEMP/ka-m002b-sources；可按该模式重做比较。

- `chat/memory/ChatMemory.java`：两个版本内容一致；仍有 `add(conversationId, Message/List<Message>)`、`get(conversationId)`、`clear(conversationId)` 和 `CONVERSATION_ID`。本次基本存取无需迁移。
- `chat/client/advisor/api/CallAdvisor.java`：两个版本内容一致；同步调用仍为 `adviseCall(ChatClientRequest, CallAdvisorChain)`。
- `BaseChatMemoryAdvisor`：2.0.2 增加继承 `MemoryAdvisor`，上下文值增加 JSpecify `@Nullable` 标注；不能因此宣称所有 Advisor API 都保持不变。
- `MessageChatMemoryAdvisor`：2.0.2 在加入历史前检查 Prompt 是否已包含相同连续历史片段，避免重复；后续组合 Advisor / 工具循环需要检查实际 Prompt，不能依赖重复拼接的旧行为。
- `MessageWindowChatMemory`：2.0.2 淘汰消息时向前移动裁剪点到 USER 消息，避免保留缺失用户起点的半轮；窗口保留条数可能少于上限。此行为来自源码比较，本次没有完整验证窗口边界场景。
- `chat/model/ChatModel.java`：2.0.2 新增 `getOptions()`；`getDefaultOptions()` 标记 `@Deprecated(forRemoval = true)` 并转调新方法。后续自定义适配器应实现新方法，不能仅覆盖旧方法。
- `ChatOptions` 在当前版本提供 `mutate()`；实际 ChatClient 会读取 `getOptions().mutate()`。因此 Mock 也必须提供非 null 的有效选项。

以上是指定文件与指定版本的比较，不代表整个 1.x → 2.x 无破坏性变化。

## 执行记录

环境沿用 Java 21.0.10、Maven 3.9.16、Boot 4.1.2。

- 首次 `mvn dependency:resolve` 使用本机阿里云镜像，解析 micrometer-core:1.17.2 POM 时遇到 HTTP 502。随后用 TEMP/ka-m002b-settings.xml 临时设置直连 Central，保留原本地仓库位置；未修改用户全局 settings 或项目仓库配置，未覆盖依赖版本。Central 解析成功。
- 首轮 clean verify 编译通过，7 个测试中 Failures=0、Errors=3：ChatModel 的普通 Mock 返回 null 选项，触发 `DefaultChatClientUtils.toChatClientRequest` 的空指针。依据 2.0.2 源码修正测试替身 `getOptions()` 返回值；没有修改生产代码或绕过调用链。
- 最终 clean verify：BUILD SUCCESS，1 分 29 秒；Tests run=7、Failures=0、Errors=0、Skipped=0（原上下文测试 1 个，核心组件测试 6 个）。Java 编译 release 仍为 21。
- 最终 dependency:tree / help:effective-pom：BUILD SUCCESS。实际 Spring AI 组件 client-chat、model、commons、template-st、vector-store 全部为 2.0.2；Framework 模块统一 7.0.10；effective POM 确认 Boot 4.1.2、AI 2.0.2、release 21。verbose tree 没有 conflict 项，没有额外供应商或数据库 Starter。未发现当前依赖图冲突，不推导未来依赖也无冲突。
- 包含新增库的打包 JAR 独立启动成功，3.463 秒完成上下文加载，Tomcat 随机监听 55514；根路径 HTTP 404。PID 8080 已停止并等待退出。
- git diff --check 通过；仅 POM、测试和实验报告变更。

本机证据：TEMP/ka-m002b-resolve.log（镜像失败）、ka-m002b-resolve-central.log、ka-m002b-verify-first.log（测试替身失败）、ka-m002b-verify.log（最终成功）、ka-m002b-management.log；target 下 surefire-reports、m002b-dependency-tree.txt、m002b-effective-pom.xml、m002b-startup.log。临时文件不提交，可能被后续 clean 清理；本报告保存主要结果和复现方式。

详细命令（PowerShell，临时 settings 只指定 localRepository，没有镜像）：

```powershell
mvn -B -s "$env:TEMP/ka-m002b-settings.xml" clean verify
mvn -B -s "$env:TEMP/ka-m002b-settings.xml" dependency:tree -Dverbose "-DoutputFile=target/m002b-dependency-tree.txt" help:effective-pom "-Doutput=target/m002b-effective-pom.xml"
java -jar target/knowledge-assistant-0.0.1-SNAPSHOT.jar --server.port=0
git diff --check
```

## 结论与后续

核心 API 的编译、离线调用和上述手动装配可以作为升级决策证据；Mock 结果不能作为真实模型连通性证据。
建议评审后采用 Boot 4.1.2 + AI 2.0.2 作为应用核心开发基线，完整基础设施兼容性仍需后续实测。
尚未验证供应商 Chat / Embedding / Rerank 调用、VectorStore 具体实现、持久化 ChatMemory、流式及工具调用、复杂 Advisor 链、自动配置和真实模型效果。
Memory 测试仅为库兼容性实验，不改变 V1 单轮问答范围。未推进 M0-03，未修改冻结文档。
