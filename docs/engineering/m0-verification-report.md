# M0 完整验收报告

日期：2026-11-07；分支 `feature/m0-foundation`；验证代码 HEAD `737587f`。开始时工作区干净。本次仅新增本文，不修改冻结 Product Spec、Architecture Spec、Flyway V1 或代码，不执行在线模型请求、不拉取/构建/替换镜像、不操作开发服务和卷。

## 1. 验收结论

**部分验证：本机完整 Maven 与真实容器功能验收通过，M0 尚不满足完全完成定义。** 空白 Docker daemon/异机恢复未验证；关键业务参数仍为设计候选而非实施计划要求的已确定基线；架构版本及 README 当前状态存在历史描述差异。上述完成定义缺口须人工评审处置，不能将 BUILD SUCCESS 等同 M0 完全通过。没有本次测试失败或已发现的代码缺陷；不启动 M1。

已验证表示本次实际执行或明确标注的历史证据；部分验证表示只覆盖部分条件或仅静态检查；未验证表示没有运行证据；失败仅指实际执行失败。历史失败保留原记录，不混入本次成功统计。

## 2. 任务与提交证据

| 任务 | 状态及证据提交 | 证据 |
|---|---|---|
| M0-01 | 部分验证；75b99f7、2d763e1 | [兼容矩阵](compatibility-matrix.md)，版本决策有证据，远端获取与维护风险未全部关闭 |
| M0-02 | 已验证工程范围；015a326、f92577f、2cbe1b1 | [升级实验](spring-ai-2-upgrade-experiment.md)、[核心兼容](spring-ai-2-core-compatibility.md)，本次上下文及核心测试再次通过 |
| M0-03 | 本机功能已验证，交付部分验证；214a4e5 | [基础设施记录 M0-03d](m0-03-infrastructure-verification.md)，联合健康与重启持久化历史通过，空白恢复未验证 |
| M0-04 | 已验证；6a2849a、616a7be | [配置错误协议记录](m0-04-verification.md)，本次相关离线测试通过 |
| M0-05 | 已验证；e402303 | [数据库记录](m0-05-database-verification.md)，本次隔离容器迁移、向量及失败阻断再次通过 |
| M0-06 | 接入范围历史已验证；3c17c04、80eb340、5f3271b | [模型报告](model-capability-report.md)，本次仅复核历史在线证据 |
| M0-07 | 部分验证；ae8679f、0b2d120、737587f；本提交归档07C | [设计](m0-07-test-design.md)、[07B记录](m0-07b-verification.md)，本次完整构建通过，空环境与参数基线缺口仍在 |

以上提交均由本机 git log 核对，任务实现独立提交已存在；人工最终验收不由本报告替代。

## 3. 环境与镜像

本次 `java -version`、`mvn -version`、`docker version`、`docker images` 实查：Windows 11 amd64，Oracle Java 21.0.10+8-LTS-217，Maven 3.9.16，Docker Desktop 4.69.0、Engine/CLI 29.4.0、API 1.54，desktop-linux/linux amd64。POM：Boot 4.1.2、Spring AI 2.0.2；实际构建 Surefire/Failsafe 3.5.6。07B依赖树历史证据：Jupiter 6.0.3、Testcontainers 2.0.5、docker-java 3.7.1；M0-05历史依赖树：Flyway 12.4.0、JDBC 42.7.14。本次未重新执行依赖树。

| 镜像 | 本机准备与版本证据 |
|---|---|
| pgvector/pgvector:0.8.0-pg17 | ID前缀40b404964359；测试固定摘要sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87；实际日志PG17.6、断言vector0.8.0 |
| knowledge-assistant-elasticsearch:8.18.0-ik8.18.0 | ID前缀448ce0dee8e4；本次版本8.18.0及IK分词通过，插件精确8.18.0历史由M0-03核查 |
| knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local | ID前缀14cea493d9a3；精确版本及完整ID历史见M0-03d，本次对象读写通过 |
| testcontainers/ryuk:0.14.0 | ID前缀7c1a8a9a47c7，执行前存在，本次采用配置的LocalImagePullPolicy |

本次没有拉取或构建操作。07B曾误拉Ryuk的历史偏差不抹除，详见原记录。镜像存在和本机运行不证明空白daemon、CI或异机可复现。

## 4. 本次完整 Maven 结果

实际命令：`mvn -B clean verify`；BUILD SUCCESS，总耗时01:55，结束于2026-11-07T17:10:22+08:00。Spotless检查、编译、打包及Failsafe verify随生命周期完成。

| 执行器/测试类 | 数量 | 失败 | 错误 | 跳过 | 耗时秒 |
|---|---:|---:|---:|---:|---:|
| Surefire ApiBaselineTests | 8 | 0 | 0 | 0 | 4.320 |
| Surefire BusinessExceptionTests | 3 | 0 | 0 | 0 | 0.152 |
| Surefire ConfigurationTests | 5 | 0 | 0 | 0 | 0.600 |
| Surefire KnowledgeAssistantApplicationTests | 1 | 0 | 0 | 0 | 5.704 |
| Surefire ModelCapabilityTests | 10 | 0 | 0 | 0 | 5.512 |
| Surefire OnlineAuthorizationTests | 1 | 0 | 0 | 0 | 0.012 |
| Surefire RerankTests | 8 | 0 | 0 | 0 | 2.467 |
| Surefire SpringAiCoreCompatibilityTests | 6 | 0 | 0 | 0 | 0.701 |
| Failsafe DatabaseIT | 2 | 0 | 0 | 0 | 19.261 |
| Failsafe ElasticsearchIT | 1 | 0 | 0 | 0 | 44.070 |
| Failsafe MinioIT | 1 | 0 | 0 | 0 | 3.269 |
| 合计 | 46 | 0 | 0 | 0 | 86.068 |

类耗时取JUnit报告（ES、MinIO亦见日志）；合计不含构建和进程开销。Surefire共42项/8类，Failsafe共4项/3类，与源码预期一致，无意外跳过；ModelOnlineIT、RerankOnlineIT均未执行。日志在本机TEMP/ka-m007c-verify.log，XML在target/surefire-reports及target/failsafe-reports；临时产物不提交且可能被清理，本文保留统计与结果。

## 5. 真实基础设施证据

- 已验证：DatabaseIT每方法独立PG容器、随机映射端口。空库无历史表，正式V1首次迁移成功，历史成功V1为1条，vector=0.8.0；重复迁移0项；临时vector(3)余弦距离0/1。故意错误SQL导致Spring启动异常，断言捕获，失败阻断测试通过；日志中的该异常为预期故障，非构建错误。
- 已验证：ElasticsearchIT实际8.18.0响应，ik_max_word分词包含spring、boot、知识库；随机临时索引显式BM25，写入并refresh后查询命中文档1，finally删除。
- 已验证：MinioIT独立容器内mc创建随机bucket、上传中文内容、下载逐字相等并清理。未复测重启持久化；该项采用M0-03d两次重启及对象哈希一致的历史证据。
- 部分验证：Compose三服务联合功能和持久化采用M0-03d历史证据，本次不启动/重启开发基础设施；数据库3维探针不代表1024维Embedding端到端存储验证。

## 6. 测试隔离负面验证

本次以源码、POM和测试资源静态检查及已有离线门禁测试为主，不停止Docker、不删除镜像、不修改全局配置。

| 项目 | 状态 | 实际证据与边界 |
|---|---|---|
| Docker不可用失败而非跳过 | 部分验证（静态） | 三类@Testcontainers没有disabledWithoutDocker=true；无Assumptions/Disabled/异常转skip，Failsafe failIfNoTests=true。未运行不可用daemon独立故障进程；仅设DOCKER_HOST仍可能被Desktop发现策略回退，不能据此伪证不可用 |
| 缺必需镜像失败且不拉取 | 部分验证（静态） | 三业务容器withImagePullPolicy返回false；testcontainers.properties的pull.policy指定LocalImagePullPolicy，其shouldPull恒false，覆盖辅助镜像。本次正常运行采用该策略；缺镜像故障未注入，没有安全独立空daemon，不通过删除现有镜像制造条件 |
| 默认不选择在线模型 | 已验证 | POM Surefire仅*Tests、Failsafe三个明确容器类；实际完整日志/XML无两个OnlineIT |
| 缺在线授权拒绝供应商调用 | 已验证（离线门禁） | OnlineAuthorizationTests缺授权断言及RerankTests.onlineGateRequiresExplicitConfigurationWithoutNetwork通过；源码授权检查位于调用前。未执行在线profile或真实网络负面请求 |
| 数据库不得回退开发库 | 部分验证（运行与静态） | 本次使用容器随机端口；所有Spring启动路径追加容器URL/用户名/密码命令行参数，直接JDBC也取容器地址；不读取KA_DB_*、不挂开发卷。未额外注入污染环境变量；无Docker时扩展启动失败不会进入连接路径 |

未执行的故障场景保留为未验证，不把静态结论写成实测失败。没有为获得成功删除断言、跳过测试或放宽选择规则。

## 7. 模型历史证据（本次零真实请求）

依据模型报告的M0-06A最终在线记录：qwen-plus普通Chat3272ms，usage18/1/19；三次JSON分别813/878/787ms，usage均33/19/52；单条Embedding413ms、1024维、tokens8，批量198ms、3条1024维、tokens26、indices[0,1,2]。Embedding模型ID无可靠记录，不推测补填。

M0-06B历史RerankOnlineIT为1项通过，qwen3-rerank单次1786ms；spring-di/weather/postgres分别0.8793624122453081、0.10159243956770786、0.07159474172730773。稳定ID来自生产index映射及离线乱序断言，在线结果不是对原始index映射的独立核验。

TokenEstimator返回exact=false，码点加固定开销和默认20%余量未校准、不保证不低估。Rerank usage=unavailable，跨阶段usage、账单费用、统计排序质量、JSON可靠性、窗口预算及1024维真实存储均未完整验证。单次耗时不作为性能基准。本次只复用历史证据，没有重复Chat、Embedding或Rerank。

## 8. 完成定义缺口、风险与M1前事项

对实施计划完成定义逐项判断：①独立提交存在，最终文档一致性部分验证；②本机版本和Compose历史功能已验证，空环境可复现性未验证；③迁移/向量/IK/BM25/对象实际功能已验证；④历史三类在线能力及本次默认离线/容器入口已验证；⑤业务参数仅设计候选，部分验证；⑥本文完成真实证据归档；⑦等待人工M0评审。

进入M1前需要人工解决或明确接受的验收缺口：

1. 在独立空白daemon/异机准备可信PG、ES/IK、MinIO、Ryuk制品，验证离线恢复和故障门禁；确定CI镜像交付渠道与维护责任。本次不存在可直接使用的隔离环境，未创建额外daemon。
2. 确认设计文档六类参数候选及后续验证责任是否满足M0基线要求，或明确调整完成定义；目前没有configuration-baseline.md，也未添加未使用业务配置，不能宣称已固定最优参数。
3. 处理Architecture Spec的Boot3与实际Boot4.1.2差异；README开头“仅M0-02”、早期“未接入”及MinIO未通过的历史表述与后附记录存在不一致。本次按只新增报告约束不改冻结文档和README。
4. 确认MinIO旧社区镜像长期维护、安全更新及许可责任；官方远端获取历史失败未重试。此为供应链风险，不是本次对象读写失败。

后续业务阶段另需校准Token估算、确认Embedding模型ID与1024维数据库约束、完善usage/费用证据；不在本次扩展实现。

## 9. 交付检查

仅新增本报告，完整diff审阅及git diff --check执行结果见交付提交；不推送、不创建PR、不合并，不进入M1。最终验收结论为部分验证，等待人工评审。

## 10. M0 收尾任务 A：文档基线确认（2026-11-07）

本节为后续补充，不覆盖第 1～9 节的历史状态与真实执行记录。已新增 [V1 初始配置基线](configuration-baseline.md)，确认六类初始值、约束、实现阶段、验证方式及待确认语义；均未新增配置属性，业务实现与验证仍待 M1/M2/M3。

已最小修正 Architecture Spec 的 Boot 4.1.2 / Spring AI 2.0.2 版本事实和 README 的早期进度、服务接入、MinIO 状态及完整构建待执行描述。历史 MinIO 远端获取失败未改写；本机运行成功不代表隔离环境恢复成功。第 8 节第 2、3 项的文档缺口由本次收尾处理，仍需人工评审，不能视为业务参数已验证。

本轮仅执行文档本地链接与相互引用检查、完整 diff 审阅和 git diff --check；未执行 Maven、Docker 或真实模型请求。第 4 节 clean verify 结果为历史结果，未在本轮重跑。未修改 Java、POM、Compose、Flyway 或生产配置。

M0 最终仍为部分验收：独立空白环境镜像恢复、Docker 不可用/缺镜像等负面测试尚未完成，CI 制品渠道及 MinIO 维护责任仍待确认。停止等待人工评审，不启动 M1。
