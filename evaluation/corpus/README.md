# M4 冻结评估语料

本目录保存 M4 实验使用的八篇 Markdown 原文。来源清单来自 [M4 样本列表](../../docs/engineering/m4-01-samples.txt)，此处保留原仓库名、仓库内路径和原始字节。

| 来源仓库 | 原文 |
| --- | --- |
| backend-interview-lab | [spring/notes/04-transaction-boundaries-and-propagation.md](backend-interview-lab/spring/notes/04-transaction-boundaries-and-propagation.md) |
| backend-interview-lab | [mysql/notes/01-index-and-sql.md](backend-interview-lab/mysql/notes/01-index-and-sql.md) |
| backend-interview-lab | [rabbitmq/labs/06-prefetch-throughput/evidence/results.md](backend-interview-lab/rabbitmq/labs/06-prefetch-throughput/evidence/results.md) |
| ai-application-lab | [03-llm-api-function-calling-spring-ai/notes/05-spring-ai-model-client-and-tools.md](ai-application-lab/03-llm-api-function-calling-spring-ai/notes/05-spring-ai-model-client-and-tools.md) |
| ai-application-lab | [04-embedding-vector-search-rag/notes/04-chunking-and-context.md](ai-application-lab/04-embedding-vector-search-rag/notes/04-chunking-and-context.md) |
| ai-application-lab | [04-embedding-vector-search-rag/labs/04-hybrid-retrieval/README.md](ai-application-lab/04-embedding-vector-search-rag/labs/04-hybrid-retrieval/README.md) |
| backend-interview-lab | [rabbitmq/notes/05-ordered-messages.md](backend-interview-lab/rabbitmq/notes/05-ordered-messages.md) |
| backend-interview-lab | [juc/notes/06-completable-future.md](backend-interview-lab/juc/notes/06-completable-future.md) |

从仓库根目录运行 [M4 离线复算](../../docs/evaluation.md)。冻结记录中的 `../<原仓库名>/...` 仍作为来源身份，读取时映射到本目录并校验 SHA-256。Git 对八篇原文关闭换行转换。原文、Gold、候选、标签、指标和指纹应保持冻结，语料变更需要独立的评估版本。原文中的部分链接指向来源仓库其他文件，离线复算只读取上述清单。

## 发布前需确认的版权事项

本地 `ai-application-lab` 根目录有 MIT 许可证，`backend-interview-lab` 未发现 Git 跟踪的许可证。公开前需向权利人确认八篇原文的再分发授权及应随附的版权、许可声明，并核对原文中的第三方文档引用、代码示例和实验素材。当前副本用于冻结实验复算。复制原文不构成新的授权。
