# 包结构迁移计划（R0-01 评审稿）

## 基线与范围

分支 refactor/package-structure。开始时已有未跟踪 docs/engineering/web-06-c-isolation-validation.md、web-06-c-preflight.md、infra/web06c/，全部保留。本轮仅新增两份规划文档，不执行任何迁移，不提交。路径清单相对 src/main/java/com/lnjecit/knowledge/assistant；目标均为建议，类名全部保留，无强制重命名。下表覆盖全部生产 Java 文件，内部 record 随外部类迁移。

## 逐类清单

|当前路径|目标路径|分类及理由|
|---|---|---|
|`common/api/Result.java`|`common/api/Result.java`|保留：现有功能归属明确，避免目录对称化|
|`common/error/BusinessException.java`|`common/error/BusinessException.java`|保留：现有功能归属明确，避免目录对称化|
|`common/error/CommonErrorCode.java`|`common/error/CommonErrorCode.java`|保留：现有功能归属明确，避免目录对称化|
|`common/error/ErrorCode.java`|`common/error/ErrorCode.java`|保留：现有功能归属明确，避免目录对称化|
|`common/RequestIdContext.java`|`common/RequestIdContext.java`|保留：现有功能归属明确，避免目录对称化|
|`document/CitationEvidenceResponse.java`|`document/dto/CitationEvidenceResponse.java`|纯包迁移：集中 API 请求响应协议|
|`document/DocumentListResponse.java`|`document/dto/DocumentListResponse.java`|纯包迁移：集中 API 请求响应协议|
|`document/DocumentQueryController.java`|`document/controller/DocumentQueryController.java`|纯包迁移：统一业务 HTTP 入口|
|`document/DocumentQueryMapper.java`|`document/mapper/DocumentQueryMapper.java`|纯包迁移：先纯迁移，查询拆分另行评审|
|`document/DocumentQueryRepository.java`|`document/repository/DocumentQueryRepository.java`|纯包迁移：简单模块数据访问归位|
|`document/DocumentQueryService.java`|`document/service/DocumentQueryService.java`|纯包迁移：简单模块按职责组织|
|`document/DocumentSummaryResponse.java`|`document/dto/DocumentSummaryResponse.java`|纯包迁移：集中 API 请求响应协议|
|`infrastructure/ai/DashScopeReranker.java`|`infrastructure/ai/DashScopeReranker.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/ModelConfiguration.java`|`infrastructure/ai/ModelConfiguration.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/ModelFailures.java`|`infrastructure/ai/ModelFailures.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/ModelResponseChecks.java`|`infrastructure/ai/ModelResponseChecks.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/RerankCandidate.java`|`infrastructure/ai/RerankCandidate.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/RerankConfiguration.java`|`infrastructure/ai/RerankConfiguration.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/Reranker.java`|`infrastructure/ai/Reranker.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/RerankResult.java`|`infrastructure/ai/RerankResult.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/ai/TokenEstimator.java`|`infrastructure/ai/TokenEstimator.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/config/ChunkingProperties.java`|`ingestion/chunk/ChunkingProperties.java`|纯包迁移：业务配置绑定归功能包，保持配置前缀|
|`infrastructure/config/ModelProperties.java`|`infrastructure/config/ModelProperties.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/config/RerankProperties.java`|`retrieval/rerank/RerankProperties.java`|纯包迁移：重排参数归检索功能|
|`infrastructure/config/UploadProperties.java`|`ingestion/upload/UploadProperties.java`|纯包迁移：业务配置绑定归功能包，保持配置前缀|
|`infrastructure/config/WorkerProperties.java`|`ingestion/task/WorkerProperties.java`|纯包迁移：业务配置绑定归功能包，保持配置前缀|
|`infrastructure/elasticsearch/EsConfiguration.java`|`infrastructure/elasticsearch/EsConfiguration.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/logging/CredentialEnvironmentPostProcessor.java`|`infrastructure/logging/CredentialEnvironmentPostProcessor.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/logging/CredentialRedactingConverter.java`|`infrastructure/logging/CredentialRedactingConverter.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/logging/CredentialThrowableConverter.java`|`infrastructure/logging/CredentialThrowableConverter.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/logging/FailureLog.java`|`infrastructure/logging/FailureLog.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/logging/StartupConfigLogger.java`|`infrastructure/logging/StartupConfigLogger.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/persistence/Document.java`|`document/entity/Document.java`|纯包迁移：业务持久化模型归业务所有；不改类名或表映射|
|`infrastructure/persistence/DocumentChunk.java`|`document/entity/DocumentChunk.java`|纯包迁移：业务持久化模型归业务所有；不改类名或表映射|
|`infrastructure/persistence/DocumentChunkMapper.java`|`document/mapper/DocumentChunkMapper.java`|纯包迁移：按实体归属定位基础 SQL|
|`infrastructure/persistence/DocumentMapper.java`|`document/mapper/DocumentMapper.java`|纯包迁移：按实体归属定位基础 SQL|
|`infrastructure/persistence/ImportTask.java`|`ingestion/task/entity/ImportTask.java`|纯包迁移：业务持久化模型归业务所有；不改类名或表映射|
|`infrastructure/persistence/ImportTaskMapper.java`|`ingestion/task/mapper/ImportTaskMapper.java`|纯包迁移：按实体归属定位基础 SQL|
|`infrastructure/persistence/ImportTaskStatus.java`|`ingestion/task/entity/ImportTaskStatus.java`|纯包迁移：业务持久化模型归业务所有；不改类名或表映射|
|`infrastructure/persistence/IndexVersion.java`|`document/entity/IndexVersion.java`|纯包迁移：业务持久化模型归业务所有；不改类名或表映射|
|`infrastructure/persistence/IndexVersionMapper.java`|`document/mapper/IndexVersionMapper.java`|纯包迁移：按实体归属定位基础 SQL|
|`infrastructure/persistence/IndexVersionStatus.java`|`document/entity/IndexVersionStatus.java`|纯包迁移：业务持久化模型归业务所有；不改类名或表映射|
|`infrastructure/persistence/KnowledgeBase.java`|`knowledge/entity/KnowledgeBase.java`|纯包迁移：业务持久化模型归业务所有；不改类名或表映射|
|`infrastructure/persistence/KnowledgeBaseMapper.java`|`knowledge/mapper/KnowledgeBaseMapper.java`|纯包迁移：按实体归属定位基础 SQL|
|`infrastructure/persistence/VectorSchema.java`|`infrastructure/persistence/VectorSchema.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/persistence/VectorTypeHandler.java`|`infrastructure/persistence/VectorTypeHandler.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/storage/MinioOriginalFileStore.java`|`infrastructure/storage/MinioOriginalFileStore.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/storage/ObjectKeyPrefixes.java`|`infrastructure/storage/ObjectKeyPrefixes.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/storage/OriginalFileStore.java`|`infrastructure/storage/OriginalFileStore.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/web/ApiExceptionHandler.java`|`infrastructure/web/ApiExceptionHandler.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/web/ApiJacksonConfiguration.java`|`infrastructure/web/ApiJacksonConfiguration.java`|保留：现有功能归属明确，避免目录对称化|
|`infrastructure/web/RequestIdFilter.java`|`infrastructure/web/RequestIdFilter.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/chunk/ChunkingConfig.java`|`ingestion/chunk/ChunkingConfig.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/chunk/ChunkingStrategy.java`|`ingestion/chunk/ChunkingStrategy.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/chunk/ChunkRepository.java`|`ingestion/chunk/ChunkRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/chunk/ChunkService.java`|`ingestion/chunk/ChunkService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/chunk/DocumentSplitter.java`|`ingestion/chunk/DocumentSplitter.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/chunk/FixedCodePointSplitter.java`|`ingestion/chunk/FixedCodePointSplitter.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/embedding/EmbeddingMapper.java`|`ingestion/embedding/EmbeddingMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/embedding/EmbeddingProperties.java`|`ingestion/embedding/EmbeddingProperties.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/embedding/EmbeddingRepository.java`|`ingestion/embedding/EmbeddingRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/embedding/EmbeddingService.java`|`ingestion/embedding/EmbeddingService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/indexing/EsChunkDocument.java`|`infrastructure/elasticsearch/EsChunkDocument.java`|纯包迁移：共享 ES 协议及技术访问不归导入内部|
|`ingestion/indexing/EsChunkFields.java`|`infrastructure/elasticsearch/EsChunkFields.java`|纯包迁移：共享 ES 协议及技术访问不归导入内部|
|`ingestion/indexing/EsIndexRepository.java`|`infrastructure/elasticsearch/EsIndexRepository.java`|纯包迁移：共享 ES 协议及技术访问不归导入内部|
|`ingestion/indexing/EsIndexService.java`|`ingestion/indexing/EsIndexService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/DocumentParser.java`|`ingestion/parser/DocumentParser.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/DocumentParserFactory.java`|`ingestion/parser/DocumentParserFactory.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/MarkdownDocumentParser.java`|`ingestion/parser/MarkdownDocumentParser.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/ParsedDocument.java`|`ingestion/parser/ParsedDocument.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/ParseException.java`|`ingestion/parser/ParseException.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/ParserSource.java`|`ingestion/parser/ParserSource.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/SourceSpan.java`|`ingestion/parser/SourceSpan.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/parser/TxtDocumentParser.java`|`ingestion/parser/TxtDocumentParser.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/publish/IndexPublishMapper.java`|`ingestion/publish/IndexPublishMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/publish/IndexPublishRepository.java`|`ingestion/publish/IndexPublishRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/publish/IndexPublishService.java`|`ingestion/publish/IndexPublishService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/ClaimedTask.java`|`ingestion/task/ClaimedTask.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/ImportEvents.java`|`ingestion/task/ImportEvents.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/IngestionTaskProcessor.java`|`ingestion/task/IngestionTaskProcessor.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskExecutionContext.java`|`ingestion/task/TaskExecutionContext.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskExecutionException.java`|`ingestion/task/TaskExecutionException.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskFailure.java`|`ingestion/task/TaskFailure.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskMapper.java`|`ingestion/task/TaskMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskProcessingRepository.java`|`ingestion/task/TaskProcessingRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskProcessor.java`|`ingestion/task/TaskProcessor.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskRepository.java`|`ingestion/task/TaskRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskScheduler.java`|`ingestion/task/TaskScheduler.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskService.java`|`ingestion/task/TaskService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskStateMachine.java`|`ingestion/task/TaskStateMachine.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/TaskWorker.java`|`ingestion/task/TaskWorker.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/task/WorkerStatusLogger.java`|`ingestion/task/WorkerStatusLogger.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/text/UnicodeOffsets.java`|`ingestion/text/UnicodeOffsets.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/upload/DocumentImportController.java`|`ingestion/controller/DocumentImportController.java`|纯包迁移：统一业务 HTTP 入口|
|`ingestion/upload/DocumentImportRepository.java`|`ingestion/upload/DocumentImportRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/upload/DocumentImportService.java`|`ingestion/upload/DocumentImportService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/upload/ImportCapacityMapper.java`|`ingestion/upload/ImportCapacityMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/upload/ImportErrorCode.java`|`ingestion/upload/ImportErrorCode.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/upload/ImportTaskResponse.java`|`ingestion/dto/ImportTaskResponse.java`|纯包迁移：集中 API 请求响应协议|
|`ingestion/verification/EsIntegrityService.java`|`ingestion/verification/EsIntegrityService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/verification/IndexVerificationService.java`|`ingestion/verification/IndexVerificationService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/verification/PgIntegrityService.java`|`ingestion/verification/PgIntegrityService.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/verification/VerificationMapper.java`|`ingestion/verification/VerificationMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`ingestion/verification/VerificationRepository.java`|`ingestion/verification/VerificationRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`knowledge/CreateKnowledgeBaseRequest.java`|`knowledge/dto/CreateKnowledgeBaseRequest.java`|纯包迁移：集中 API 请求响应协议|
|`knowledge/KnowledgeBaseController.java`|`knowledge/controller/KnowledgeBaseController.java`|纯包迁移：统一业务 HTTP 入口|
|`knowledge/KnowledgeBaseRepository.java`|`knowledge/repository/KnowledgeBaseRepository.java`|纯包迁移：简单模块数据访问归位|
|`knowledge/KnowledgeBaseResponse.java`|`knowledge/dto/KnowledgeBaseResponse.java`|纯包迁移：集中 API 请求响应协议|
|`knowledge/KnowledgeBaseService.java`|`knowledge/service/KnowledgeBaseService.java`|纯包迁移：简单模块按职责组织|
|`KnowledgeAssistantApplication.java`|`KnowledgeAssistantApplication.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/api/RagAnswerRequest.java`|`rag/dto/RagAnswerRequest.java`|纯包迁移：集中 API 请求响应协议|
|`rag/api/RagController.java`|`rag/controller/RagController.java`|纯包迁移：统一业务 HTTP 入口|
|`rag/context/AssembledContext.java`|`rag/context/AssembledContext.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/context/ContextBuilder.java`|`rag/context/ContextBuilder.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/context/ContextProperties.java`|`rag/context/ContextProperties.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/context/SelectedEvidence.java`|`rag/context/SelectedEvidence.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/generation/AnswerGenerator.java`|`rag/generation/AnswerGenerator.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/generation/RawGenerationResult.java`|`rag/generation/RawGenerationResult.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/model/RagAnswer.java`|`rag/model/RagAnswer.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/model/RagCitation.java`|`rag/model/RagCitation.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/model/RagError.java`|`rag/model/RagError.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/model/RagStatus.java`|`rag/model/RagStatus.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/RagService.java`|`rag/RagService.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/validation/CitationValidator.java`|`rag/validation/CitationValidator.java`|保留：现有功能归属明确，避免目录对称化|
|`rag/validation/StructuredOutputParser.java`|`rag/validation/StructuredOutputParser.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/api/RetrievalController.java`|`retrieval/controller/RetrievalController.java`|纯包迁移：统一业务 HTTP 入口|
|`retrieval/api/RetrievalSearchRequest.java`|`retrieval/dto/RetrievalSearchRequest.java`|纯包迁移：集中 API 请求响应协议|
|`retrieval/api/RetrievalSearchResponse.java`|`retrieval/dto/RetrievalSearchResponse.java`|纯包迁移：集中 API 请求响应协议|
|`retrieval/embedding/QueryEmbeddingService.java`|`retrieval/embedding/QueryEmbeddingService.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/embedding/QueryVector.java`|`retrieval/embedding/QueryVector.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/fusion/RrfFusionService.java`|`retrieval/fusion/RrfFusionService.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/keyword/KeywordChunkMapper.java`|`retrieval/keyword/KeywordChunkMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/keyword/KeywordChunkRepository.java`|`retrieval/keyword/KeywordChunkRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/keyword/KeywordRetriever.java`|`retrieval/keyword/KeywordRetriever.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/keyword/KeywordSearchRepository.java`|`retrieval/keyword/KeywordSearchRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/model/RankedChunk.java`|`retrieval/model/RankedChunk.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/model/RetrievalContext.java`|`retrieval/model/RetrievalContext.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/model/RetrievalDiagnostics.java`|`retrieval/model/RetrievalDiagnostics.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/model/RetrievalParameters.java`|`retrieval/model/RetrievalParameters.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/model/RetrievalResult.java`|`retrieval/model/RetrievalResult.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/model/RetrievalStatus.java`|`retrieval/model/RetrievalStatus.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/model/RetrievalStrategy.java`|`retrieval/model/RetrievalStrategy.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/rerank/RerankService.java`|`retrieval/rerank/RerankService.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/RetrievalService.java`|`retrieval/RetrievalService.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/PublishedDocument.java`|`retrieval/snapshot/PublishedDocument.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/PublishedVersionSnapshot.java`|`retrieval/snapshot/PublishedVersionSnapshot.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/PublishedVersionSnapshotMapper.java`|`retrieval/snapshot/PublishedVersionSnapshotMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/PublishedVersionSnapshotRepository.java`|`retrieval/snapshot/PublishedVersionSnapshotRepository.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/PublishedVersionSnapshotService.java`|`retrieval/snapshot/PublishedVersionSnapshotService.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/PublishedVersionTerms.java`|`retrieval/snapshot/PublishedVersionTerms.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/SnapshotInvalidException.java`|`retrieval/snapshot/SnapshotInvalidException.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/snapshot/TermsLimitExceededException.java`|`retrieval/snapshot/TermsLimitExceededException.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/vector/VectorRetriever.java`|`retrieval/vector/VectorRetriever.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/vector/VectorSearchMapper.java`|`retrieval/vector/VectorSearchMapper.java`|保留：现有功能归属明确，避免目录对称化|
|`retrieval/vector/VectorSearchRepository.java`|`retrieval/vector/VectorSearchRepository.java`|保留：现有功能归属明确，避免目录对称化|

## HTTP 与 DTO 清单补充

上表中全部 Controller、Request、Response 均需移动、不重命名。API 不变：KnowledgeBaseController 为 GET/POST /v1/knowledge-bases、GET /v1/knowledge-bases/{id}，对应 CreateKnowledgeBaseRequest、KnowledgeBaseResponse；DocumentQueryController 为 GET /v1/knowledge-bases/{kbId}/documents 及 /{documentId}/versions/{versionId}/chunks/{chunkId}，对应 DocumentListResponse、DocumentSummaryResponse、CitationEvidenceResponse，无独立 Request；DocumentImportController 为 POST /v1/knowledge-bases/{kbId}/documents:import（multipart）及 GET /v1/import-tasks/{taskId}，对应 ImportTaskResponse；RetrievalController 为 POST /v1/retrieval/search，对应 RetrievalSearchRequest/Response；RagController 为 POST /v1/chat/answers，对应 RagAnswerRequest，返回 rag.model.RagAnswer。通用 Result 保留。Web UI V1 的分页列表、最新任务状态、引用证据校验都纳入，不新增前端能力。

## 数据库实际基线

V1 启用 vector；V2 建五表；V3 注释；V4 将 index_version/import_task 改名 document_index_version/document_import_task 并改约束和索引名；V5 为 document_chunk 新增 version INTEGER NOT NULL DEFAULT 0。没有数据库外键，归属由业务校验。document 的 (knowledge_base_id,content_hash) 唯一，chunk 的 (index_version_id,chunk_order) 唯一；embedding 为 vector(1024)。发布事实是 document.active_version_id，不能只用版本 status 替代。历史迁移全部只读。

## SQL 影响矩阵

以下覆盖全部 14 个 Java Mapper、9 个 XML。基础 Mapper 均继承 BaseMapper，无独立 XML；实际生成 SQL 还取决于调用处 Wrapper。专用 XML 路径相对 src/main/resources/mapper。

|Java Mapper（当前包）|XML|表与操作|建议|
|---|---|---|---|
|infrastructure.persistence.KnowledgeBaseMapper|无|knowledge_base 基础 CRUD|移 knowledge.mapper；单表容量锁合并候选|
|infrastructure.persistence.DocumentMapper|无|document CRUD、去重、计数、对象引用检查|移 document.mapper|
|infrastructure.persistence.IndexVersionMapper|无|document_index_version CRUD、expected_chunk_count 条件初始化|移 document.mapper|
|infrastructure.persistence.DocumentChunkMapper|无|document_chunk CRUD、按版本和顺序读取|移 document.mapper|
|infrastructure.persistence.ImportTaskMapper|无|document_import_task CRUD、按文档取首任务|移 ingestion.task.mapper|
|document.DocumentQueryMapper|document/DocumentQueryMapper.xml|document count/page/document；版本批量读；任务 DISTINCT ON 最新读；chunk 单条证据读|先移包保留；单表方法拆回基础 Mapper 为候选|
|ingestion.embedding.EmbeddingMapper|ingestion/EmbeddingMapper.xml|document/version FOR UPDATE；chunk 条件写 embedding 并 version+1|锁方法调整归属候选；写入保留专用|
|ingestion.task.TaskMapper|ingestion/TaskMapper.xml|任务锁定/领取/失败/恢复；document+version 归属查询|保留，租约与 fencing 不拆散|
|ingestion.publish.IndexPublishMapper|ingestion/IndexPublishMapper.xml|document 首次指针、version 发布、task 成功条件更新|保留跨表原子业务操作|
|ingestion.verification.VerificationMapper|ingestion/VerificationMapper.xml|version BUILDING→VERIFIED 条件更新|合并 IndexVersionMapper 候选，暂保留|
|ingestion.upload.ImportCapacityMapper|ingestion/ImportCapacityMapper.xml|knowledge_base FOR UPDATE|合并 KnowledgeBaseMapper 候选，暂保留|
|retrieval.vector.VectorSearchMapper|retrieval/vector/VectorSearchMapper.xml|chunk+document+version 联查、距离排序与 LIMIT|保留专用，禁止弱化快照成对过滤|
|retrieval.keyword.KeywordChunkMapper|retrieval/keyword/KeywordChunkMapper.xml|chunk LEFT JOIN document/version，ES 命中 PG 回填|保留专用，不能与普通 chunk 查询合并|
|retrieval.snapshot.PublishedVersionSnapshotMapper|retrieval/snapshot/PublishedVersionSnapshotMapper.xml|knowledge_base LEFT JOIN document/version，按 document id 排序|保留专用，空库与不存在语义不同|

### 按表修改字段时的检查入口

|表 / Entity / 基础 Mapper|所有额外 SQL 入口|必须连带检查的 Java 数据访问|
|---|---|---|
|knowledge_base / KnowledgeBase / KnowledgeBaseMapper|ImportCapacity、PublishedVersionSnapshot XML|KnowledgeBaseRepository、DocumentImportRepository|
|document / Document / DocumentMapper|DocumentQuery、Embedding、Task、IndexPublish、VectorSearch、KeywordChunk、PublishedVersionSnapshot XML|DocumentImportRepository、ChunkRepository、EmbeddingRepository、IndexPublishRepository|
|document_index_version / IndexVersion / IndexVersionMapper|DocumentQuery、Embedding、Task、IndexPublish、Verification、VectorSearch、KeywordChunk、PublishedVersionSnapshot XML|DocumentImportRepository、ChunkRepository、EmbeddingRepository、VerificationRepository、IndexPublishRepository|
|document_chunk / DocumentChunk / DocumentChunkMapper|DocumentQuery、Embedding、VectorSearch、KeywordChunk XML|ChunkRepository、EmbeddingRepository、PgIntegrityService、EsIndexService、引用与检索结果映射|
|document_import_task / ImportTask / ImportTaskMapper|DocumentQuery、Task、IndexPublish XML|DocumentImportRepository、TaskProcessingRepository、TaskRepository、IndexPublishRepository|

所有字段修改还应检查 V2/V3/V4/V5 的既有定义（不得改写）、新增迁移、实体映射注解、XML resultType/Row、DTO 转换及相关持久化测试。VectorTypeHandler/VectorSchema 也受向量字段变化影响。此矩阵是检查索引，不表示每次必须修改全部文件。

### 重复与交叉

DocumentQuery 的 count 与 DocumentImportRepository.countDocuments 都是同库计数；document/chunk 单条投影与 BaseMapper selectById 部分重叠，但列集合不同。tasks 取最新任务，而上传去重取最早任务，不能统一排序。EmbeddingMapper 的锁被验证和发布复用，是职责命名交叉，不是重复 SQL；建议未来将 lockDocument/lockVersion 原样归各实体 Mapper，不新增通用锁层。Verification 与 ImportCapacity 是单表操作，可合并但收益有限。所有候选属于 SQL 归属调整，必须独立于纯包迁移审查，保留原 WHERE、排序、行数检查与调用顺序。

## Repository 逐项审计

|Repository|实际职责与结论|
|---|---|
|KnowledgeBaseRepository|创建实体及列表顺序封装，保留；单条读取虽简单不单独拆除|
|DocumentQueryRepository|大部分转发，但批量空集合保护有价值；先保留迁包，删除需连同 Service 调用和空集合协议评审|
|DocumentImportRepository|跨三表创建、去重、容量锁、对象引用检查；有封装价值，同时含容量业务规则与对象前缀校验，保留；规则上移只是待确认候选|
|TaskProcessingRepository|单个 selectById 转发，删除候选；可由处理器直接使用基础 Mapper，但须单独确认项目调用风格|
|TaskRepository|领取状态、归属、fencing、重试与恢复，业务和数据判断交织；保留，不以体积为理由拆分|
|ChunkRepository|跨表读取、顺序查询、数量条件初始化与插入，保留；Embedding 复用其读取形成跨功能依赖，暂不新增 facade|
|EmbeddingRepository|版本归属、向量与数量校验、幂等和乐观锁、批次事务，封装价值明确；业务校验偏重，暂不拆，拆除会触及事务及校验时点|
|EsIndexRepository|ES mapping 检查、bulk create、冲突比对及异常转换，移共享技术包；不改索引结构|
|VerificationRepository|按序锁及状态条件更新，虽短仍有一致性语义，保留|
|IndexPublishRepository|文档→版本→任务锁与三表发布、行数检查，必须保留原子语义|
|VectorSearchRepository|较薄的专用查询边界，保留；删除收益低且暴露 Row 类型并不能改善业务走查|
|KeywordSearchRepository|ES 查询与协议检查，保留 retrieval.keyword，属于通道特定访问|
|KeywordChunkRepository|PG 回填、ES/PG 数据归属与内容一致性校验，保留|
|PublishedVersionSnapshotRepository|快照构造及归属一致性检查，保留|

## 核心调用链与边界

1. KnowledgeBaseController → KnowledgeBaseService → KnowledgeBaseRepository → KnowledgeBaseMapper。HTTP 参数与响应转换留入口，实体不直接暴露。
2. DocumentQueryController → DocumentQueryService → KnowledgeBaseService / DocumentQueryRepository → DocumentQueryMapper。列表按创建时间、id 倒序，批量装配版本和最新任务；证据读取校验库、文档、版本、Chunk 归属及发布事实。两个读取入口使用只读 REPEATABLE_READ，不能在拆 Mapper 时降低隔离。
3. DocumentImportController → DocumentImportService → OriginalFileStore / DocumentImportRepository。HTTP 完成文件校验、对象处理、去重及持久化任务，返回 ImportTaskResponse；任务状态查询不触发索引处理。上传数据库事务通过现有编程式事务边界管理，不能移动整个方法进长事务。
4. TaskScheduler / TaskWorker → TaskService → TaskRepository → TaskMapper，领取提交后再进入 IngestionTaskProcessor。处理器禁止已有事务，读原对象并核对摘要 → DocumentParserFactory → ChunkService.initialize → EmbeddingService.embed → EsIndexService.index → IndexVerificationService（PG/ES 验证）→ IndexPublishService.publish。Chunk 初始化、Embedding 批次写入、PG 验证与发布保持各自边界；模型/ES 网络请求不纳入整个流水线事务。失败经 Worker/TaskService 记录并按原策略重试恢复。发布锁为 document→version→task；领取只锁 task，归属快照不反向加锁。HTTP 与 Worker 以持久化任务、租约和 claim_epoch 为界，不能改成请求同步执行。
5. RetrievalController → RetrievalService → PublishedVersionSnapshotService/Repository；VECTOR 为 QueryEmbeddingService→VectorRetriever/Repository；BM25 为 KeywordRetriever→KeywordSearchRepository→KeywordChunkRepository；HYBRID_RRF 融合两路，HYBRID_RERANK 在融合后 RerankService。共享快照和查询向量，finalTopK 在末端应用，失败不隐式降级。
6. RagController → RagService → RetrievalService → ContextBuilder → AnswerGenerator → StructuredOutputParser → CitationValidator → RagAnswer。引用校验针对已选证据，DocumentQuery 的证据接口是后续 HTTP 读取，两者不可混为一个事务。

## 跨模块依赖与成本

实体归 document 后 ingestion/retrieval 对 document 模型的依赖是显式数据契约，不要求 Repository 全部经过 document Service。Retrieval 的 KeywordSearchRepository 依赖 ingestion.indexing.EsChunkDocument、EsIndexRepository、EsIndexService，KeywordChunkRepository 也直接引用 EsChunkDocument。移动共享 DTO/字段/技术访问后仍须处理 EsIndexService 的索引名引用；建议 R4 评审将既有常量原样归共享 ES 配置或协议类，不创建通用搜索接口。成本为 import、测试引用、SpEL 全限定名和配置绑定检查。EsIndexService 的 @Value 表达式含自身全限定名，未来移动时必须同步；logging、META-INF/spring.factories 等字符串引用也须搜索。

## R1～R5 顺序、验证及回滚

|阶段|实施范围|验证要求|回滚|
|---|---|---|---|
|R1|Controller/DTO 及简单模块 Service/Repository 纯迁包|编译、相关 KnowledgeBase/DocumentQuery/Import/Retrieval/Rag API 测试；核对所有路由及序列化|独立提交反向还原路径/import|
|R2|Entity/基础 Mapper 纯迁包，不改类名；XML resultType 同步|编译、MyBatis 映射、相关持久化与查询测试；核查 Mapper 扫描和 typeHandler 字符串|独立提交回退；无数据库迁移|
|R3|仅在确认后调整锁、单表查询与小 Mapper 归属；不删业务校验|相关数据库集成测试，重点锁顺序、租约竞争、Embedding 幂等、首次发布与证据快照；逐句比对 SQL|整体回退该阶段，避免一半旧一半新 namespace|
|R4|共享 ES 类及业务 Properties 迁移；索引名解耦需确认|配置绑定、ES 协议/完整性与检索测试；核对 mapping、索引名、条件 Bean 和默认值|独立回退配置/import/字符串引用|
|R5|经确认才删除薄 Repository 或调整业务校验职责；收尾|受影响测试及合并前全量验证，在线/容器测试须另行授权；复核 API、SQL、事务无行为变化|按独立职责提交回退，不改 Flyway|

每阶段开工先检查分支和用户改动；按代码规范执行必要格式、编译和影响范围测试。以上是未来验证计划，不是本轮通过记录。回滚使用新增 revert 提交或恢复本阶段文件，不清理用户文件、不改写历史。

## 待人工确认

- document 是否作为版本与 Chunk 实体的所有者：本稿推荐，替代方案继续集中 persistence 更少移动但定位仍差；不创建新 lifecycle 模块。
- R3 是否接受单表锁/Verification/Capacity 的 Mapper 调整，以及 DocumentQuery 单表投影是否迁回基础 Mapper：收益是按表定位，成本是 XML 与所有调用点联动，暂保持。
- 是否删除 TaskProcessingRepository、是否保留 DocumentQueryRepository 的空集合封装：不作为纯迁包前提。
- R4 共享索引名放配置还是协议常量；以及 R5 是否值得拆 Embedding/上传中的业务校验：未确认前保持现状。

## 本轮验证记录

只做源码、迁移及 Markdown 静态审阅和路径清单检查、git diff --check。未运行 Maven、模型请求或 Docker；未证明运行期映射、并发与 API 回归通过。未提交、Push 或 Merge，无本轮 Commit SHA。
