# 代码风格与验证

## Java

- [pom.xml](../pom.xml) 使用 Spotless Maven Plugin 2.43.0 和 Palantir Java Format 2.50.0，覆盖 `src/main/java/**/*.java` 与 `src/test/java/**/*.java`，并移除未使用的 import。新增或修改的 import 使用明确的类名或静态成员名，避免通配符。
- 局部变量默认使用显式类型。模型调用与响应、业务实体、DTO、配置对象、重要的泛型集合和返回类型不直观的表达式应明确声明类型。只有类型一目了然且显式声明明显冗余时才使用 `var`。
- 主流程及重要业务判断使用准确、通俗的中文注释，说明约束和判断依据，随逻辑更新同步维护。

## YAML、SQL、XML 与 Markdown

- YAML 使用两空格缩进，缩进禁用 Tab。保留配置层级及值类型，凭据通过环境变量或本地配置提供。
- SQL 按语句和子句清晰分行，关键字风格保持一致。格式调整应保留查询条件、排序和事务语义。已执行的 Flyway 迁移保持原样，数据库演进使用新的迁移。
- Maven POM 由 Spotless 使用 Eclipse WTP XML Formatter 4.21.0 格式化，采用四空格缩进。其他 XML 沿用文件内风格，MyBatis XML 按语句组织并使用 `#{参数名}` 绑定业务输入。
- Markdown 使用清楚的标题层级和带语言标记的围栏代码块。仓库内文档使用相对链接，检查文件路径和锚点，清理多余行尾空白。

## 原生前端

[package.json](../package.json) 固定 Prettier 3.6.2，使用 `npm ci` 按 [锁文件](../package-lock.json) 安装。[格式配置](../.prettierrc.json) 使用四空格缩进、JavaScript 单引号与分号、120 列打印宽度及 LF 行尾。[忽略配置](../.prettierignore) 排除 `node_modules/`、`target/`、`dist/` 和 `build/`。

- `npm run format:web:check` 检查静态 HTML、CSS、JavaScript 和 `src/test/js/**/*.mjs`。
- `npm run format:web` 会写入上述整个范围。只需整理本次变更时，可用 `npx prettier --write` 显式传入相关文件路径。

## 按变更范围验证

- Spotless 的 `check` 绑定在 Maven `validate` 阶段，仅检查格式。涉及 Java 或 POM 时，可用 `mvn spotless:check` 检查。格式修正限于本次涉及的文件。全量 `mvn spotless:apply` 仅用于任务明确包含全量格式治理时。检查若暴露无关历史格式问题，记录受影响文件与验证结果，另行处理。
- 默认执行受影响的测试。里程碑验收、重大架构或依赖调整、合并前等关键节点才执行完整回归。报告实际执行的验证、结果及未验证事项。
- 纯文档修改审阅全文及完整 diff、校验相对链接并执行 `git diff --check`。保护冻结评估输入、历史记录和实验结果。

## Git 提交信息

使用 Conventional Commits 类型前缀和中文描述，例如 `docs: 补充现行工程规范`。保留必要的英文技术术语，简述变更意图。提交只包含当前任务涉及的文件。
