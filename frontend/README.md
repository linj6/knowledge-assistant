# Knowledge Assistant Web UI V2

## 本地启动

按[开始使用](../docs/getting-started.md#应用配置与启动)配置本地 PostgreSQL、Redis 等基础设施。在仓库根目录通过 IDEA 启动后端，或执行以下命令：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

另开终端启动 Vite，然后打开输出的本地网址（默认 http://localhost:5173）：

```sh
cd frontend
npm ci
npm run dev
```

Vite 将同源 `/api/` 请求原样转发到 `http://127.0.0.1:8080`。注册、登录、查询当前用户和退出使用 Spring Security Session。每次写请求先读取 Session 对应的 CSRF Token。

本地可运行 `npm run typecheck` 和 `npm run build` 验证类型与生产构建。正式打包和 Docker 部署见[开始使用](../docs/getting-started.md#jar-和-docker-镜像)。
