# Knowledge Assistant 项目开发指引

## 项目定位与当前状态

Knowledge Assistant 是基于 Java / Spring AI 的个人知识管理与 RAG 问答项目。M5 正在准备公开仓库，当前任务分支为 `feature/m5-public-readiness`。已实现账号与知识库隔离、文档导入、四策略检索、单轮与多轮 RAG、静态 Web 页面及评估工具。M4 有冻结离线结果与历史 LIVE 联调记录。MinIO 分发、模型调用授权和新环境部署待 M5-02 验证。

遵守已确认的 V1 P0 范围和当前任务授权。跨会话长期记忆、Agent、Workflow、MCP、Skills、Harness 及后续 P1/P2 功能仍在当前实现范围之外。

## 当前文档入口

- [项目总览](README.md)
- [开始使用](docs/getting-started.md)
- [当前架构](docs/architecture.md)
- [核心功能](docs/core-features.md)
- [M4 评估](docs/evaluation.md)

当前实现与版本以源码、配置、Flyway 和测试为准。当前 pom.xml 使用 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2。文档冲突应先报告，例如历史架构规格的 Spring Boot 3.x 与当前 pom.xml 不同。保护冻结评估输入和历史正文。

[历史资料](docs/archive/README.md)供核对早期产品需求、架构决策、开发顺序及阶段验证。历史[代码风格](docs/archive/v1-development/engineering/code-style.md)作为工程规范参考，历史模型报告中的默认 clean verify 说明按下述影响范围规则处理。

## 开发约束

- 严守当前任务范围，优先简单、清晰、可维护的实现，避免过度设计。
- Java 局部变量默认显式类型，var 仅在类型显而易见且符合 Code Style 时使用。
- 主流程和重要业务判断使用准确、通俗的中文注释。
- 不提交密钥、Token 或其他敏感信息，不在日志或异常中输出敏感原文。
- 不修改已经执行过的 Flyway Migration；数据库演进新增迁移。
- 不擅自升级依赖版本。

## 测试与验证

- 默认只执行本次修改影响范围内的测试，不机械执行 mvn clean verify。
- Milestone 验收、重大依赖或架构调整、合并前等关键节点才执行全量测试。
- 纯文档修改通常只需审阅文档、完整 diff 和 git diff --check，无需执行 Maven 格式化、编译或测试。
- 不擅自执行真实供应商 API 请求，不擅自启动 Docker 基础设施。
- 测试报告准确说明实际执行范围、结果及未验证事项，不得将未执行的验证标记为通过。

## Git 工作流

- 在当前任务指定分支上开发。
- 开始修改前检查工作区状态，不覆盖或清理用户已有的未提交修改。
- 仅提交任务相关文件，保持提交粒度清晰。
- Git Commit Message 默认使用中文描述变更意图，保留 Conventional Commits 类型前缀及必要英文技术术语；详细规则见 Code Style 的 Git Commit Message 章节，无需改写已有提交历史。
- 不擅自创建 PR、合并 main、推送远端或改写历史。

## 任务完成报告

简洁说明修改文件及主要变更、实际执行的测试和检查、未执行的验证及原因、已知限制，以及 Commit SHA。
