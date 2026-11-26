# 核心功能

## 用户权限与知识库

用户可以注册、登录、查询登录态及登出。业务接口使用 Redis 支持的 Session Cookie，写请求受 CSRF 校验。知识库归属当前账号，文档证据、导入任务、检索和会话按归属校验。主站提供「知识库」「检索」「问答」页面。评估 API 当前由安全配置拒绝访问。[安全配置](../src/main/java/com/lnjecit/knowledge/assistant/infrastructure/web/SecurityConfiguration.java) · [知识库接口](../src/main/java/com/lnjecit/knowledge/assistant/knowledge/controller/KnowledgeBaseController.java)

## 文档导入

支持 UTF-8 Markdown / TXT，单文件上限 2 MiB。同一知识库内重复内容复用已有导入结果，新任务返回 HTTP 202，重复内容返回 200。页面可查询任务进度及文档列表。切分支持固定代码点与 Markdown 结构感知，当前配置默认固定切分，长度 800、重叠 120。发布后才能参与检索。[导入接口](../src/main/java/com/lnjecit/knowledge/assistant/ingestion/controller/DocumentImportController.java) · [默认配置](../src/main/resources/application.yml)

## 四种检索策略

| 策略 | 行为 |
| --- | --- |
| `VECTOR` | 对问题生成 Embedding，在 pgvector 检索相近 Chunk |
| `BM25` | 在 Elasticsearch / IK 中进行关键词检索 |
| `HYBRID_RRF` | 融合向量与 BM25 候选，按各路排名累加贡献 |
| `HYBRID_RERANK` | 以 RRF 候选为输入，调用配置的重排模型 |

检索页可对比各路结果。四种策略已实现，M4 冻结评估范围为前三种。[检索编排](../src/main/java/com/lnjecit/knowledge/assistant/retrieval/RetrievalService.java) · [M4 评估](evaluation.md)

## 引用与多轮会话

单轮入口为 `POST /api/v1/chat/answers`。主站问答使用绑定一个知识库的会话。后端从检索候选构建上下文，校验生成结果中的引用身份，证据不足时可返回拒答。引用可打开对应版本的 Chunk；引用校验覆盖身份与范围，正文是否支持结论需另行判断。同一会话可参考有预算限制的近期已回答轮次。[RAG 源码](../src/main/java/com/lnjecit/knowledge/assistant/rag) · [历史选择](../src/main/java/com/lnjecit/knowledge/assistant/conversation/service/TurnHistoryService.java)

## 取消与恢复

同一会话只允许一个活动轮次。同一轮次 ID 和问题的重复提交读取已有状态。页面轮询查询轮次，取消会请求中断处理，再通过服务端状态确认结果。重进页面可读取已保存消息，启动时会收敛上次进程中断留下的活动轮次。生成中断后可重新发起提问。导入恢复独立于对话，Worker 按任务租约与重试规则处理遗留任务。[轮次服务](../src/main/java/com/lnjecit/knowledge/assistant/conversation/service/TurnService.java) · [轮次执行器](../src/main/java/com/lnjecit/knowledge/assistant/conversation/service/TurnExecutor.java) · [导入任务](../src/main/java/com/lnjecit/knowledge/assistant/ingestion/task)

## 长期记忆

记忆总开关按账号保存，新老账号默认关闭。在「记忆」页可手动新增、编辑、启用、停用和删除记忆。每个账号最多保存 50 条（含停用条目），单条正文最多 500 个 Unicode 码点。当前仅支持显式手动管理。

仅多轮会话问答召回记忆。已启用的主题型记忆在当前问题包含任一关键词时命中，匹配忽略大小写。已启用的全局型记忆直接进入候选。命中的主题型优先，组内按更新时间和 ID 降序。默认每轮最多选取 10 条、1,000 估算 Token，生成阶段还会按完整请求 12,000 估算 Token（含 1,024 输出预留）从末尾裁剪记忆。

Memory Trace 记录实际注入的条数、ID 和类型，成功且可回答的结果可在当轮和历史消息中查看。记忆用于个性化，知识库引用与拒答仍以检索证据为准。删除记忆只影响后续召回，已生成的历史回答仍保留。开启后，命中并纳入请求的记忆可能发送给线上模型。
