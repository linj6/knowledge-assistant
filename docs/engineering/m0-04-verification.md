# M0-04 验收记录

2026-11-06，feature/m0-foundation。

- `mvn clean verify`：BUILD SUCCESS，20 tests，0 failures/errors/skipped。
- MockMvc ApiBaselineTests：8 项；配置 ConfigurationTests：5 项；原有测试：7 项。
- `git diff --check`：通过。
- 离线运行，无 Docker、数据库或模型服务依赖。
- 404 保持 MVC 默认行为；只支持同步 MDC 生命周期，不实现跨线程传播。
- 未知异常日志保留关联 ID、错误码和异常类型，主动省略可能带敏感内容的消息与堆栈。
- 配置启用仅表示启动校验，真实模型客户端在 M0-06 实现。
- 本地详细构建日志：target/m004-verify.log；测试报告：target/surefire-reports。
## M0-04b 错误语义与异常处理重构

- ErrorCode 接口和 CommonErrorCode 枚举集中定义稳定字符串错误码及安全文案。
- BusinessException 只接受 ErrorCode，不依赖 Spring MVC，不开放任意响应消息构造器。
- ApiError 提取为独立 record；ApiExceptionHandler 负责 HTTP 转换。
- 业务异常公共码 INVALID_ARGUMENT/METHOD_NOT_ALLOWED/UNSUPPORTED_MEDIA_TYPE/INTERNAL_ERROR 分别映射 400/405/415/500；领域自定义码默认 400。映射集中在 Web 层，不判断具体领域枚举。
- 响应字段仍为 code/message/requestId，原有中文消息及 400/405/415/500 行为不变，404 仍使用 MVC 默认处理。成功响应不包装。
- 业务日志仅记录 RequestId 和错误码；未知异常保留原安全日志策略。RequestIdFilter 未修改，异步传播边界不变。
- 参考本地 interview-ai 对应四个类型的职责划分，未引入其数字码、Result 或领域判断。
- 实测 `mvn clean verify`：BUILD SUCCESS，23 项全部通过（原有 20 项 + 新增业务异常 MockMvc 3 项；MockMvc 共 11 项），0 failures/errors/skipped。
- 新增测试覆盖公共码 HTTP 映射、自定义字符串码、安全文案、敏感异常消息不进入响应或业务日志、响应头与正文关联、MDC 清理。
- `git diff --check`：通过。详细日志 target/m004b-verify.log，报告 target/surefire-reports。
- 无新增外部依赖、业务接口或未解决阻断问题。
