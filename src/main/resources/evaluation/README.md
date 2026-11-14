# Evaluation 运行时资源

- answer-evaluation-v1.json：正式 D01 冻结 Gold，测试与运行时共用；身份按规范化 freezeFingerprint 校验，不按原始文件换行符校验。
- retrieval-metrics-v1.json、m4-01-gold-evidence.json：历史读取所需的冻结材料。检索 fixture 的 src/test/resources 归档保留，用于 C01/C02 原路径和 inputSha256 回放；运行时通过 ClassPathResource 读取此处副本。禁止独立修改任一冻结副本。
- 来源原文仍按冻结 documentPath 定位，部署时需要提供对应原文目录；此处不包含外部知识文档。

Run 文件仍保存在 data/evaluation；EvaluationRunStore 是保存及读取的唯一边界。损坏协议或 JSON 返回 422，缺失文件返回 404，保存失败返回 500。临时 .pending 目录不会出现在有效 Run 列表中。
