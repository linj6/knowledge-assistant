# Knowledge Assistant

Knowledge Assistant 是一个基于 Java / Spring AI 的个人知识库与 RAG 问答项目。你可以导入自己的 Markdown 和纯文本资料，对比检索策略，并在同一知识库内连续追问、查看回答引用的原文片段。

## 当前功能

- 注册、登录和登出，按账号隔离知识库、文档、导入任务和会话。
- 创建及浏览知识库，上传 UTF-8 Markdown / TXT，查看异步导入进度。
- 对比向量检索、BM25、RRF 融合和融合后重排。
- 单轮 API 与多轮问答页面，提供引用跳转、取消生成和轮次状态查询。

## 工程实现

项目是单 Maven 模块，使用 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2、Spring Security、MyBatis 和 Flyway。静态 HTML、CSS、JavaScript 与 REST API 由同一应用提供。PostgreSQL / pgvector 保存业务数据和向量，Elasticsearch / IK 提供关键词检索，MinIO 保存原文件，Redis 保存登录会话。版本以 [pom.xml](pom.xml) 和 [Compose 配置](compose.yaml) 为准。

- **索引按版本发布**。Worker 构建并校验 PostgreSQL 与 Elasticsearch 的数据，检索以 PostgreSQL 当前发布版本为边界。
- **引用可回查**。后端校验引用与本次上下文的对应关系，界面可按文档、索引版本和 Chunk 查看证据。
- **轮次可追踪**。同一会话内用轮次 ID 幂等提交，轮询读取持久化结果，服务启动时收敛遗留的处理中状态。
- **评估可复算**。M4 冻结检索候选、Gold 与人工标注，记录离线指标和独立的 LIVE 联调结果。

## 快速启动

先按[开始使用](docs/getting-started.md)准备 JDK、Maven、基础设施镜像和本地配置。使用 local Profile 启动后访问 `http://localhost:8080/api/index.html`。

| 文档 | 内容 |
| --- | --- |
| [开始使用](docs/getting-started.md) | 环境、配置、启动与排查 |
| [当前架构](docs/architecture.md) | 模块、数据存储和关键流程 |
| [核心功能](docs/core-features.md) | 权限、导入、检索、引用与会话 |
| [M4 评估](docs/evaluation.md) | 冻结实验、历史联调与复现 |
| [代码风格与验证](docs/code-style.md) | 格式化、变更验证和提交规则 |
| [Java 包结构](docs/package-structure.md) | 模块职责、类的归属和依赖 |
| [历史资料](docs/archive/README.md) | 开发过程与阶段验证记录 |

## 当前限制

MinIO 固定镜像的获取、校验和导入流程见[镜像交付说明](infra/minio/README.md)。新环境端到端部署仍待验证。完整导入与问答需要模型配置，在线模型调用默认关闭并要求显式授权，真实请求可能计费。现有多轮上下文限于单个会话。跨会话长期记忆、Agent、Workflow、MCP、Skills 和 Harness 均未实现。评估工作台的 HTTP 路由在当前安全配置中禁用。
