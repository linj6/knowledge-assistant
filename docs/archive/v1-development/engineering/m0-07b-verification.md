# M0-07B 测试基线实施验证

日期：2026-11-07；分支 feature/m0-foundation；开始时工作区干净。按批准的方案 A 实施，未修改生产代码、Flyway V1、Product Spec、Architecture Spec 或依赖管理版本。

## 实现与选择

Surefire 3.5.6 默认只选 *Tests；online-model 通过 combine.self=override 只选 ModelOnlineIT、RerankOnlineIT。effective-online.xml 静态核实没有合并普通列表。Failsafe 3.5.6 绑定 integration-test/verify，仅列 DatabaseIT、ElasticsearchIT、MinioIT；本次实际执行日志也只出现这三个类。没有新增通用容器管理框架。

DatabaseIT 每个方法独立容器，所有 SpringApplication 启动参数强制传入容器 JDBC/用户名/密码，并关闭模型；直接 JDBC 也使用容器地址，不读取 KA_DB_*。空库断言、真实V1迁移、重复迁移0、vector0.8.0、距离0/1及失败迁移阻断均已执行。日志确认PG17.6。没有访问开发库；未专门注入污染环境变量做负面实测。

ES 复用预装IK镜像，验证版本、中文及spring/boot tokens、显式BM25临时索引的写入/查询/删除。MinIO 使用独立动态端口、健康等待、测试bucket及mc上传/下载内容断言和清理；未引入生产存储模块。所有测试容器不用开发卷。

## 实际命令和结果

- `mvn -B dependency:tree`（限定相关依赖）：成功；Testcontainers模块/core 2.0.5，docker-java 3.7.1，Jupiter 6.0.3。无版本覆盖。
- `mvn -B help:effective-pom -Ponline-model -Doutput=target/effective-online.xml`：成功，只解析配置，不执行在线测试。
- `mvn -B spotless:apply spotless:check`：通过；后续修改后再次 apply/check/test-compile，14个测试源文件编译成功。
- `mvn -B test`：更新授权门禁后42项，42通过、0失败/错误/跳过。实际仅8个离线类，未出现在线类或容器IT。
- 最后为上下文测试增加高优先级 properties 强制关闭模型后，局部 `mvn -B test -Dtest=KnowledgeAssistantApplicationTests,OnlineAuthorizationTests`：2项通过、0失败/错误/跳过。42项的完整批次未在此修改后重复。
- `mvn -B failsafe:integration-test failsafe:verify`：4项，PG2通过、ES1通过、MinIO1失败（镜像缺少cmp，退出127），构建正确失败。
- 改用mc cat下载后Java比较内容，再编译并执行 `mvn -B failsafe:integration-test failsafe:verify -Dit.test=MinioIT`：1项通过、0失败/错误/跳过。最终各服务均有局部通过证据，不将失败批次描述为全通过。
- 早期编译暴露2.0.5不存在PullPolicy.neverPull()，已改为ImagePullPolicy实现；另一次编译完成前直接调用Failsafe报无测试，未当作通过。
- 完整diff审阅及 `git diff --check` / 暂存检查通过。

日志保存在本机TEMP的ka-m007-*.log，JUnit XML在target/surefire-reports、target/failsafe-reports；这些是临时证据，本文保存结果，不提交完整启动日志。

## Docker与拉取限制偏差

Docker29.4.0可用，三个业务镜像本地已存在，均成功启动；PG摘要与Compose一致，ES和MinIO使用已验证项目标签，无构建、无IK下载。Testcontainers与Docker握手通过，动态PG地址日志可见，未使用开发固定端口。

首轮误将全局配置写为image.pull.policy，辅助容器没有采用禁止拉取策略，实际自动拉取并启动了testcontainers/ryuk:0.14.0（初查本地只有0.12.0）。这是本轮超出镜像拉取限制的操作，已向用户说明；不能声称全程无拉取。没有删除新镜像或修改全局Docker设置。

已修正src/test/resources/testcontainers.properties为pull.policy，指向LocalImagePullPolicy；最终MinIO重跑日志明确Found configured Image Pull Policy及该类名，Ryuk0.14.0使用已有缓存，没有拉取日志。最终代码拒绝业务和辅助镜像自动拉取，缺镜像会失败，不使用禁用Ryuk或跳过测试规避问题。

## 未验证与交接

未执行完整mvn verify（留M0-07C）、真实在线profile、真实模型请求、Docker不可用/缺镜像负面运行、空白daemon离线恢复或CI环境。在线缺授权通过离线调用门禁断言，Rerank既有门禁保留；不能把静态profile选择核查写成真实在线测试通过。配置基线仍为设计候选，不新增未使用属性。没有已知Docker/依赖启动阻断，但离线制品交付和长期镜像维护风险仍在。停止等待人工代码评审，不推送、不创建PR、不合并。
