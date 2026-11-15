# Evaluation 包结构与默认装配

本轮仅整理包结构、引用与必要可见性，并移除评估条件装配，不涉及评估 V2。

## 最终包结构

- controller：EvaluationController、EvaluationRunController、AnswerReviewController。
- service：EvaluationService、AnswerReviewService。
- model：AnswerEvaluationDataset、AnswerEvaluationRun、AnswerReview、EvaluationRunResponse、RetrievalRunData、RunStatus。
- store：EvaluationRunStore、AnswerReviewStore、EvaluationFiles。
- dataset：EvaluationDatasetLoader、FrozenFingerprint、Sha256。
- metrics：AnswerMetrics、EvaluationProtocol（保留原位置）。

移动生产文件17个、测试文件4个，合计21个。测试按 controller/service 分包；metrics/runner 测试保持位置并更新引用。新增 EvaluationAssemblyTests 检查默认扫描可注册全部7个评估 Bean，且装配不调用 RagService、发布快照服务或 DocumentMapper。

保留现有嵌套 Record 的数据结构与所属类，不为移动包拆分 DTO。Loader 对外入口及返回 Record、Store 的跨层构造/保存入口与历史响应类型按实际调用需要开放；私有实现与同层辅助构造器保持原可见性。EvaluationFiles 的产物文件名仍为包内常量，只公开跨层实际使用的根路径、协议与指纹。

## 配置及行为

删除7处评估 Controller/Service/Store 的条件注解及对应 import。仓库当前 YAML 中没有实际的评估开关配置项，因此无需修改 application-local.yml；失效的前端提示、测试断言及文档说明已同步更新。

评估 API 现在默认注册，不自动运行评估。模型启用、费用授权、Gold/发布快照/身份校验保持原样。本轮没有增加认证系统，评估 API 仍只适用于受控本地环境，不适合直接公网暴露。

API 路径、请求响应 Record、评分规则和文件持久化协议不变。没有修改冻结 Gold。AnswerMetrics 仅修改 import 与同一指纹工具的引用形式。

## 验证与审查

执行编译、测试编译、Spotless apply/check，evaluation 定向测试41项全部通过，无失败或跳过；包含真实本地Q01只读材料与临时合成标签、C01/C02归档读取、30组评分差分及费用授权 Stub 检查。后续仅收敛常量可见性并复核相关装配/API/评分测试。

逐文件归一化比较生产源码（排除包、import、必要public、条件注解及注释）未发现逻辑变化；旧根包类引用和原评估开关引用已清除。清理了target中移动前残留的根包class文件，避免增量编译遗留造成重复Bean；未使用全量Maven clean。

未运行依赖Testcontainers的完整应用上下文测试，避免启动Docker；以真实评估包扫描加Mock外部依赖验证装配。没有真实供应商调用、全量Maven、提交、推送或合并。

最终收敛后再次执行 Spotless、编译/测试编译及装配、API、费用授权、人工评审和评分差分14项测试，全部通过；前端评估定向测试15项通过；git diff --check通过。application-local.yml与冻结运行时数据集均无本轮差异。最终Diff审查未发现API路径、业务判断或持久化字段变化。
