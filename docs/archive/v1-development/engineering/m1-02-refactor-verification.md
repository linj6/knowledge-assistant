# M1-02-REFACTOR 验收记录

日期：2026-11-08。分支 feature/m1-ingestion-indexing，起点 a0e0694e2e43c7d787dc4d9ce1dd60a433e4163f，初始工作区干净。

## 协议变更

- 新增 common/api/Result<T>：Integer code、String message、T data、String requestId。成功 code=0，message=成功；失败 data=null。复用 MDC，不新增追踪机制。移除 ApiError。
- 三个业务 Controller 方法直接返回 Result，创建使用 @ResponseStatus(CREATED) 和响应 Location；保留真实 HTTP 状态。
- 错误码显式分配：40000 参数非法、40400 资源不存在、40401 知识库不存在、40500 方法不支持、41500 媒体类型不支持、50000 内部错误；0 专用于成功。新增错误码需分配未使用的整数，不能依赖枚举顺序。测试验证公共码唯一且与成功和测试自定义码无冲突。
- ErrorCode 改为 Integer，BusinessException 继续携带同一 ErrorCode 定义，异常处理器统一产生 Result<Void>，安全消息和日志脱敏边界保持。
- 路径参数及响应 ID 使用 Long，MVC 自动绑定并通过 @Positive 校验。非法、溢出、负数和零返回 400；按 Spring Long 绑定规则，带正号的合法整数也可绑定。
- Boot 4.1.2 的 Jackson 3.1.7 使用 JsonMapperBuilderCustomizer 注册 Long/long 的 ToStringSerializer；不手动转换 DTO ID。影响所有 Long/long 字段，包括非 ID 字段；Integer code 仍为 JSON 数字。
- application.yml 设置 spring.mvc.servlet.path=/api，Controller 映射 /v1/knowledge-bases，最终 URL 不变。Location 根据真实请求构建，保留 servlet 前缀和上下文。M0 standalone 测试映射去除手写 /api；真实服务器单独验证 YAML 生效。
- MVC 内的资源 404 也使用统一 Result。servlet 映射之外的容器请求不属于业务 Controller 协议。

成功示例：`{"code":0,"message":"成功","data":{"id":"9007199254740993","name":"Java","description":null,"createdAt":"2026-11-08T00:00:00Z"},"requestId":"safe-123"}`。

失败示例（HTTP 404）：`{"code":40401,"message":"知识库不存在","data":null,"requestId":"safe-123"}`。

## 实际验证

```powershell
mvn spotless:apply
mvn spotless:check
mvn -DskipTests test-compile
mvn "-Dtest=KnowledgeBaseApiTests,KnowledgeBasePersistenceTests,ApiBaselineTests,BusinessExceptionTests" test
git diff --check
```

最终格式检查、编译通过；19 项测试，0 失败、0 错误、0 跳过。包含创建/列表/详情 Result、201/Location、数值成功码和错误码唯一性、400/404/405/415/500、requestId、超 JS 安全整数的 ID 字符串、MVC Long 绑定和非法 ID 边界。

真实 Testcontainers PostgreSQL 17.6 / pgvector 0.8.0 回归通过，保留原持久化/事务异常边界验证；新增真实 Tomcat HTTP 创建与详情、公共前缀生效及无前缀路径 404、Location、响应头 requestId 关联及 Boot JsonMapper 非 ID Long 字符串验证。

过程中一次未加引号的 Maven 属性查询被 PowerShell 拆分，已改为检查本地实际依赖；一次编辑中的格式检查失败，重新 apply 后最终检查通过。没有将工具问题作为代码缺陷。

已审阅完整 diff。未修改迁移、实体、Repository 或其他 M1 业务；未调用模型、ES、MinIO，未执行全量测试。无已知任务阻断项；本次协议为有意的不兼容变更，调用方须按 Result.data 读取成功数据并采用整数错误码。未进入 M1-03。
