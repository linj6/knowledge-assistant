# Knowledge Assistant

Knowledge Assistant 是一个基于 Java / Spring AI 的个人知识库与 RAG 问答项目。你可以导入自己的 Markdown 和纯文本资料，对比检索策略，并在同一知识库内连续追问、查看回答引用的原文片段。

## 当前功能

- 注册、登录和登出，按账号隔离知识库、文档、导入任务和会话。
- 创建及浏览知识库，上传 UTF-8 Markdown / TXT，查看异步导入进度。
- 对比向量检索、BM25、RRF 融合和融合后重排。
- 单轮 API 与多轮问答页面，提供引用跳转、取消生成和轮次状态查询。

登录后可在「记忆」页手动管理长期记忆并开启账号总开关。总开关默认关闭。启用后，多轮问答选中的记忆可能发送给线上模型。详见[长期记忆](docs/core-features.md#长期记忆)。

## 工程实现

项目是单 Maven 模块，使用 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2、Spring Security、MyBatis 和 Flyway。静态 HTML、CSS、JavaScript 与 REST API 由同一应用提供。PostgreSQL / pgvector 保存业务数据和向量，Elasticsearch / IK 提供关键词检索，MinIO 保存原文件，Redis 保存登录会话。版本以 [pom.xml](pom.xml) 和 [Compose 配置](compose.yaml) 为准。

- **索引按版本发布**。Worker 构建并校验 PostgreSQL 与 Elasticsearch 的数据，检索以 PostgreSQL 当前发布版本为边界。
- **引用可回查**。后端校验引用与本次上下文的对应关系，界面可按文档、索引版本和 Chunk 查看证据。
- **轮次可追踪**。同一会话内用轮次 ID 幂等提交，轮询读取持久化结果，服务启动时收敛遗留的处理中状态。
- **评估可复算**。M4 冻结检索候选、Gold 与人工标注，记录离线指标和独立的 LIVE 联调结果。

## 快速启动

推荐在 Windows 上启动 Docker Desktop，切换到 Linux amd64 容器模式，然后在仓库根目录运行：

```powershell
.\start.ps1
```

首次运行时，输入 `YES` 显式授权在线调用及可能产生的费用，再按隐藏输入提示填写阿里云百炼 API Key。输入 `NO` 也可继续部署，在线模型和导入 Worker 保持关闭。脚本会构建镜像、启动应用和基础设施、执行 Flyway 迁移、初始化 MinIO Bucket 和生命周期规则，并在健康检查通过后打开 `http://127.0.0.1:8080/`。

完整参数、资源实测和排查方法见 [Windows 一键部署](docs/getting-started.md#windows-一键部署)。需要使用 JDK、Maven 或 IDEA 调试时，参见[开发模式的环境准备](docs/getting-started.md#环境与基础设施)和 [local Profile 启动说明](docs/getting-started.md#应用配置与启动)。

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

Windows Docker Desktop 下的独立空引擎已通过冷部署验收，初始镜像、构建缓存和数据卷均为空。真实在线业务 E2E 已通过，百炼请求经计数网关转发。标准 Compose 网络下已验证百炼 Endpoint 配置及 DNS、TCP、TLS 直连，带密钥直连的 Chat 和 Embedding 尚未实测。

完整导入与问答需要模型配置。在线模型默认关闭，须显式授权，真实请求可能计费。首次构建需为镜像、缓存及持久化卷预留资源，实测耗时和资源用量见 [Windows 一键部署](docs/getting-started.md#windows-一键部署)。MinIO 固定镜像的获取、校验和导入流程见[镜像交付说明](infra/minio/README.md)。

对话历史限于单个会话。手动维护的长期记忆可在同一账号的多轮会话间复用。Agent、Workflow、MCP、Skills 和 Harness 均未实现。评估工作台的 HTTP 路由在当前安全配置中禁用。
