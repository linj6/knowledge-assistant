# M0-07A 测试基础设施现状调查与实施设计

调查日期：2026-11-07；分支：feature/m0-foundation。本文是供人工评审的方案，不是 M0-07 验收报告。本阶段只新增本文，未执行 Maven、Docker 或真实模型请求。

## 1. 依据与当前依赖

已审阅 AGENTS.md、POM、实施计划 M0-07/M0 验收、任务模板、架构、Compose、环境示例、src/test、模型报告、兼容矩阵和 M0-03 基础设施记录。架构中的 Boot 3 与当前 POM 4.1.2 冲突沿用既有记录，不修改冻结文档。兼容矩阵早期的 ES/MinIO 阻断应结合 M0-03d 最新结论阅读：本地联合功能通过，隔离离线恢复未通过。

当前 Java 21、Boot 4.1.2、AI 2.0.2。POM 通过 spring-boot-starter-webmvc-test 引入测试能力，没有直接声明 Testcontainers，没有自定义 Surefire、没有启用 Failsafe，也没有测试 profile。Spotless check 绑定 validate。

本机 Maven 仓库 D:/develop/repository/maven 中 Boot 4.1.2 parent/dependencies POM 静态核对：Surefire/Failsafe 管理版本均 3.5.6，JUnit BOM 6.0.3，Testcontainers BOM 2.0.5。测试使用 org.junit.jupiter API（通常称 JUnit 5 编程模型），实际管理版本已经是 6，不能声称仍运行 Jupiter 5.12.2。管理版本不等于已引入或已经运行：本阶段未重新解析依赖树、effective POM 或测试 provider；07B 必须保留这些证据，不独立覆盖版本，不添加 Vintage。

## 2. 当前测试清单

| 测试类 | 目标、外部依赖 | Spring Context | 默认 Surefire |
|---|---|---|---|
| ApiBaselineTests | MockMvc standalone；请求 ID、异常和参数协议；无外部服务 | 否 | 是 |
| BusinessExceptionTests | standalone MockMvc、MDC、输出捕获、安全错误响应 | 否 | 是 |
| ConfigurationTests | 模型属性绑定、校验与安全日志，无网络 | ApplicationContextRunner 小上下文 | 是 |
| KnowledgeAssistantApplicationTests | 应用启动；offline profile 排除 JDBC/Flyway 自动配置 | 完整上下文 | 是 |
| SpringAiCoreCompatibilityTests | AI 核心 API 手动装配/Mock 兼容性，无供应商 | 否 | 是 |
| ModelCapabilityTests | Token 估算、响应校验、Chat/Embedding 本地 HTTP 协议桩与错误；回环随机端口 | 否 | 是 |
| RerankTests | 重排协议、映射、校验、安全异常、超时与授权门禁；本地 HTTP 桩/Mock | 否 | 是 |
| DatabaseIT | 真实 JDBC/Flyway、扩展、余弦距离、失败迁移阻止启动 | 手工 SpringApplication 非 Web 上下文 | 否 |
| ModelOnlineIT | 普通 Chat、3 次 JSON Chat、单条与批量 Embedding；真实供应商 | 否，手工装配 | 否 |
| RerankOnlineIT | 单次真实 Rerank 与结果校验 | 否，手工装配 | 否 |

src/test/resources 只有 application-offline.yml，排除了数据源和 Flyway，并未强制关闭环境中启用的模型；07B 应为离线及容器上下文显式覆盖 model/rerank enabled=false，避免开发环境污染。其余离线测试没有真实模型请求，固定 localhost:1 等值用于配置/错误场景，HTTP 桩实际绑定回环端口 0；无需开发服务固定端口。

DatabaseIT 当前读取应用 KA_DB_*，默认 localhost:5432/ka，需要密码，可能访问开发库；V1 扩展及历史表持久共享。两个方法不声明顺序，各自启动上下文，正常迁移会共同影响同一数据库；向量临时表限 JDBC 会话，失败迁移使用 UUID schema，finally 删除 schema/临时文件。不能据此保证首次迁移被测到，已有开发库可能已迁移。

其他测试未发现显式测试顺序或跨类业务状态依赖；MDC、全局输出捕获和 Spring 测试上下文缓存属于共享机制，当前不宜引入并行执行。本地 HTTP 桩需保持 finally stop；07B 核对超时线程退出与资源关闭。ApiBaselineTests 内部调用其他测试方法是方法复用，不是 JUnit 顺序保证。

Surefire 默认匹配 Test*、*Test、*Tests、*TestCase，不匹配上述 *IT；但 -Dtest=DatabaseIT 或在线类可显式执行。当前 mvn verify 仍只执行默认单测，不会自动补跑 IT。ModelOnlineIT 仅以 ASSISTANT_MODEL_ENABLED=true 及模型配置作为门禁，没有 Rerank 那样独立授权变量；RerankOnlineIT 同时要求 ASSISTANT_RERANK_ONLINE_AUTHORIZED=true 和 ASSISTANT_RERANK_ENABLED=true，缺配置失败而非跳过。在线入口绝不能仅靠 IT 后缀隔离：Failsafe 默认会匹配它们。

## 3. Maven 分层与推荐

| 选择 | 行为 | 效率与 CI | 遗漏风险/计划关系 |
|---|---|---|---|
| A（推荐） | mvn test 离线；mvn verify 单测+Docker IT | 日常 test 快；CI verify 必须配置 Docker 和镜像 | 默认验收不漏 IT，符合当前计划 |
| B | mvn verify 仅离线；mvn -Pdocker-it verify 才含容器 | 无 Docker 环境方便，CI 需强制 profile | 易忘 profile；改变冻结计划，须先人工确认 |

推荐 A，无需改变实施计划。三个入口拟定为：`mvn test`；`mvn verify`（显式运行完整 Docker 验收）；获单独授权后 `mvn -Ponline-model test`。离线指不访问外部服务，首次 Maven 依赖下载仍需预置缓存；严格断网时预置后使用 -o，不承诺空缓存可构建。

07B 为 Surefire 显式配置离线命名范围、排除 *IT；为默认 Failsafe 绑定 integration-test 和 verify，容器类使用明确白名单 DatabaseIT、ElasticsearchIT、MinioIT，设置 failIfNoTests=true；同时永久排除 ModelOnlineIT/RerankOnlineIT。拒绝宽泛 **/*IT 后只靠环境变量挡在线请求。

online-model profile 仅在 Surefire 显式包含两个在线类，替换离线选择，不绑定容器执行；命令只到 test 阶段。保留 Rerank 门禁，为 Chat/Embedding 增加对应独立授权变量；缺授权明确失败。profile 选择不替代用户每次在线请求授权。避免 online profile 与 verify 混用，README 明确入口。07B 用不含凭据的选择/门禁测试证明隔离，不自动执行真实请求。

## 4. Testcontainers 最小方案

优先 Boot BOM 管理的 Testcontainers 2.0.5：testcontainers-junit-jupiter、testcontainers-postgresql、testcontainers-elasticsearch；MinIO 先用 GenericContainer，不为健康/S3探针额外引入通用框架。2.x artifact/package 必须按本地 BOM 和官方 API 核对，不照抄 1.x 示例。可用 JDK HTTP 客户端及容器内已有 mc 做最小探针，暂不为 M0 添加生产 ES/S3 SDK。

| 服务 | 镜像与复用 | 等待及功能断言 |
|---|---|---|
| PG | Compose 的 pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87；PostgreSQLContainer 通过 asCompatibleSubstituteFor(postgres) 声明兼容 | JDBC 就绪；server_version 为17.6、vector extversion=0.8.0；不能用普通 postgres:17 代替 |
| ES | knowledge-assistant-elasticsearch:8.18.0-ik8.18.0；声明与官方 ES 镜像兼容 | single-node、禁用安全、512m heap；HTTP cluster health yellow，再验证版本、analysis-ik、两种 analyzer 及临时 BM25 索引 |
| MinIO | knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local；GenericContainer，server /data --console-address :9001 | /minio/health/ready 返回200；版本、独立 bucket put/get 哈希一致、删除对象/bucket |

ES 不在测试启动中 build 或在线装 IK；现有 Dockerfile 在预构建阶段下载并校验固定 ZIP。ES 本地构建 manifest digest 会随 provenance 改变，交付时记录 image ID、构建输入与 tar 哈希，不能伪称官方 digest。MinIO M0-03d 固定 image ID 为 sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e；仓库外 tar 路径及 SHA256 见该报告。目标 daemon 缺镜像时先按单独运维流程校验归档再 load，测试不能自动回退 latest/第三方镜像。

三个服务优先使用本地已锁定镜像，设置 never-pull 策略；缺失立即报镜像准备错误。离线还必须预置当前 Testcontainers 需要的 Ryuk/辅助镜像和 Maven 依赖，07B 从实际解析版本记录镜像名称/摘要，不关闭 Ryuk 掩盖缺失。MinIO 异机/空白 daemon 恢复未验证，是 CI 可复制性阻断项，不等同于本地不可运行。

每个测试类持有自己的静态 @Container，JUnit 扩展管理 start/stop；不启用 reuse、不绑定主机固定端口、不挂开发命名卷、不调用 Compose。getHost/getMappedPort 或 PG getJdbcUrl 注入；DatabaseIT 的每次手工 start（含失败迁移）显式传 datasource 参数，优先级高于 KA_DB_*，保证任何路径都不回落开发库。无需为此引入 ServiceConnection 或管理器；将来改为标准 Spring 测试才考虑 DynamicPropertySource/Boot service connection。

启动等待初值 PG/MinIO 120秒、ES 180秒，失败带服务和脱敏诊断；启动失败也要清理部分资源。正常停止由扩展负责，异常退出由 Ryuk 回收。测试 bucket/index/schema 唯一命名并 finally 清理，容器销毁作为兜底。不使用 disabledWithoutDocker=true、Assumptions 或捕获异常转 skipped；daemon 不可用、握手失败、镜像缺失、超时、功能断言失败均使 verify 非零。仅成功启动并完成断言才算通过。

Flyway 使用全新容器空库：启动前确认无历史表，首次应用启动执行不可变 V1，断言历史表仅一条成功 V1及扩展；再次 migrate 执行0项，再次启动仍0新增。保留 vector(3) 数据距离0/1断言与随机 schema 失败迁移阻断启动。不要将3维探针当成1024维模型全链路验证，不改 V1、不依赖方法顺序。

## 5. 配置基线（建议值均未实测）

现有已实现：KA_DB_* 数据源、Flyway enabled=true/baseline-on-migrate=false；模型与 Rerank 默认关闭、各30秒超时，以及 ModelProperties/TokenEstimator 参数校验。Compose 是开发固定端口与持久卷；.env.example 不自动注入 Spring。下表六类业务配置当前均未实现，M0只记录决策候选，不添加未使用属性。

| 参数 | 建议初值及约束来源 | 引入与验证阶段 |
|---|---|---|
| 文件大小 | 单文件10 MiB，请求总量12 MiB容纳 multipart；架构要求限制类型、编码、大小；数值为工程假设 | M1 上传时同步网关/Servlet/业务校验；测0、恰好上限、超1字节、多文件和解码内存 |
| 单KB文档 | 最多1000个非删除文档，发布集合也不得超过1000；并发原子校验，删除/处理中计数语义须确定 | M1 定义计数、M2 验证发布并发与枚举耗时 |
| ES terms | 应用安全边界2000个唯一版本ID，且KB上限1000低于此值；还须低于目标索引实际 index.max_terms_count，启动/验收读取核实 | M2 查询边界测试；越界明确错误，禁止静默截断；不把常见默认65536作为实测 |
| Worker重试 | 最多3次重试（连首次最多4次）；延迟5/15/45秒，加±20%抖动，永久错误不重试 | M1 分类、计数持久化、崩溃恢复；避免与HTTP重试相乘 |
| Worker租约/超时 | 租约120秒、每30秒续租；任务总超时10分钟；单外部调用初始30秒沿用已有模型超时 | M1 测长任务、续租失败、双Worker、崩溃和 fencing；租约过期绝不等于旧Worker停止 |
| RAG Context | 证据预算4096估算tokens；另预留回答1024及系统/问题开销，总输入输出不超实际模型窗口 | M3 ContextBuilder引入；20%估算余量非可靠Tokenizer，需usage校准与长中文样本验证，不保证不会低估 |

以上数值没有产品容量或性能实测背书。架构要求的安全发布、幂等、领取代际隔离不能用租约数字替代；上下文预算不能在模型窗口和问题大小未知时机械相加。后续实现任务应确认并记录单位、合法范围、上限错误和启动校验来源。

## 6. 最小迁移清单与 M0-07B 验收

1. 人工审阅本文，确认 A 和未来配置候选；核对 Boot BOM/effective POM/依赖树，记录 Jupiter/provider、Testcontainers/docker-java 实际版本，验证 Docker 29握手，不沿用旧1.21.4风险结论代替实测。
2. 仅添加测试 scope 模块与 Failsafe 配置；保留依赖版本，建立离线/容器/在线明确选择，补齐 Chat 授权门禁与离线上下文显式关闭模型。
3. DatabaseIT 改为隔离容器连接，覆盖全部 start 路径、首次/重复/失败迁移和向量计算；无开发数据库、无固定主机端口。
4. 新增 ElasticsearchIT/MinioIT 最小真实功能探针；复用已验证镜像，不引入生产检索/上传业务。现有离线类保持职责，不改成完整 SpringBootTest。
5. README 记录镜像预置、离线依赖、三个命令入口和失败诊断；M0配置只记录候选与后续验证任务，不添加空属性。
6. 在单独授权的07B范围内运行离线 test、具备Docker且无模型密钥的 verify；记录各类数量、零失败/错误、必需容器测试零跳过，镜像版本与断言；模拟 Docker 不可用须非零，缺镜像须非零。检查即使环境有在线启用标记，默认入口也不选中在线类、不访问供应商。在线仅测门禁，真实冒烟需另行授权。

M0-07B 通过条件：上述真实PG/ES/MinIO测试完成，默认verify选择正确；首次与重复迁移证据清晰；镜像在目标CI可准备；工作区/diff/格式检查通过，README与报告准确。M0整体验收另须对照计划逐项归档，不以此次探针替代业务里程碑。

## 7. 风险、阻断与评审决策

- 推荐 A；若采用 B，必须先人工确认计划变更，再实施，本文不修改计划。未来业务参数也是评审候选，不宣称冻结生效。
- 当前版本只有静态POM证据，未执行新Testcontainers握手；2.x API/模块及Boot4自动配置是07B实测项。
- MinIO本地镜像已验证，远端获取、空白daemon离线恢复与长期维护仍有风险；CI镜像分发渠道和维护责任需人工确定。ES也需可重复交付预装IK镜像。没有可信制品时明确阻断CI验收，不替换选型。
- 资源不足、企业代理、Ryuk权限、随机端口可达性可能阻断容器运行；不得通过静默跳过关闭问题。
- 模型报告已记录接入验收，但Token误差、窗口预算、检索质量、费用和全链路向量兼容仍未全面验证，本设计不扩大其结论。

官方方案依据：[Surefire匹配规则](https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html)、[Failsafe执行目标](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html)、[Testcontainers JUnit示例及动态端口](https://github.com/testcontainers/testcontainers-java/blob/main/docs/quickstart/junit_5_quickstart.md)、[镜像与Ryuk配置](https://java.testcontainers.org/features/configuration/)。这些资料用于设计；精确版本与功能仍以07B实际解析和运行证据为准。

## 本阶段检查边界

仅文档审阅、完整新增diff检查及 git diff --check；未运行Maven格式化/编译/测试、Docker命令或供应商请求。没有将历史验收记录标为本次重新通过。提交后停止，等待人工评审。
