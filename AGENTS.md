# Knowledge Assistant 项目开发指引

## 项目定位

Knowledge Assistant 是基于 Java / Spring AI 的个人知识管理与 RAG 问答项目，采用分阶段开发。遵守已确认的 V1 P0 范围和当前任务授权，不提前实现后续里程碑或 P1/P2、Memory、Agent、Web UI。

## 技术栈

当前 pom.xml 使用 Java 21、Spring Boot 4.1.2、Spring AI 2.0.2；项目采用 PostgreSQL / pgvector、Elasticsearch / IK、MinIO、Flyway、Maven、JUnit。基础设施具体版本与验证状态应查阅仓库记录，不推测版本或引入新技术。

## 权威文档

- 产品需求：[Product Spec](docs/product/product-spec.md)。
- 架构决策：[Architecture Spec](docs/architecture/architecture.md)。
- 开发顺序：[Implementation Plan](docs/plans/implementation-plan.md)。
- 具体代码风格：[Code Style](docs/engineering/code-style.md)。
- 单次任务模板：[Codex Task Template](docs/plans/codex-task-template.md)。
- 模型能力及验证边界：[Model Capability Report](docs/engineering/model-capability-report.md)。

单次任务以用户当前明确授权的范围为准。文档冲突或与实际代码版本不一致时，先报告冲突，不擅自修改冻结文档。已知差异：Architecture Spec 的 Spring Boot 3.x 与当前 pom.xml 的 4.1.2 不一致；模型报告的默认 clean verify 说明不作为每次任务的验证要求，按下述影响范围执行。

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

- 在当前任务指定分支上开发；M0 当前使用 feature/m0-foundation。
- 开始修改前检查工作区状态，不覆盖或清理用户已有的未提交修改。
- 仅提交任务相关文件，保持提交粒度清晰。
- Git Commit Message 默认使用中文描述变更意图，保留 Conventional Commits 类型前缀及必要英文技术术语；详细规则见 Code Style 的 Git Commit Message 章节，无需改写已有提交历史。
- 不擅自创建 PR、合并 main、推送远端或改写历史。

## 任务完成报告

简洁说明修改文件及主要变更、实际执行的测试和检查、未执行的验证及原因、已知限制，以及 Commit SHA。
