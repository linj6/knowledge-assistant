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

## 来源与公开许可

上述八篇 Markdown 均由本项目维护者使用 ChatGPT 辅助整理和归档，属于维护者的技术学习笔记和实验资料。其中五篇归档自 `backend-interview-lab`，三篇归档自 `ai-application-lab`，各篇原仓库及路径见上表。

维护者有权许可的内容按本仓库根目录的 [MIT 许可证](../../LICENSE) 公开，可依该许可证使用、复制、修改和再分发，包括用于公开评估和离线复算。复制或分发受 MIT 许可的内容或其中的实质性部分时，须随附适用的版权声明和 MIT 许可声明。

第三方引用、链接所指向的内容以及其他受独立许可约束的材料，仍适用其原有权利归属和许可条件。使用或再分发这些材料时，应遵守其适用条件并保留相关版权及许可证声明。
