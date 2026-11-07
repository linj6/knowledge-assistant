# Codex CLI — 单任务执行模板

> 将方括号占位内容替换后发送给 Codex CLI；不要一次执行多个任务。

## 背景与目标

仓库：linj6/knowledge-assistant。当前分支：feature/m0-foundation（M0 七项共用）。任务：[Task ID / 名称]。目标：[从 Implementation Plan 复制]。

## 权威规范

先阅读 `docs/product/product-spec.md`、`docs/architecture/architecture.md`、`docs/plans/implementation-plan.md` 对应部分。发生冲突或发现阻断缺陷先报告，不擅自修改已冻结设计。

## 实现范围

[明确要做的功能、文件/模块、前置依赖]

## 禁止修改与非目标

[不属于本任务的功能]。不得修改无关文件、提前实现 P1/P2、Memory、Agent、Web UI；不得添加无必要的抽象、设计模式、依赖或空占位类。

## 代码规范

Java21、Spring Boot 4.1.2、Spring AI 2.0.2；简单、易读、模块职责清晰；主流程及重要业务判断必须有准确通俗的中文注释；配置不含密钥；不得记录敏感原文。优先复用已有测试基础设施。

## 验收与测试

[明确成功行为、错误行为、边界情况]

测试命令：[具体 Maven / Docker / SQL / API 命令]。

真实基础设施功能必须提供实际集成测试；外部服务不可用时标记未验证，不得声称通过。在线模型测试单独执行，不应成为默认离线构建的依赖。

## 验证证据与交付

提供：变更文件清单、关键实现解释、实际执行命令及输出摘要、成功/失败数量、测试证据位置、已知风险和未完成事项；不得伪造结果。

## Git 约束

M0 全部在 `feature/m0-foundation` 开发；开始前检查 `git status`，保护用户未提交改动；只提交本任务相关文件。完成测试与自检后提交一个或多个小 Commit，报告 SHA；不自行合并 main 或创建下一任务代码。人工评审后继续下一任务。

## 提交前格式检查

遵守 docs/engineering/code-style.md。依次执行 mvn spotless:apply、mvn spotless:check、mvn clean verify、git diff --check；检查 diff 后提交，报告实际测试结果。按任务需要运行既定 DatabaseIT，不跳过格式检查，不修改已执行 Flyway V1 或冻结设计。
