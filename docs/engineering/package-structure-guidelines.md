# 包结构规范（R0-01 评审稿）

基于 refactor/package-structure 当前源码；本稿是后续建议，不授权实施。遵循已确认的业务模块、单表 SQL 优先及按需 Repository 决策。Architecture Spec 的 Boot 3.x 与当前 pom.xml 4.1.2 存在已知差异，本次不改冻结文档或依赖。

## 模块职责

knowledge 管理知识库元数据；document 管理文档、索引版本和 Chunk 持久化模型及列表、引用证据读取；ingestion 管理上传、任务、解析、切分、向量写入、索引构建、验证和发布；retrieval 管理发布快照、向量/BM25 召回、融合与重排；rag 管理上下文、生成与引用校验。document 的实体可由 ingestion 与 retrieval 直接使用，不为消除所有跨模块依赖增加接口。

## 放置规则

- 所有 HTTP Controller 放模块 controller；请求响应 DTO 放模块 dto，类名和 JSON 协议保持。rag.model 与 retrieval.model 是业务结果和内部参数，不因被 API 返回就整体搬到 dto。
- 简单模块按 controller/service/repository/mapper/entity/dto 按需组织；复杂模块继续 task、embedding、vector、validation 等功能包，功能包内无需再为一个类创建 service 子包。
- Service 编排流程；Repository 封装查询、条件写入和数据一致性，允许直接使用 Mapper，禁止为了层级齐全创建转发层。现有事务位置先保留，不能借规范将所有事务机械迁入 Service。
- 基础 Mapper 与实体按表归属；专用 Mapper 留所属功能。Java Mapper 的业务 SQL 使用 XML。XML 位于 resources/mapper，namespace 与 Java 全限定名一致；资源目录不要求与每层 Java 目录完全对称。
- 单表查询优先基础 Mapper；跨表原子发布、pgvector、快照与批量回填可保留专用 Mapper。字段变更必须检查迁移计划的矩阵及 Java Wrapper，不能只搜索 XML。
- Entity 不是 HTTP DTO；不为表名对称强制重命名 IndexVersion/ImportTask。

## Infrastructure 边界

ai 保留供应商适配、模型 Bean、故障转换、TokenEstimator、Reranker 及供应商调用协议 RerankCandidate/RerankResult；不新增业务抽象。ModelProperties 是模型连接与能力配置，留 infrastructure.config。Chunking/Upload/Worker/RerankProperties 属业务参数绑定，建议移到对应功能包；EmbeddingProperties、ContextProperties、ChunkingConfig、RetrievalParameters 已在业务包，保留。

elasticsearch 保留客户端配置，并承接共享 EsChunkDocument、EsChunkFields 和 EsIndexRepository；EsIndexService 仍是导入构建编排。其索引名常量被检索引用的问题单独评审，不能仅移动 DTO 就宣称完成解耦。

persistence 只留 VectorTypeHandler、VectorSchema 等数据库技术能力，业务实体及基础 Mapper 迁业务。storage 保留 MinIO 适配、OriginalFileStore、ObjectKeyPrefixes，原对象路径及读取限制不变。logging 的全部脱敏转换器、环境后处理器、FailureLog、StartupConfigLogger 保留；后者引用业务默认常量是装配/观测依赖，暂不增加配置抽象。web 保留统一异常、Jackson 和 RequestIdFilter；common 的 Result、错误协议、RequestIdContext 保留。

## 新类归属示例

新增文档列表筛选：document.dto 与 DocumentQueryService，普通单表 SQL 优先 DocumentMapper；保留现有分页排序。新增任务租约条件更新：ingestion.task 的专用 SQL，不放通用 infrastructure。新增检索通道：retrieval 功能包，供应商客户端才放 infrastructure。新增切分参数：ingestion.chunk，不放全局 config。新增表字段：先查影响矩阵、实体、XML/Wrapper、调用方，再新增 Flyway 迁移，历史迁移只读。

## 不变约束

不改 API、SQL 条件和排序、事务传播及隔离、锁顺序、ES mapping/语义、MinIO 路径与处理行为；不增加依赖，不扩展 P1/P2、Memory、Agent 或 Web UI。争议项保持现状直到单独评审。
