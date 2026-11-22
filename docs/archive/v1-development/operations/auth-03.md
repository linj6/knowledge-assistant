# AUTH-03 认证业务

所有外部 URL 使用 `/api/v1/auth` 前缀，响应沿用 Result 和 RequestId。

- POST `/register`：JSON username/password，成功 200 返回公开身份；不会自动登录。用户名为 3～64 位 ASCII 字母、数字、下划线，大小写敏感；密码至少 8 个 UTF-16 单元、UTF-8 最多 72 字节，不含控制字符。非法参数 400，同名注册 409。
- POST `/login`：相同 JSON，成功 200 返回公开身份并设置 Session Cookie；不存在、错误密码、禁用账号统一 401。输入格式非法返回 400。
- GET `/me`：成功 200 返回 id（字符串）和 username 等公开身份，匿名 401。
- POST `/logout`：要求已认证且 CSRF 有效；成功 200，失效服务端 Session、清除上下文及 SESSION Cookie。

注册、登录、退出均遵守现有 CSRF 规则。先 GET `/csrf` 获取 Token；登录成功后旧 Token 被清除，退出后 Session 失效，客户端必须重新 GET `/csrf`。

登录使用 DaoAuthenticationProvider/AuthenticationManager 和 PostgreSQL 用户数据，委托 PasswordEncoder 当前写入 `{bcrypt}` 散列。认证后转换为仅含 ID、用户名的可序列化 AuthPrincipal，权限保存在 Authentication 中，凭据为 null。官方 ChangeSessionIdAuthenticationStrategy 先轮换已有 Session ID，CsrfAuthenticationStrategy 清除旧 Token，再由 HttpSessionSecurityContextRepository 显式保存；Spring Session 将 Session 持久化到 Redis。

DISABLED 阻止新的登录；已建立 Session 不逐请求重新查询数据库，不保证禁用立即踢下线，其认证可持续到退出或 Session 过期。资源所有权授权留到 AUTH-04。
