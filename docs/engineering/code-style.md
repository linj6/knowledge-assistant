# 代码格式规范

## 固定工具与范围

Spotless Maven Plugin 2.43.0、Palantir Java Format 2.50.0、removeUnusedImports、Eclipse WTP XML Formatter 4.21.0。复用 [Interview AI 配置](https://github.com/linj6/interview-ai/blob/main/pom.xml)。在 Java 21.0.10 / Maven 3.9.16 / Spring Boot 4.1.2 工程实测；不调整 Java 或 Spring Boot 版本。格式器运行于构建插件，不引入应用运行时依赖。

自动处理 src/main/java/**/*.java、src/test/java/**/*.java 和 pom.xml；其他 XML 须先评估用途。

## 基本规则

- Java 排版以 Palantir 输出为准，不叠加另一套手工排版规则；自动移除未使用 import。新增或修改 import 使用明确类名和静态成员名，避免通配符；历史通配符逐步整理，此项由评审约束，格式器不负责展开。
- 保留有价值的中文注释；主流程和重要判断使用准确通俗的中文，修改逻辑时同步维护注释。
- Maven XML 使用 WTP，四空格缩进，依赖各字段清晰分行。其他 XML 不在本次自动范围。
- YAML 使用两空格缩进，不用 Tab，不提交密钥，保留配置含义。
- SQL 清晰分行、关键字风格一致；不得借格式化改变语义。已执行的 Flyway V1 不修改，后续数据库演进新增迁移。
- Markdown 使用清晰标题、围栏代码块和有效相对链接，避免行尾空白。

## 命令与 Codex CLI 流程

开始前检查 git status、当前分支及任务范围，阅读相关规范。完成修改后按顺序执行：

```powershell
mvn spotless:apply
mvn spotless:check
mvn -DskipTests test-compile
# 按影响范围执行对应测试；仅在关键节点执行全量测试
git diff --check
```

validate 阶段绑定 check，格式不合规构建失败；正常构建只检查，不自动修改源文件。失败须修复后重新验证，不以跳过检查交付。按任务需要执行既定 DatabaseIT；报告实际测试数量、结果及未验证事项。提交前检查完整 diff，确认字符串、SQL、异常协议、断言和中文注释未被意外修改，保护冻结设计；仅提交任务范围内容。

## 评审边界

自动格式化不能证明业务正确、SQL安全、API兼容或测试充分，不能替代代码评审。字符串或 SQL 内容变化必须单独说明并验证。工具升级应独立评估兼容性及全量 diff，固定版本后运行上述命令。

## Java 局部变量类型

默认使用显式类型，仅在类型非常直观且显式声明明显冗余时允许 `var`，不要为了消除所有 `var` 而牺牲代码简洁性。

必须使用显式类型：

1. Spring AI 模型调用及响应对象。
2. 业务实体、DTO、配置对象和异常对象。
3. 方法调用返回值类型不直观的情况。
4. 泛型集合及其重要中间结果。
5. 需要阅读者理解具体 API 类型的局部变量。

允许使用 `var`：

1. 右侧直接 `new ConcreteType(...)`，类型非常明确（上述必须显式声明的场景除外）。
2. 简单循环变量，且类型不影响理解。
3. 显式类型明显冗余、不会增加阅读成本的局部变量。

每次修改默认只执行受影响的测试；Milestone 验收、重大架构或依赖调整、合并前等关键节点才执行全量测试。仅执行局部测试时，报告必须准确注明验证范围。
