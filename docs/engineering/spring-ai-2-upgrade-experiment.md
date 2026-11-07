# Spring Boot 4 / Spring AI 2 最小升级实验

后续核心组件验证见 [M0-02b 报告](spring-ai-2-core-compatibility.md)；以下为上一阶段的历史结果。

日期：2026-11-06。实验分支：`experiment/spring-ai-2-upgrade`。
起点：`feature/m0-foundation`，`b68b2927d9224de9ebad79e948830e8d79a40efa`；开始时工作区干净。
本报告不替换冻结的兼容性矩阵或架构基线。

## 变更

仅修改 pom.xml，并新增本报告。启动类、上下文测试与配置无需修改。

| 项目 | 原版本/依赖 | 实验版本/依赖 |
|---|---|---|
| Java release | 21 | 21 |
| Boot Parent / Maven Plugin | 3.5.15 | 4.1.2 |
| Spring AI BOM | 1.1.8 | 2.0.2 |
| Web Starter | spring-boot-starter-web | spring-boot-starter-webmvc |
| 测试 Starter（test scope） | spring-boot-starter-test | spring-boot-starter-webmvc-test |

按 Boot 4 模块化指南采用 MVC 及配套测试 Starter；后者传递引入基础测试 Starter，无需重复声明。
没有手动覆盖第三方版本，没有增加模型、持久化或业务依赖。

## 官方依据（文档判断）

- [Boot 4.1.2 系统要求](https://docs.spring.io/spring-boot/4.1/system-requirements.html)：Java 17～27、Framework 7.0.10 起，Java 21 满足要求。
- [Spring AI 2.0.2 入门文档](https://docs.spring.io/spring-ai/reference/getting-started.html)：2.0.x 支持 Boot 4.0.x / 4.1.x。
- [Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)：MVC 与测试 Starter 的模块划分。

上述支持声明不能替代未执行的业务集成验证。

## 实际执行与结果

环境：Oracle Java 21.0.10、Maven 3.9.16、Windows 11 amd64。

1. 使用 Invoke-WebRequest 直接请求 Maven Central 的以下 POM，均返回 HTTP 200：
   - https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/4.1.2/spring-boot-starter-parent-4.1.2.pom
   - https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/2.0.2/spring-ai-bom-2.0.2.pom
2. 首次 `mvn -B clean verify` 在依赖解析阶段失败：`java.nio.file.AccessDeniedException: D:\develop\repository\maven\org\springframework\spring-core\7.0.10\spring-core-7.0.10.pom.lastUpdated`。没有执行到编译/测试，不能计为测试失败。检查时该文件已不存在；没有修改权限或依赖，再次执行相同命令成功。原因属于本机缓存写入异常，具体竞争来源未确定。
3. 重试 `mvn -B clean verify`：BUILD SUCCESS，25.784 秒；Tests run: 1, Failures: 0, Errors: 0, Skipped: 0。启动类与原有 `@SpringBootTest` 均直接编译通过。
4. `mvn -B help:effective-pom "-Doutput=target/effective-pom.xml" dependency:tree -Dverbose "-DoutputFile=target/dependency-tree.txt"`：BUILD SUCCESS。确认 Boot 4.1.2、AI BOM 2.0.2 的托管项（例如 spring-ai-model 2.0.2）、maven.compiler.release=21。
5. 依赖树中所有 Spring Framework 模块均为 7.0.10，没有混入 6.x，没有 `omitted for conflict`；重复依赖由 Maven 去重。实际包含 Tomcat 11.0.27、Jackson Databind 3.1.7、JUnit Jupiter 6.0.3。未发现当前依赖图的版本冲突，但这不证明未来依赖兼容。
6. `javap -verbose target/classes/com/lnjecit/knowledge/assistant/KnowledgeAssistantApplication.class`：major version 65，对应 Java 21。
7. `java -jar target/knowledge-assistant-0.0.1-SNAPSHOT.jar --server.port=0`：Boot 4.1.2 上下文启动成功，Tomcat 实际监听 65214，启动耗时 7.392 秒；curl 请求根路径返回 HTTP 404，符合没有业务端点的预期。验证后停止 PID 8668 并等待退出。
8. `git diff --check` 通过；没有业务代码、占位类或冻结文档变更。

Maven 构建使用本机配置的阿里云 central 镜像，Central 直连可获取性由上述独立请求验证。
本机详细日志：TEMP/ka-boot4-verify.log（首次失败）、TEMP/ka-boot4-verify-retry.log（成功）；target/effective-pom.xml、dependency-tree.txt、dependency-check.log、startup.log、startup-error.log。临时日志和 target 不提交，可能被清理；本报告保留关键结果与复现命令。

## 判断与未验证范围

最小 Web 骨架升级验证通过，可以作为新基线候选，建议评审后再决定正式切换。
尚不足以批准完整 Knowledge Assistant 技术栈基线：原兼容矩阵仍为 Boot 3，后续 ES 客户端与固定 ES 服务端、持久化及模型适配需要重新核对。

本次只导入 Spring AI BOM，依赖树没有实际 Spring AI 组件。因此 Spring AI API、Chat/Embedding/Rerank、VectorStore、Advisor、ChatMemory、Tokenizer、供应商协议及真实模型调用均未验证。
未验证数据库、Flyway、ES、MinIO、Docker 或业务行为；未执行 M0-03。Jackson 3、Tomcat 11、JUnit 6 的后续业务使用兼容性也不由本次上下文测试覆盖。
