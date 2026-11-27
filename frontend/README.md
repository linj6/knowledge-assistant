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

## 业务演示

知识库和文档管理使用真实 API。检索、问答和长期记忆仍使用 Mock，演示数据按真实账号 ID 保存在当前浏览器的 localStorage 中，仅在当前浏览器可用。清理站点数据后，本地修改会丢失。原型阶段的演示账号数据保留在浏览器，真实账号使用独立的存储键。
