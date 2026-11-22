# M0 隔离环境与镜像恢复验收设计

日期：2026-11-07；分支：feature/m0-foundation；调查基线：76d8ffce79966282730e6c4fc9951c9a5ab4aadf。

本文仅为 B-1 调查与设计，未运行实验。下列 Docker、Maven、网络隔离及虚拟机步骤均为后续单独授权后的操作方案，不是已执行记录。仅检查仓库与历史记录，未查询实时 daemon，也未确认仓库外归档现在是否存在。M0 仍为部分验收。

## 1. 当前环境与制品现状

历史环境为 Windows 11 amd64、Docker Desktop 4.69.0、Engine 29.4.0/API 1.54、Linux amd64；Java 21、Maven 3.9.16，Testcontainers 2.0.5/docker-java 3.7.1。当前机器、虚拟化能力、剩余资源与权限均未重新实查。

依据：[README](../../../../README.md)、[实施计划](../plans/implementation-plan.md)、[兼容矩阵](compatibility-matrix.md)、[基础设施历史记录](m0-03-infrastructure-verification.md)、[07 设计](m0-07-test-design.md)、[07B 记录](m0-07b-verification.md)、[M0 报告](m0-verification-report.md)、[配置基线](configuration-baseline.md)。历史失败不因后续本机成功而消失。

| 镜像 | 版本与已知身份 | 来源、构建输入及归档现状 |
|---|---|---|
| PostgreSQL + pgvector | pgvector/pgvector:0.8.0-pg17；测试引用 digest sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87；历史运行 PG17.6/vector0.8.0，ID 仅记录前缀 40b404964359 | 历史官方命名空间获取成功；无可信归档路径/归档哈希记录，完整 image ID 与 RepoDigests 需补录，不把相同前缀当成两者等同的证明 |
| Elasticsearch + IK | knowledge-assistant-elasticsearch:8.18.0-ik8.18.0；ES/IK 均8.18.0；历史最终 ID 前缀448ce0dee8e4 | 项目 Dockerfile 基于 docker.elastic.co/elasticsearch/elasticsearch:8.18.0，历史基础 manifest digest sha256:70b37a809f04cd86e14460f959ff33c272a38f24268eee8b913c2cc73ac86c88；无最终镜像可信归档/完整 ID 记录 |
| MinIO | knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local；ID sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e；commit 07c3a429bfed433e49018cb0f78a52145d4bedeb | 历史本机 minio/minio:latest 核验版本后固定项目标签，并非允许以后回退 latest。历史归档 C:/Users/mi/ka-infrastructure-artifacts/minio-RELEASE.2025-09-07T16-13-09Z.tar；SHA256 033da3bb0531f4b071310c1ad44cade4017e694ec554dc482d6da38f01fbcd33；当前文件存在性及重新校验未知 |
| Ryuk | testcontainers/ryuk:0.14.0；历史 ID 前缀7c1a8a9a47c7 | Testcontainers 辅助镜像，07B 曾发生隐式下载，随后配置全局禁止拉取策略；无完整 ID、可信 digest、归档路径或归档哈希记录 |

ES Dockerfile 与 [Compose](../../../../compose.yaml) 已读。IK 输入为 https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.18.0.zip ，SHA256 为 3ff14b395289ad8e00e310b6514902512be43b63c7ac73cf9db92790e556b206。当前 Dockerfile 基础镜像仍按标签引用，历史本地 manifest list 曾因 provenance 改变；构建输入可追踪不等于位级可重复构建。后续恢复优先使用核验后的完整最终镜像归档，恢复阶段不构建或下载 IK。

[LocalImagePullPolicy](../../../../src/test/java/com/lnjecit/knowledge/assistant/LocalImagePullPolicy.java) 的 shouldPull 恒 false；[测试配置](../../../../src/test/resources/testcontainers.properties) 设置 pull.policy，三个业务 IT 另有 withImagePullPolicy(image -> false)。[POM](../../../../pom.xml) 默认 Failsafe 白名单为 DatabaseIT、ElasticsearchIT、MinioIT，failIfNoTests=true。静态配置不能替代负面实测。

镜像标签是可变引用；image ID 是镜像配置内容身份，Registry digest 是清单或索引身份，tar SHA256 是交付文件身份，三者不能互换。docker save/load 是否保留测试需要的 digest 引用必须实测，不能只验证标签可见。

## 2. 隔离方案对比与推荐

| 方案 | 镜像/容器、网络与卷隔离 | 开发环境影响 | Windows 11 复杂度、资源与权限 | 离线/负面测试与 CI |
|---|---|---|---|---|
| A：独立 Linux VM 内独立 daemon | 独立虚拟磁盘、daemon、卷、网络；禁止宿主 Docker socket/开发目录挂载 | 隔离较强，仍共享宿主 CPU/内存/磁盘，须限额；虚拟交换网络须审查 | 中等；需虚拟化可用、创建 VM 权限、guest 管理权限及额外磁盘内存；不要求修改 Desktop | 可断外网保留 guest 回环，可用全新快照漏装镜像；无 daemon 的独立 VM 克隆用于不可用场景；可迁移至 CI |
| B：Desktop 中独立 Docker Context | Context 仅保存连接目标；若仍指向同一 daemon，镜像、容器、卷和网络均共享，独立项目名也不隔离镜像 | 同 daemon 下删除/停止会影响开发；不允许用于本次缺镜像实验 | 低，但不满足隔离；若 Context 指向独立 VM，则隔离来自 A，不来自 Context | 同 daemon 无法安全制造空存储；不推荐。CLI Context 也不能证明 JVM 实际连接目标 |
| C：专用测试主机或临时 CI Runner | 专用本地 daemon/临时磁盘可真正隔离；共享 Runner socket 或远程共享 daemon 不合格 | 对开发机影响最小；需审查 CI 凭据及网络 | 本地复杂度低，需主机配额、运维权限、可信制品分发；资源由平台提供 | 适合长期 CI 与真正异机验证；是否支持断网、特权 Docker、全新磁盘由平台确认 |

推荐最小方案 A：准备一次性 Linux amd64 VM，在 guest 内运行 Java/Maven 与 Docker，避免 Windows Context 与 Testcontainers 自动发现 Desktop 的交叉影响；负面场景使用独立克隆。此为新测试执行环境建议，不改变项目技术选型。若无虚拟化权限/资源，转 C，不能退化为同 daemon 的 Context。

建议初始配额为 4 vCPU、8 GiB RAM、60 GiB 可用虚拟磁盘，属于待测规划值，不是已验证最低要求；宿主须另留开发资源。磁盘最终按四镜像展开大小、归档、Maven 缓存、快照和日志加余量评估，资源不足先阻断。独立 daemon 只换 data-root 而共用网络/宿主权限的方案需额外审查，不作为最小路径。

## 3. 前置条件与实施顺序（后续执行）

1. 人工批准执行窗口、资源限额、虚拟化平台、guest OS/Engine 版本、操作者与隔离网络。记录宿主开发 daemon ID、容器 ID/状态/StartedAt/RestartCount、镜像身份和开发卷清单，使用显式目标只读查询，不采集凭据或业务数据。
2. 制品保管人提供四个独立镜像归档及审批后的清单，含完整 ID、RepoDigests、标签、平台、大小、来源、导出时间、源 daemon 身份、归档 SHA256、输入提交/插件校验和。仓库代码、JDK21/Maven、完整离线 Maven 缓存与已知版本 Engine 安装介质一起准备，不把测试时在线下载作为恢复步骤。
3. VM 从无 Docker 数据的基础盘创建，独立磁盘，不共享 Desktop socket、用户 .docker/.testcontainers.properties、开发 .env 或卷。只复制审查后的代码/缓存/制品，使用全新 HOME 和实验凭据，移除模型启用/授权变量，禁止 online-model profile。
4. 记录 VM UUID、磁盘来源、daemon ID、DockerRootDir、平台、网络、路由、socket、卷和容器清单。空环境必须没有镜像和容器、没有开发卷，默认 Docker 网络允许存在；快照必须早于 load。验证 daemon ID 与开发 daemon 不同。CLI docker info 与 JVM 日志须指向 guest 的本地 daemon。
5. 建立“工具齐备、镜像为空”的快照。断开 guest 外网及宿主共享访问，仅保留 guest 回环/测试容器所需网络；记录路由、阻断规则、daemon 日志与出口抓包。先完成离线 test-compile，缓存不足作为环境阻断，不混作 Docker 负面成功。
6. 正常恢复四镜像，完成第 4 节检查，再运行三个容器 IT。各负面场景使用空快照的全新克隆，分别加载所需子集，不在开发 daemon 删除镜像。不可用场景使用从未安装/运行 daemon 的工具 VM。
7. 每次运行前确认目标身份，独立进程、独立工作目录与报告目录；不得复用上次 Failsafe 报告。实验后归档证据，按第 6 节回滚。

## 4. 镜像恢复与校验方案

制品准备与恢复分开授权。若后续需从现有可信源导出，先核验完整身份，再以 docker image save -o <单镜像归档> <已核验引用> 导出；不覆盖旧归档，不拉取补齐。Windows 用 Get-FileHash -Algorithm SHA256 -LiteralPath <归档>，Linux 用 sha256sum <归档>。哈希清单经独立可信渠道交付，不能只从与 tar 同一未可信来源取得哈希就认定来源可信。

在空 guest 中依次校验文件 SHA256、执行 docker image load -i <归档>，保存 stdout/stderr 与退出码。每条命令后立即保存退出码；PowerShell 用 $LASTEXITCODE，shell 用 $?，不得由后续打印命令覆盖。恢复后执行 docker image inspect <测试原始引用>，记录完整 Id、RepoDigests、RepoTags、Os/Architecture，与批准清单逐项核对。再保存全量镜像清单，确保无意外镜像。

| 镜像 | load 后身份/功能验收 | 失败诊断与恢复 |
|---|---|---|
| PG/vector | 必须按 DatabaseIT 的完整 tag@digest 引用 inspect 成功；DatabaseIT 验证首次/重复迁移、vector0.8.0、向量距离及错误迁移阻断；PG17.6 日志核对 | digest 引用不保留即阻断，不能去掉 digest 或修改测试绕过；核对归档是否含完整层和所需引用，重新评审导出格式/交付方式 |
| ES/IK | 完整最终 ID/平台与清单一致，核验插件 descriptor 的8.18.0；ElasticsearchIT 验证服务版本、IK分词、临时索引BM25查询 | 缺层、插件/版本不符、内存或权限故障分别记录；不得用官方裸ES镜像替代，回到空快照后恢复已批准制品 |
| MinIO | 完整 ID 与已记录值一致，版本/commit 核对；MinioIT 对独立 bucket 上传下载中文对象并清理 | 历史 tar 不存在/哈希不符/来源链不足即阻断，不回退 latest/第三方；索要可信原归档或另行评审新的制品，不覆盖旧证据 |
| Ryuk | 补齐并批准完整 ID/digest 后才可验收；保留 Ryuk 启用，记录辅助容器启动及测试资源回收 | 权限、socket挂载、缺失或平台不符分别诊断；不通过禁用 Ryuk、替换未知版本掩盖失败 |

PG、ES/IK、Ryuk 目前缺完整可信交付包；MinIO 仅有历史包证据。四者全部通过前不能宣称可重复离线恢复。当前 ES 基础标签与 IK 下载地址的未来可获得性不作为恢复保证。

正常功能入口（仅后续 guest 内执行）：先 mvn -o -B test-compile，再 mvn -o -B failsafe:integration-test failsafe:verify。后者预期 DatabaseIT 2、ElasticsearchIT 1、MinioIT 1，共4项，失败/错误/跳过均0；实际统计以XML为准。不执行在线模型。此局部 IT 验收不等于重新执行历史完整 clean verify。容器随机端口、无开发卷，不运行现有 Compose 的固定端口/构建流程。联合 Compose 与持久化仍引用历史证据，若要在新环境重验需单独批准实验步骤。

## 5. 负面测试方案

### 5.1 Docker 不可用

使用独立工具 VM 克隆：有 JDK/Maven/缓存/已编译代码，但从未安装运行 Engine，无 Docker Desktop/WSL 集成，无宿主 socket、远程 Context 或开发网络通道。不是设置一个错误 DOCKER_HOST 后假定发现失败；允许清理当前实验进程的 Docker/Testcontainers 环境变量，禁止修改宿主全局配置。

运行前保存 guest 进程、socket、监听端口、路由、防火墙与挂载清单，证明不存在可访问的本地或远程 daemon；记录无 /var/run/docker.sock、无 Desktop/Podman 等候选 socket，远程出站被阻断。若 CLI 已安装，docker info 失败仅作补充，不能替代 JVM 证明。运行全新 Maven/Failsafe JVM并保存 Testcontainers 发现日志，核对所有尝试目标均不可用、无成功连接/创建容器；若发现可访问目标，立即判实验无效并修正 VM 隔离。

执行第4节局部 IT 入口，要求 test-compile 成功、Failsafe verify 非零，异常原因明确为 Docker 环境不可用，不是依赖缺失或编译失败；XML/summary 显示真实错误/失败且无静默跳过，不要求初始化失败时仍恰好生成4个方法记录。若只有“无测试”或无XML无法证明具体失败来源，判证据不足；保存完整日志并调查，不标通过。宿主 Desktop 全程保持运行。

### 5.2 缺必需镜像（四个独立场景）

从空镜像快照分别克隆四次，每次只 load 其他三个可信归档，遗漏 PG、ES/IK、MinIO 或 Ryuk 之一。加载后列出完整镜像身份，inspect 被遗漏的测试原始引用必须非零，其余引用可解析。不能通过删除开发镜像制造缺失，也不能通过错改代码标签制造场景。

每个场景先验证 guest daemon 可用且身份正确，然后在新 JVM 运行同一局部 IT 命令。四个场景均要求 Failsafe verify 非零，日志明确指向所缺镜像，不允许成功、静默跳过或默认回退。业务镜像缺失时其他类可能通过；Ryuk 缺失可能在辅助资源初始化阶段导致多个类错误，记录实际数量不硬套预期。

证明策略生效：核对 target/test-classes/testcontainers.properties 与已编译 LocalImagePullPolicy 对应当前提交，无 HOME 文件或环境变量覆盖；记录策略日志（若默认级别不足，后续仅在实验目录配置测试日志，另行审阅），对业务和 Ryuk 均检查没有 Pulling/下载步骤。仅配置文件存在不算实测证明。

证明没有自动拉取：同时采集 Maven日志、daemon调试/API访问证据、docker events、隔离前后镜像清单及出口抓包/阻断计数；检查无 /images/create 拉取请求、无注册表访问/下载与 pull 事件。断网只能证明不能下载成功，不能证明没有尝试；events 没有 pull 也不能单独证明无失败拉取。如果现有 daemon 日志无法识别拉取请求，须在独立 VM 增加可审计的 API 观测或等价跟踪后重验，证据不足时保持未验证，不以网络错误替代“本地镜像不存在”的诊断。不得开放不受保护的远程 Docker API。

保留 Ryuk 启用、禁止拉取策略不变；其他辅助镜像若被发现额外请求，记录名称/来源并阻断，不临时联网补拉。场景结束保留失败报告，重建克隆而非从失败现场继续累积条件。

## 6. 安全边界与回滚

所有变更局限实验 VM，禁止全局 Context 切换、停止 Desktop、开发容器操作、开发卷挂载、镜像删除及 docker system prune。测试只使用实验凭据，无生产/模型密钥。证据不输出环境变量全集或完整敏感 inspect 内容，按白名单摘录身份字段。

异常时先停止实验进程并保留日志；若需清理残留资源，仅在再次核对 VM UUID/daemon ID 后处理实验 VM，最简回滚为关闭并丢弃该一次性克隆或恢复空快照。任何 VM 删除/磁盘回收须明确目标和单独授权，不对计算路径执行递归删除。断网规则仅在 guest/其专属虚拟网络，恢复只撤销实验规则。

结束后用同一显式开发目标只读复核容器 ID/状态/StartedAt/RestartCount、镜像与卷清单，无开发容器重启或数据操作；对照操作记录与开发 daemon 事件时间窗，报告任何并行用户操作，不能要求动态日志/业务数据字节不变。资源竞争也需记录。无法做前后对照则无影响结论只能部分验证。

## 7. 统一验收证据模板

每个正常/负面场景独立编号，保存脱敏原始日志、XML和SHA256证据索引；不把临时 target 文件作为唯一持久证据。

| 字段 | 必填证据 |
|---|---|
| 状态 | 已执行通过/预期负面通过/实际失败/未验证/阻断；负面通过是故障门禁成立，不是构建成功 |
| 代码与工具 | 提交SHA、工作区状态、Java/Maven/Engine/Testcontainers实际版本、OS/架构、开始结束时间 |
| 隔离身份 | 宿主与guest UUID/daemon ID、磁盘与DockerRootDir、socket/网络/卷、空快照来源，开发目标对照 |
| 制品 | 来源审批、完整image ID、RepoDigests、标签、平台、tar路径/大小/SHA256、接收复算、load输出与退出码、原始引用inspect |
| 命令 | 脱敏完整命令、工作目录、进程环境白名单、每步退出码、标准输出和错误、进程终止原因 |
| 测试 | 类名、实际数量、failure/error/skipped、Failsafe summary与XML对应关系、是否出现在线类 |
| 故障注入 | 无daemon证明或遗漏镜像前置inspect、发现日志、失败根因与非零verify证据，排除编译/依赖等无关失败 |
| 拉取观测 | daemon/API日志、events、网络观测覆盖时间、镜像前后差异、是否尝试拉取/是否下载；观测缺失明确标注 |
| 清理与影响 | Ryuk状态/回收、VM回滚、开发环境前后对照及并行操作 |
| 限制 | 未验证项、阻断原因、责任人、后续动作、评审结论，原始证据保管位置与保留期 |

本机独立 VM/daemon 通过只能关闭该平台上的空存储恢复与故障行为；真正异机或 CI 仍需在不同宿主/独立 Runner 从同一交付包重复流程，另记平台、制品传输、缓存准备及权限差异。不能将同机快照克隆写成异机验收，也不将4项局部IT写成46项完整构建。

## 8. 未解决风险及人工决策

- 执行环境：虚拟化可用性、VM平台/guest版本、配额、管理员权限、CI隔离条件与网络观测手段尚未确认；无合格环境则阻断实验。
- 制品阻断：PG/ES/Ryuk 缺可信归档与完整身份清单；MinIO历史归档当前存在性及可信交付链未核验。PG digest在load后可解析性待验证。不得自动回退latest、第三方或修改测试。
- 交付建议：先用访问受控的内部制品存储交付四个独立tar与版本清单，清单审批后不可覆盖旧版；哈希经独立可信渠道分发。具体服务、配额、访问权限、保留期、签名方案、备份及下载审计由人工选择，不假定已有仓库或Registry。
- 责任建议：项目维护者负责版本清单与输入追踪，制品保管人负责归档/哈希/访问与备份，环境操作者负责隔离和日志，评审者负责验收；需人工指定具体人员和替补，当前无人名或既定责任证据。
- MinIO旧社区镜像获取失败、维护/安全更新和许可责任仍未关闭；离线运行成功不能解决维护风险。安全更新触发、漏洞处理期限、重新构建/验收责任需批准，不在本任务替换选型。
- B-1仅新增本文，不更新既有验收报告，不运行Maven/Docker/模型请求。后续实验须单独授权；完成后依据实证更新M0报告并人工评审，不启动M1。
