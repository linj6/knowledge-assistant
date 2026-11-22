# M1-05 Chunk 切分与稳定 ID 验证

## 协议与来源

DocumentSplitter / FixedCodePointSplitter 使用 IndexVersion.chunkingConfig 持久化快照，不读取 application.yml。FIXED_CODE_POINT v1 当前快照为 800/120，步长 680；从 0 开始，窗口覆盖 EOF 即停止，不生成完全覆盖的尾部窗口。切分结果不含数据库 ID。

复用 ParserSource.boundaries，一次构建码点到 UTF-16 边界表。范围为原文 Unicode 码点 [start,end)，BOM、空白、Markdown 标记和换行原样保留；CRLF 占两位，Emoji 与补充平面字符占一位。时间及临时边界表空间为 O(N)，输出正文包含重叠区域的副本；没有逐 Chunk 从头扫描。标题路径取起点所属 Fragment，跨章节不改变归属，不重新解析 Markdown。

heading_path JSON 为 {"version":1,"headings":[{"text":"标题","level":1,"span":{"start":2,"end":4}}]}；空路径使用空数组，完整保留 Parser Heading 元数据。重试按反序列化后的协议值比较，不依赖 JSON 字段顺序。

## 持久化与稳定性

ChunkService 定义事务边界，ChunkRepository 封装 Mapper。首次插入保留 Entity 的 Snowflake ASSIGN_ID；初始集合与 expected_chunk_count 原子提交。已有数量非零时必须与确定性结果及实际记录数相同；已有记录逐项检查顺序、正文、来源位置、标题路径及 KB/Document/IndexVersion 归属，不执行覆盖。

数据库既有 (index_version_id,chunk_order) 唯一约束负责拒绝重复插入；失败事务回滚。只有成功提交的 ID 才可作为后续重试的稳定 ID，回滚或外层事务尚未提交的 ID 不保证保留。expected_chunk_count=0 且已有记录视为不完整状态并明确失败。

检查 Document 发布指针、IndexVersion.publishedAt 和 PUBLISHED 状态，禁止已发布文档重写。模型、维度、配置快照不更新。没有改动 Migration V1～V4、Parser 或上传接口。

## 实际验证

执行 spotless:apply、spotless:check、-DskipTests test-compile 成功。执行 DocumentSplitterTests、DocumentParserTests、ChunkPersistenceIT 共 30 项通过，0 失败/错误/跳过。真实 Testcontainers 使用仓库固定 pgvector 0.8.0 / PG17 镜像，实际 PostgreSQL 17.6，迁移 V1～V4 成功；验证已提交 ID 重用、不一致失败、数量、外层事务回滚、唯一约束与发布指针拒绝。

切分参数测试覆盖 TXT/MD、中文/Emoji/补充平面、混合换行、边界 679/680/799/800/801/1360/1480/1481、200000 码点、多次确定性、来源精确回取、标题起点归属及代码围栏。增加标题 JSON 往返测试，并单独以 -Xmx512m 执行切分测试。首次长度 1 测试仅包含 BOM 导致 NO_CONTENT，修正有效正文样本后通过，不修改 Parser 协议。

## 边界

未实现并发写入互斥、发布竞态互斥、Worker/fencing；唯一约束和数量条件更新不代表并发重试完全安全，并发冲突可能明确失败，留待 M1-09。未执行全量测试、模型、MinIO、ES 或后续索引阶段。200000 码点测试是有限资源正确性检查，不是生产峰值内存或吞吐承诺；未做 profiler。冻结架构 Boot 3.x 与 pom 4.1.2 已知差异保持原样。

补充实际结果：mvn "-Dtest=DocumentSplitterTests" "-DargLine=-Xmx512m" test 共 11 项通过，0 失败/错误/跳过，包含标题 JSON 往返。git diff --check 通过。

## M1-05 评审修正（基线 552407f）

按职责移动至 ingestion.upload、ingestion.parser、ingestion.chunk；ParserSource 仍为包内类，仅解码逻辑留在 Parser。码点边界表原样提取为 ingestion.text.UnicodeOffsets，供 ParsedDocument 与 Splitter 复用，严格 UTF-8 和原文范围协议不变。没有新增 Worker/Embedding/ES/Publication 包。

ChunkingStrategy 目前仅 FIXED_CODE_POINT，ChunkingConfig 和配置绑定改用枚举。JSON 仍保存同名字符串，旧数据库快照可直接读取，未知策略拒绝；application.yml 值未修改。RequestIdContext 统一私有 MDC Key，Filter 设置/清理、Result 读取、异常日志读取共同复用，Header、校验规则、响应字段和脱敏保持不变。

ChunkService 只允许 BUILDING 初始化及一致性重试；VERIFIED/PUBLISHED/FAILED 均拒绝。Document.activeVersionId 与 publishedAt 防护保留，不实现 FAILED 恢复。现有数据库 VERIFIED/PUBLISHED 必须具有正数量的约束保持不变，状态拒绝测试使用已初始化版本。

原文归属边界：initialize(documentId,versionId,parsed) 校验持久化对象之间的归属，但不能证明任意调用方提供的 parsed 来自 Document 原文件。后续 ProcessingService 必须先按 Document.objectKey 在数据库事务之外读取原始字节，以与上传一致的 SHA-256 规则核验 Document.contentHash，再严格解析并调用初始化；失败必须终止，不得把其他原文绑定至该版本。本次增加方法说明，不新增对象存储请求或完整编排。

验证：mvn spotless:apply、mvn spotless:check、mvn -DskipTests test-compile 均成功。受影响测试命令：mvn "-Dtest=ChunkingStrategyTests,DocumentParserTests,DocumentSplitterTests,CommonMarkFeasibilityTests,ImportTests,ImportApiTests,ApiBaselineTests,BusinessExceptionTests,ChunkPersistenceIT,ImportPersistenceTests" test，共 53 项通过，0 失败/错误/跳过。包含真实 PostgreSQL 17.6/Testcontainers、V1～V4 迁移、初始化及 ID 重试、事务回滚、唯一约束、三种非 BUILDING 状态拒绝；上传集成使用 Fake 存储，不访问真实 MinIO。保留 TXT/Markdown/Factory/Unicode 回归及请求响应/MDC 清理测试，新增旧快照和枚举 JSON 往返、真实 YAML 绑定及非法策略测试。

重构过程中首次脚本遇到本机默认编码读取失败、首次编译暴露缺失 import，修正后重新格式化及编译通过。未修改迁移、依赖、冻结架构或 API。未执行全量测试、在线模型、MinIO、ES；并发互斥和原文来源认证仍是上述明确边界，不宣称 M1-09 已完成。

补充：mvn spotless:apply 后执行 mvn "-Dtest=RequestIdContextTests" test，1 项通过，0 失败/错误/跳过；证明过滤链抛出 ServletException 时上下文与底层 MDC 均清理，链内 Result.requestId 与响应 Header 一致。该构建同时通过 Spotless 检查及测试编译。git diff --check 和 git diff --cached --check 通过。
