# M0～M1 架构与核心流程

基线：`feature/m1-ingestion-indexing`，`29af0d444ce9f0baa762f2574b1ac1205119f965`。依据生产 Java、Mapper SQL、V1～V5 迁移和 application 配置整理；不是未来 V1 全景。

## 1. 系统架构

```mermaid
flowchart TD
  C[客户端] --> API[Spring Boot API]
  API --> K[KnowledgeBaseService]
  API --> U[DocumentImportService]
  subgraph APP[单个应用进程]
    API
    K
    U
    S[TaskScheduler] --> T[TaskService]
    S --> W[TaskWorker 顺序执行]
    W --> P[IngestionTaskProcessor]
    P --> CH[Parser 和 ChunkService]
    P --> EM[EmbeddingService]
    P --> EI[EsIndexService]
    P --> V[IndexVerificationService]
    P --> PUB[IndexPublishService]
  end
  subgraph DB[PostgreSQL 业务权威数据源]
    META[KB Document IndexVersion ImportTask Chunk]
    VECTOR[pgvector 向量列]
  end
  K --> META
  U --> META
  U --> M[MinIO 原始文档]
  T --> META
  P --> M
  CH --> META
  EM --> VECTOR
  EM --> MODEL[外部 Embedding HTTP 服务]
  EI --> ES[Elasticsearch 与 IK 关键词索引]
  EI --> META
  V --> META
  V --> ES
  PUB --> META
```

pgvector 位于 PostgreSQL 内。ES 是关键词副本，不能决定发布事实。MinIO 同一 Bucket 下区分 staging 和 originals。Worker 没有独立部署或分布式互斥，必须只运行一个启用 Worker 的应用。

启动由 KnowledgeAssistantApplication 扫描组件；TaskScheduler 仅在 worker.enabled=true 装配，默认 false。ModelConfiguration / EmbeddingService 受 model.enabled 控制；ES 服务和验证器受 elasticsearch.enabled 控制；IngestionTaskProcessor 要求这两个开关同时为 true。Scheduler 找不到处理器则不领取。开关不等于外部连通性验证。

## 2. 上传

```mermaid
sequenceDiagram
  participant C as Client
  participant A as Controller
  participant U as DocumentImportService
  participant M as MinIO
  participant PG as PostgreSQL
  C->>A: multipart 单文件
  A->>U: receive
  U->>PG: 确认知识库存在
  U->>U: 文件名 类型 大小 UTF-8 校验及原始字节 SHA-256
  U->>PG: 按 KB 和 hash 查询已有任务
  alt 已存在
    U-->>A: 已有任务
    A-->>C: 200
  else 新内容
    U->>U: 校验模型配置并捕获切分快照
    U->>M: put staging 随机 Key
    U->>M: copy 到 originals 随机 Key
    alt 对象调用失败或复制结果未知
      U->>M: 尽力删除本请求 originals
      Note over U,M: 补偿失败仅安全告警 可能留下孤儿
    else 对象准备完成
      rect rgb(235,245,255)
        Note over U,PG: 短 PG 事务 网络对象操作不在事务内
        U->>PG: 创建 Document 和 BUILDING IndexVersion 和 PENDING ImportTask
        PG-->>U: 提交结果
      end
      alt 提交异常或结果不确定
        U->>PG: 查询主库 originals 引用
        Note over U,PG: 确认无引用才删除 有引用或查询失败则保留
        opt 确认无引用
          U->>M: 尽力删除 originals
        end
        Note over U,PG: 唯一键冲突时查询胜出任务 可返回 200
      else 提交成功
        Note over U,A: 新任务结果为 202
      end
    end
    U->>M: finally 尽力删除 staging
    Note over U,M: 删除失败不改变业务结果 staging 两天 ILM 兜底
    U-->>A: 任务或异常
    A-->>C: 202 或并发去重 200 或错误协议
  end
```

主要类：DocumentImportController、DocumentImportService、DocumentImportRepository、MinioOriginalFileStore。原始 BOM 纳入摘要；去重范围是同一 KB。数据库异常不能直接等同回滚；正式对象引用未知时禁止删除。originals 补偿失败不由 staging ILM 回收，需人工核对。

## 3. 构建与首次发布

```mermaid
sequenceDiagram
  participant S as TaskScheduler
  participant T as TaskService
  participant W as TaskWorker
  participant P as IngestionTaskProcessor
  participant M as MinIO
  participant R as Parser
  participant CH as ChunkService
  participant E as EmbeddingService
  participant PG as PostgreSQL
  participant EI as EsIndexService
  participant ES as Elasticsearch
  participant V as IndexVerificationService
  participant PUB as IndexPublishService
  S->>T: recoverNext 然后 claimNext
  T->>PG: 短事务锁 Task 领取 RUNNING
  Note over T,PG: attempt 加一 epoch 加一 owner 和数据库时间租约
  S->>W: execute 已领取任务
  W->>P: process 协作式截止时间
  P->>PG: 校验任务 文档 版本和领取关系
  alt 已存在发布事实
    P->>PUB: 核对完整发布事实 幂等返回
  else 未发布
    opt BUILDING
      P->>M: 读取 PG 记录的 originals Key
      P->>P: 校验原始字节 SHA-256
      P->>R: 解析 Markdown 或 TXT
      P->>CH: initialize
      CH->>PG: 事务初始化 Chunk 和数量 或逐项核对已有集合
      P->>E: embed 仅缺失向量
      E->>E: 外部 HTTP Embedding 调用
      E->>PG: 分批短事务写入向量
      P->>EI: index
      EI->>PG: 核对 BUILDING 快照
      EI->>ES: create-only Bulk 固定 Chunk ID
      Note over EI,ES: 409 读取已有内容 完全一致才接受
    end
    P->>V: verify VERIFIED 恢复也重新核验
    V->>PG: 读取一致快照 校验数量 归属 正文 向量
    V->>ES: refresh 完整集合字段比较和实际搜索验证
    V->>PG: 短事务复核快照 标记 VERIFIED
    P->>PUB: publish
    rect rgb(235,245,255)
      PUB->>PG: 事务锁 Document 再 Version 再 Task
      PUB->>PG: activeVersionId 加 PUBLISHED 和 publishedAt 加 SUCCEEDED
      Note over PUB,PG: 三项原子提交 最终条件更新校验有效租约
    end
    Note over P,PG: 提交响应未知时读取任务 再调用发布核对完整事实
  end
  Note over W,PG: 成功仅由发布事务写入 对外 READY
```

主要类还包括 EmbeddingRepository、PgIntegrityService、EsIntegrityService、IndexPublishRepository。整个处理器无外层事务；MinIO、模型、ES 网络请求均不持有 PG 业务事务。Chunk 初始化、向量批次写入、验证完成和发布各自使用短事务。发布后不再执行会触发失败转换的截止时间检查。

## 4. 任务状态与恢复

```mermaid
stateDiagram-v2
  state ImportTask {
    [*] --> PENDING
    PENDING --> RUNNING: 领取 次数加一
    RUNNING --> SUCCEEDED: 首次发布事务
    RUNNING --> FAILED: 执行失败或异 owner 租约过期恢复
    FAILED --> RUNNING: 可重试且到期且次数小于4
    SUCCEEDED --> SUCCEEDED: 原领取事实幂等确认
  }
  state IndexVersion {
    [*] --> BUILDING
    BUILDING --> VERIFIED: 双索引验证和快照复核
    VERIFIED --> VERIFIED: 恢复后重新验证
    VERIFIED --> PUBLISHED: 首次发布事务
    PUBLISHED --> PUBLISHED: 完整发布事实幂等确认
    state "FAILED（枚举存在，当前无写入路径）" as VersionFailed
  }
```

FAILED 为真实枚举值；图中保留孤立节点，表示当前生产流程没有写入路径。

ImportTask 的 FAILED 不必然终止：可重试错误安排 5/15/45 秒退避，加 ±20% 抖动，默认总共最多执行四次（配置可覆盖）。永久错误及未知 RuntimeException 不重试，next_retry_at 为空。租约默认十五分钟；任务协作式截止时间十分钟，不续租、不强制中断。恢复记录 WORKER_INTERRUPTED，恢复后仍走正常领取。必须确认旧进程停止，租约不提供跨存储 fencing。

IndexVersion 枚举有 FAILED，但当前任务 fail/recover SQL 只修改 ImportTask，不会把版本改为 FAILED，也不会自动重置版本为 BUILDING。正常失败保留 BUILDING 或 VERIFIED；后者下次跳过构建但重新验证。TaskMapper 允许领取某些 FAILED 版本，而处理器拒绝该状态，这是当前实现边界，不能画成已实现的 FAILED 到 BUILDING 转换。

TaskStateMachine 只管理领取和失败；SUCCEEDED 由 IndexPublishService 管理。READY 是 ImportTaskResponse 的外部映射，不是新增持久化状态。

## 一致性与能力边界

- PG 唯一约束裁决 KB 内 hash 去重；Chunk 按版本和顺序唯一；向量列固定 1024 维，版本模型及切分快照不得漂移。
- 发布锁顺序 Document → IndexVersion → ImportTask；只锁 Task 的事务不反向锁其他实体。activeVersionId 决定可见性，版本和任务事实必须同时提交。
- PG 与 ES、MinIO 无分布式事务；部分索引写入靠幂等补齐和重新核验，不能宣称永久强一致。
- 已实现 KB API、上传、MD/TXT、双索引验证与首次发布；没有 Retrieval、RAG、Evaluation 服务、Memory、Agent 或 Web UI。

## 与冻结 Architecture Spec 的差异（仅记录）

1. 第六节仍写 M1-12 没有生产 TaskProcessor；当前已存在 IngestionTaskProcessor。
2. 状态语义描述失败版本重新进入 BUILDING，生产 fail/recover 没有该转换。
3. EsIndexService 注释仍提 M1-09 fencing；当前架构明确单 Worker、不承诺严格 fencing。
4. 架构文档当前已是 Boot 4.1.2，用户 AGENTS 所述 Boot 3.x 差异在此基线不再存在。
5. 架构全景包含后续 Retrieval/RAG/Evaluation，不能作为 M1 已实现清单。

验证：依据源码逐项审阅。Mermaid 实际渲染检查尚未完成，不标记通过。未运行 Maven；本阶段仅新增文档。未修改冻结架构。
