# Java 包结构

项目采用单 Maven 模块。Java 根包为 `com.lnjecit.knowledge.assistant`，其下按业务职责和共享技术能力组织。运行时流程见[当前架构](architecture.md)。

## 模块职责

| 包 | 职责与现有结构 |
| --- | --- |
| `auth` | 注册、认证和用户数据访问。HTTP 入口在 `auth.web`，认证及用户加载逻辑在 `auth.service`。 |
| `knowledge` | 知识库元数据与账号范围内的读写，按 `controller`、`dto`、`service`、`repository`、`mapper`、`entity` 组织。 |
| `document` | 文档、索引版本及 Chunk 的持久化模型，提供文档列表与引用证据查询。 |
| `ingestion` | 文件上传与异步导入。按 `upload`、`task`、`parser`、`chunk`、`embedding`、`indexing`、`verification`、`publish` 等功能组织。 |
| `retrieval` | 在发布快照限定的范围内检索，按 `snapshot`、`embedding`、`vector`、`keyword`、`fusion`、`rerank` 组织，由 `RetrievalService` 编排。 |
| `rag` | 问答编排，按 `rewrite`、`context`、`generation`、`validation` 组织查询改写、上下文选取、生成及引用校验。 |
| `conversation` | 会话及消息持久化、历史读取和轮次提交、执行与取消，由轮次服务调用 RAG。 |
| `evaluation` | 评估数据加载、指标计算、运行结果及人工评审记录，包含 `dataset`、`metrics`、`store` 和评估 HTTP 入口等。 |
| `infrastructure` | 共享技术实现，包括模型适配与配置、Elasticsearch、MinIO、数据库类型处理、日志脱敏及 Web 安全与通用 Web 配置。 |

## 类的职责

| 类型 | 放置与职责 |
| --- | --- |
| Controller | 放在所属业务的 HTTP 入口包，如 `controller` 或现有的 `auth.web`。接收请求、校验输入并转换响应，由 Service 执行业务流程。HTTP 响应使用明确的 API 数据契约，避免直接暴露持久化实体。 |
| DTO | 表达请求与响应契约，通常放在所属模块的 `dto`。业务参数及结果可留在 `model` 或对应功能包。 |
| Service | 实现业务规则、编排流程并管理业务事务边界。已有 Repository 的事务按实际数据一致性要求保留。 |
| Repository | 封装有业务含义的查询、条件写入或组合持久化操作，按需调用 Mapper 或客户端。 |
| Mapper | 负责 MyBatis / MyBatis-Plus 数据映射。基础 Mapper 随表的归属模块放置，专用 Mapper 随功能放置。普通单表操作优先复用基础 Mapper，发布、快照、向量查询等可使用专用 Mapper。业务 SQL 放在 `src/main/resources/mapper` 下的 XML 中，`namespace` 对应 Java Mapper 全限定名。 |
| Entity | 表示数据库持久化记录，随拥有该表及其生命周期的模块放置，保持字段类型和映射明确。 |

## 子包与依赖

- 简单模块按实际需要使用职责子包。复杂流程优先按功能内聚，例如 `ingestion.task` 可同时包含任务调度、状态机、数据访问和任务模型，`retrieval.vector` 可同时包含召回逻辑和专用数据访问。只有功能包内部继续分组能改善定位时才加一层。
- 保留有业务含义的跨模块调用。例如导入发布复用 `document` 的实体和基础 Mapper，RAG 复用 `retrieval`，会话轮次调用 `RagService`。按实际需要引入 Repository 或接口，避免仅转发调用的空壳。
- 供应商适配、客户端配置和共享技术能力留在 `infrastructure`。切分、重排、导入 Worker 等业务参数与编排留在各自功能包。
