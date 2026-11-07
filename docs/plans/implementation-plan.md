# Knowledge Assistant — V1 Implementation Plan

- 状态：M0 已评审；M1～M5 为整体规划，尚未详细拆分
- 日期：2026-11-06
- 基线：[Product Spec](../product/product-spec.md) · [Architecture Spec](../architecture/architecture.md)
- 范围：P0，42 项任务（M0 7 / M1 13 / M2 7 / M3 6 / M4 5 / M5 4）

## 1. 实施原则与节奏

Spec → Architecture → Plan → Codex CLI 单任务实现 → 人工评审 → 真实测试 → 小步提交 → 里程碑验收。代码可读性优先；主流程和重要业务逻辑必须有准确、通俗的中文注释。不得虚构测试或性能结果。M0 全部使用 `feature/m0-foundation` 分支，每个任务单独提交；M0 通过后再细化 M1，不提前建设 P1/P2、Memory、Agent 或 Web UI。Codex 不得擅自修改已冻结 Spec。

## 2. M0～M5 任务总览

| 里程碑 | 目标 | 数量 | 完成门槛 |
|---|---|---:|---|
| M0 | 工程准备 | 7 | 真实容器、数据库迁移、模型接口与测试基线通过 |
| M1 | 导入与双索引 | 13 | 成功/失败/重复/竞态/恢复及首次发布可验证 |
| M2 | 四策略检索 | 7 | Top-K 前过滤、KB 隔离、RRF/Rerank 对照、故障可识别 |
| M3 | RAG 与引用 | 6 | 有答案/无答案/非法引用/外部失败测试通过 |
| M4 | 评估闭环 | 5 | 固定集、指标分母、逐例结果、人工评审与复跑 |
| M5 | 工程验收 | 4 | E2E、故障注入、非技术资料、README 与 P0 门禁 |

### M0 — 工程准备

| ID | 任务名称 |
|---|---|
| M0-01 | 技术版本兼容性验证 |
| M0-02 | 工程骨架与模块边界 |
| M0-03 | Docker Compose 基础设施 |
| M0-04 | 配置、安全与 API 错误基线 |
| M0-05 | Flyway 与数据库连接验证 |
| M0-06 | 模型与 Rerank 连通性验证 |
| M0-07 | 测试基础设施与配置基线 |

### M1 — 文档导入与双索引

| ID | 任务名称 |
|---|---|
| M1-01 | 核心数据模型与数据库约束 |
| M1-02 | 知识库 REST API |
| M1-03 | 文件校验、MinIO 上传与导入任务创建 |
| M1-04 | Markdown/TXT 解析与原文位置映射 |
| M1-05 | Chunk 切分与稳定 ID |
| M1-06 | Embedding 与 PG 向量写入 |
| M1-07 | ES 索引映射与 BM25 写入 |
| M1-08 | 导入状态机与后台任务领取 |
| M1-09 | Worker 租约、fencing 与写入互斥 |
| M1-10 | 双索引完整性与可搜索验证 |
| M1-11 | 首次索引发布与幂等提交 |
| M1-12 | 有限重试、失败恢复与孤儿对象处理 |
| M1-13 | 完整导入集成与故障验收 |

### M2 — 四策略检索

| ID | 任务名称 |
|---|---|
| M2-01 | 发布快照与统一检索协议 |
| M2-02 | pgvector 精确向量检索 |
| M2-03 | Elasticsearch BM25 检索 |
| M2-04 | RRF 融合与候选去重 |
| M2-05 | Rerank 适配与重排链路 |
| M2-06 | 四策略编排、REST API 与诊断 |
| M2-07 | 可见性、隔离、故障与策略对照验收 |

### M3 — RAG 问答

| ID | 任务名称 |
|---|---|
| M3-01 | ContextBuilder 与证据 ID 映射 |
| M3-02 | Token Budget 与上下文构建校验 |
| M3-03 | AnswerGenerator 与结构化输出协议 |
| M3-04 | CitationValidator 与来源解析 |
| M3-05 | RagService、回答 API 与异常状态 |
| M3-06 | 问答引用、拒答与提示注入测试 |

### M4 — 评估闭环

| ID | 任务名称 |
|---|---|
| M4-01 | 固定评估集、Gold 标注与数据快照 |
| M4-02 | 指标计算与口径单元测试 |
| M4-03 | 四策略检索评估 Runner |
| M4-04 | 问答评估 Runner 与人工评审协议 |
| M4-05 | 结果报告、配置追踪与可复现性验收 |

### M5 — 工程验收

| ID | 任务名称 |
|---|---|
| M5-01 | 全链路 E2E 与真实故障注入 |
| M5-02 | 非技术知识库通用性验证 |
| M5-03 | README、部署运维与故障诊断文档 |
| M5-04 | P0 质量门禁、最终评审与归档 |

## 3. 关键依赖与风险

- 主路径：M0 → M1 双索引首次发布 → M2 四策略 → M3 问答 → M5；M4 数据集在 M1 稳定 Chunk ID 后可前置准备，Runner 依赖 M2/M3。
- M1-09 必须解决旧 Worker 租约过期后的迟到写入：claim_epoch 仅阻止发布不足以防止 ES 覆写，需互斥或代际隔离并真实故障注入。
- M1-10/11 与 M2-01 必须验证 ES Refresh、完整 ID 集合及 PG/ES 共同发布快照，禁止 Top-K 后才过滤未发布数据。
- M1-04 验证 Unicode 码点原文偏移，特别是中文、CRLF、Emoji、Markdown 标记。
- M0 固定真实兼容版本及模型能力；配置初始值不是经实验证明的最优值。
- 评估使用正式 RetrievalService/RagService，不复制算法；严格禁止评估静默降级。

## 4. M0 详细实施任务（已确认）

执行顺序：M0-01 → M0-02 与 M0-03 → M0-04 与 M0-05 → M0-06 → M0-07。M0-02/03 可独立推进，M0-05 仅需 M0-04 的数据源配置部分。

### M0-01 技术版本兼容性验证

- **目标与价值**：锁定可获取且兼容的运行栈，避免后续返工
- **前置依赖**：Product/Architecture Spec
- **实现范围**：核对 Java21/Maven、Boot3/Spring AI、JUnit/Testcontainers、PostgreSQL/pgvector、ES/IK 精确版本、MinIO 镜像与许可证；固定镜像 tag/digest；形成兼容矩阵与来源。
- **非目标**：不写业务代码、不验证业务索引、不接模型业务链路。
- **预计文件/模块**：docs/engineering/compatibility-matrix.md；必要的决策记录
- **验收标准**：各组件版本、来源、风险与验证状态明确；依赖可解析、镜像可拉取；未实测项不得写已通过。
- **测试要求**：Maven dependency resolution；Docker pull/version；ES/IK 版本匹配核对。
- **保留证据**：依赖解析输出、镜像标识、兼容性矩阵、未关闭项。
- **执行粒度**：原则上一次 Codex 任务完成；人工审查后提交。

### M0-02 工程骨架与模块边界

- **目标与价值**：建立最小可运行、易读的模块化单体工程
- **前置依赖**：M0-01
- **实现范围**：单 Maven 模块、Java21、Boot 启动入口；按 knowledge/ingestion/retrieval/rag/evaluation/infrastructure 分包；基础 Maven 插件和启动测试。
- **非目标**：不建多 Maven 子模块、不建业务 DTO/表/Service 占位类、不做通用插件框架。
- **预计文件/模块**：pom.xml、src/main/java/.../KnowledgeAssistantApplication.java、src/main/resources/application.yml、src/test/...、.gitignore、README.md
- **验收标准**：应用可启动；mvn clean verify 通过；依赖和包结构简洁。
- **测试要求**：JUnit context smoke；Maven 构建。
- **保留证据**：JDK/Maven 版本、构建和启动记录。
- **执行粒度**：原则上一次 Codex 任务完成；人工审查后提交。

### M0-03 Docker Compose 基础设施

- **目标与价值**：让真实基础设施可重复启动与清理
- **前置依赖**：M0-01
- **实现范围**：Compose 启动 PG+pgvector、ES+IK、MinIO；健康检查、持久卷、端口和本地环境示例；最小 PG 扩展、ES IK/BM25、MinIO 对象读写冒烟。
- **非目标**：不做 K8s、HA、应用生产镜像、监控集群。
- **预计文件/模块**：compose.yaml、.env.example、infra/ 下必要初始化文件、README.md
- **验收标准**：三个服务健康；向量扩展存在；IK 中英文分词与 BM25 查询真实可执行；MinIO 对象读写与重启持久化成功。
- **测试要求**：Docker compose 启停/重启；SQL、ES analyzer/search、MinIO put/get。
- **保留证据**：镜像 digest、健康检查、SQL/ES 响应、MinIO 操作结果。
- **执行粒度**：原则上一次 Codex 任务完成；人工审查后提交。

### M0-04 配置、安全与 API 错误基线

- **目标与价值**：统一配置、请求追踪及安全错误边界
- **前置依赖**：M0-02；基础环境配置参考 M0-03
- **实现范围**：Profiles/env、ConfigurationProperties 和启动校验；安全的统一 API 错误格式、异常处理、requestId；日志脱敏。仅启用功能要求相应密钥。
- **非目标**：不做 RBAC/OAuth、分布式 tracing 平台、业务错误码大全。
- **预计文件/模块**：infrastructure/config、infrastructure/web、application.yml、相关测试
- **验收标准**：非法参数返回 4xx；未知异常安全 5xx；requestId 可追踪；不泄漏密钥或堆栈；无在线密钥可运行离线测试。
- **测试要求**：MockMvc、配置绑定、错误脱敏测试。
- **保留证据**：脱敏响应样例、测试结果。
- **执行粒度**：原则上一次 Codex 任务完成；人工审查后提交。

### M0-05 Flyway 与数据库连接验证

- **目标与价值**：建立可复跑数据库迁移和真实向量能力
- **前置依赖**：M0-02、M0-03；M0-04 基础数据源配置
- **实现范围**：JDBC/DataSource、Flyway；迁移启用 vector 扩展；空库迁移/重复运行；测试临时表向量插入与余弦计算。
- **非目标**：不建知识库业务表、不实现正式 VectorRetriever。
- **预计文件/模块**：db/migration/V1__enable_vector_extension.sql、infrastructure/persistence、集成测试
- **验收标准**：Flyway 首次和再次运行正确；vector 扩展与余弦计算通过；迁移失败不能静默启动。
- **测试要求**：真实 PG+pgvector 集成测试、迁移重复执行。
- **保留证据**：schema history、扩展版本、向量计算和测试输出。
- **执行粒度**：原则上一次 Codex 任务完成；人工审查后提交。

### M0-06 模型与 Rerank 连通性验证

- **目标与价值**：验证 Chat/Embedding/Rerank/Tokenizer 真实可用并固定模型配置
- **前置依赖**：M0-02、M0-04；M0-01 的依赖版本
- **实现范围**：优先调查 DashScope；验证 Chat 鉴权、结构化输出可行性、usage，Embedding 维度/批量，Rerank 候选 ID 映射，Tokenizer 精确性或估算边界、超时/限流及成本；在线冒烟独立执行。
- **非目标**：不实现 RAG、Prompt 管理平台、多模型动态路由、引用语义判断。
- **预计文件/模块**：infrastructure/ai、模型配置、src/test/.../ai、docs/engineering/model-capability-report.md
- **验收标准**：三类模型能力均有真实成功请求；向量维度已测；Rerank ID 不错位；Token 方案和错误边界清楚；无密钥泄漏。
- **测试要求**：Fake 协议/错误测试；带 tag/profile 的可选在线冒烟。
- **保留证据**：模型 ID/维度、脱敏请求结果、usage/耗时、异常记录、费用边界。
- **执行粒度**：分两次 Codex 执行（Chat/Embedding；Rerank/Tokenizer/错误），同一任务内小提交。

### M0-07 测试基础设施与配置基线

- **目标与价值**：建立可复用测试层与 M0 可追溯验收基线
- **前置依赖**：M0-03～M0-06
- **实现范围**：JUnit 单测、Testcontainers PG+pgvector/ES+IK/MinIO、独立在线冒烟；固定文件大小、KB 文档上限、ES terms 安全边界、Worker 重试/租约和上下文预算初始值，标明后续验证任务；README。
- **非目标**：不做 CI/CD、生产压测、M1 业务测试；不宣称初始参数最优。
- **预计文件/模块**：pom.xml、src/test、.env.example、README.md、docs/engineering/configuration-baseline.md、m0-verification-report.md
- **验收标准**：默认 mvn verify 无需在线模型密钥、可在 Docker 环境运行单测及容器集成；独立在线冒烟可运行；关键配置有来源与边界；M0 阻断项关闭。
- **测试要求**：完整离线/容器测试与单独在线冒烟；空环境复现。
- **保留证据**：命令、通过/失败数、镜像版本、配置清单、验收报告。
- **执行粒度**：原则上一次 Codex 任务完成；人工审查后提交。

## 5. M0 里程碑完成定义

1. 七项任务均已独立提交，代码主流程中文注释准确，文档与实现一致。
2. Java21/Boot/Spring AI、PG/pgvector、ES/IK、MinIO 版本有真实证据；Compose 可复现。
3. Flyway 空库迁移与重复运行通过；向量和 ES BM25/IK、MinIO 实际测试通过。
4. Chat/Embedding/Rerank 真实冒烟可单独执行；默认 `mvn verify` 不要求在线密钥，Docker 集成测试可运行。
5. 关键参数有初始配置、约束与后续验证点；不得宣称未经验证的最优参数。
6. `docs/engineering/m0-verification-report.md` 记录真实执行命令、环境、结果、未解决问题；没有证据的项目标记未验证。
7. 人工评审 M0 后再细化 M1，避免先做大量未实施的细节设计。

## 6. Codex CLI 单任务模板

每次只交付一个 Task ID；模板见 [codex-task-template.md](codex-task-template.md)。Codex 应先检查仓库当前代码与依赖，不擅自扩大范围或更改 Spec；完成后提供 diff、测试命令、结果、证据及提交 SHA。若 Docker/外部模型不可访问，明确报告未验证，不能用 Mock 代替真实集成验收。
