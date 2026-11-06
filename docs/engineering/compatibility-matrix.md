# M0-01 技术版本兼容性矩阵

验证日期：2026-11-06；分支：feature/m0-foundation；平台：Windows 11 amd64，Docker Desktop Linux amd64。

## 结论与状态口径

Java/Maven 可运行，选定的 Maven 组合依赖解析成功，pgvector 镜像可获取。**尚不能宣称完整运行栈验收通过**：MinIO 社区仓库已归档且镜像来源存在访问风险；ES/IK 尚未启动验证。本次不改变冻结架构、不创建工程或 Compose。

- 实测：只表示本节列出的具体命令通过，不推导启动、业务或安全验收通过。
- 文档支持：官方资料提供兼容依据，未完成对应运行测试。
- 风险/待验证：缺少证据、访问失败或存在维护问题。

## 版本矩阵

| 组件 | 固定版本 | 选择原因、兼容关系与状态 |
|---|---|---|
| Java | 21；本机 Oracle 21.0.10+8-LTS-217 | 符合架构；java -version 实测。补丁安全更新需持续跟踪 |
| Maven | 3.9.16 | 本机版本实测，满足 Boot 3.5 要求的 >=3.6.3 |
| Spring Boot | 3.5.15 | 保留 Boot 3；与 AI 1.1.8 发布说明对应，parent/BOM 解析实测 |
| Spring AI | 1.1.8 | 稳定发布；BOM + OpenAI starter 依赖解析实测；仅作协议依赖探针，不决定模型供应商 |
| Spring Framework | 6.2.19 | Boot 管理，解析树实测，不单独覆盖 |
| JUnit Jupiter / Platform | 5.12.2 / 1.12.2 | Boot 管理，解析实测；未运行测试 |
| Testcontainers | 1.21.4 | Boot 管理，postgresql 模块解析实测；JUnit 集成模块、Docker 29 API 握手尚未验证 |
| PostgreSQL | 17.6 | 从固定 pgvector 镜像 postgres --version 实测；17 系列稳定基线 |
| pgvector | 0.8.0 | 镜像内 vector.control 实测；支持 PG17，精确向量搜索满足架构；扩展 SQL 尚未执行 |
| PostgreSQL JDBC | 42.7.11 | Boot 管理，JAR 解析实测；数据库连接尚未实测 |
| Flyway core / database-postgresql | 11.7.2 / 11.7.2 | Boot 管理并保持同版；必须包含 PostgreSQL 独立数据库模块，解析实测；迁移待 M0-05 |
| Elasticsearch / IK | 8.18.0 / 8.18.0 | IK ZIP 下载并读取 descriptor，version 与 elasticsearch.version 均为 8.18.0；严格一致，不跨补丁混装；安装启动待验证 |
| MinIO | RELEASE.2025-09-07T16-13-09Z（候选，未批准为可运行基线） | 有正式 release；旧社区二进制维护风险显著，Quay 拉取 401，不能声称镜像可获取 |

## 镜像与插件锁定

| 用途 | 地址与 Tag / Digest | 实际证据 |
|---|---|---|
| PG + vector | docker.io/pgvector/pgvector:0.8.0-pg17；sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87 | pull 成功；后续使用 digest 防止 PG 补丁随 tag 漂移 |
| ES 基础镜像 | docker.elastic.co/elasticsearch/elasticsearch:8.18.0 | 已发起 pull；结果见验证记录；不是预装 IK 镜像 |
| MinIO 候选 | quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z | pull 401 UNAUTHORIZED，未获得 digest |
| MinIO 备用官方命名空间候选 | docker.io/minio/minio:RELEASE.2025-09-07T16-13-09Z | 已尝试，结果见下文；不以第三方镜像替代 |

IK 官方包：https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.18.0.zip 。SHA256：3ff14b395289ad8e00e310b6514902512be43b63c7ac73cf9db92790e556b206。无 stable 路径的 URL 实测 404；应使用上述实际成功地址。M0-03 再构建含插件镜像并记录其 digest，不在此提前实现。

## 实际验证记录与复现

开始时 git branch --show-current 返回 feature/m0-foundation；git status --short 仅有未跟踪 .idea/，保留不提交。已阅读四份正式文档。

| 实际命令 | 结果及边界 |
|---|---|
| java -version；mvn -version | Java 21.0.10、Maven 3.9.16，退出成功 |
| docker version | client/server 29.4.0、API 1.54；daemon 可用 |
| mvn -B -f "$env:TEMP/ka-m001/pom.xml" dependency:tree "-DoutputFile=$env:TEMP/ka-m001/tree.txt" | BUILD SUCCESS，13.379 秒；通过本机配置的阿里云 central 镜像解析，不代表 Maven Central 直连实测 |
| mvn -B -f "$env:TEMP/ka-m001/pom.xml" dependency:resolve | BUILD SUCCESS，16.352 秒；依赖 JAR 可解析，未编译启动应用 |
| docker pull pgvector/pgvector:0.8.0-pg17 | 成功，digest 如上 |
| docker run --rm pgvector/pgvector:0.8.0-pg17 postgres --version | PostgreSQL 17.6 |
| docker run --rm pgvector/pgvector:0.8.0-pg17 cat /usr/share/postgresql/17/extension/vector.control | default_version = 0.8.0；未创建数据库或业务表 |
| Invoke-WebRequest 上述 IK URL -OutFile "$env:TEMP/ka-ik.zip"；Expand-Archive；Get-Content plugin-descriptor.properties；Get-FileHash | 下载解包成功，版本匹配与哈希如上；未安装插件 |
| docker pull quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z | 401 UNAUTHORIZED；无法由该错误判断 tag 不存在 |

临时探针位于系统 TEMP，未写入工程。复现 POM：parent 为 org.springframework.boot:spring-boot-starter-parent:3.5.15；导入 org.springframework.ai:spring-ai-bom:1.1.8；依赖为 Boot starter-test、AI starter-model-openai、org.postgresql:postgresql、org.flywaydb:flyway-database-postgresql、org.testcontainers:postgresql，均使用管理版本。探针没有源码，测试 starter 仅用于依赖调查。完整本机日志位于 TEMP/ka-m001 的 maven.log、resolve.log、tree.txt（临时文件不保证长期保留）；本文件保留命令、版本与结果摘要。

## 兼容性问题与后续关闭条件

1. **MinIO 重大维护风险**：官方仓库页面标示 2026-04-25 归档；README 表示社区版源码分发，旧镜像不意味着持续安全维护。许可证为 AGPL-3.0，需保留许可并核查修改、分发和网络服务的适用义务；不能将服务端 AGPL 与客户端 SDK 许可混为一谈。未做法律或 CVE 全量审计。此问题不阻断 M0-02 骨架，但阻断宣称 M0-01 全栈通过以及 M0 基础设施验收；M0-03 前需人工确认受支持的 MinIO 获取/构建与更新路径，不擅自切换存储选型。
2. ES/IK 包级版本匹配已实测，插件安装、容器启动、中文/英文分析器、BM25 尚待 M0-03。ES 使用容器自带 JDK，不强制替换成应用 Java21。后续客户端与服务端版本需对齐；不要直接采用 Boot 默认客户端却假定与 8.18.0 完全兼容。
3. pgvector 的扩展文件存在不等于 CREATE EXTENSION 通过；M0-05 验证 JDBC、Flyway 首次/重复迁移和余弦计算。精确搜索不依赖 HNSW；模型维度仍待 M0-06，不在本次虚构。
4. Testcontainers 1.21.4 传递 docker-java 3.4.2，Docker 29 的 API 最低版本 1.40；CLI 可用不能证明 Java 客户端握手成功。M0-07 必须运行实际容器测试，必要时评估同系列补丁升级。JUnit4 4.13.2 为 Testcontainers 传递依赖，不表示采用 JUnit4 测试；不要额外引入 Vintage。
5. 依赖树没有证明运行时无冲突。统一 Boot/AI BOM，不独立升级 Jackson、Reactor、Netty；本次树中 Jackson 2.21.4、Reactor 3.7.19。Spring AI 1.1 对 Boot3.5，避免 Boot3.4/AI1.1 混用导致 ES 类缺失；不引入与冻结 Boot3 不符的 AI2/Boot4。未执行漏洞扫描，旧 ES/PG/vector 补丁维护状态需在部署前复核。
6. M0-02 可使用 Java/Maven/Boot/AI 基线继续搭建；本任务保留未关闭验证项，不等于 M0 七项验收完成。

## 官方依据

- [Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)：Java 与 Maven 范围。
- [Spring AI 1.1.8 发布说明](https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/)：Boot 3.5.15 对应组合。
- [Spring AI 官方兼容问题 #5620](https://github.com/spring-projects/spring-ai/issues/5620)：Boot3.4 与 AI1.1 ES 类缺失风险。
- [pgvector 官方项目](https://github.com/pgvector/pgvector)：PG 扩展、Docker、精确搜索支持。
- [IK 官方项目](https://github.com/infinilabs/analysis-ik)：安装来源及版本匹配规则；实际 ZIP descriptor 为本次精确版本证据。
- [MinIO 官方项目](https://github.com/minio/minio) 与 [候选版本 release](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z)：归档、源码分发、AGPL 与版本来源。

### 本次拉取最终补充

- docker pull minio/minio:RELEASE.2025-09-07T16-13-09Z：失败，failed to unpack image，层媒体类型为 text/html；不能判定为可获取镜像，也未证明是上游不存在，需排查网络/registry 代理返回。
- docker pull docker.elastic.co/elasticsearch/elasticsearch:8.18.0：部分层已下载，长时间未完成，本次中断；随后串行队列中的 Quay pull 不计作新的成功验证。未获得 ES digest，明确版本 tag 保留，完整拉取与版本命令待 M0-03。
- docker image inspect 对 ES/MinIO 候选返回 No such image，与未完成/失败状态一致。
