# Knowledge Assistant Web UI V2

UX-02 交互原型使用 Vue 3、TypeScript、Vite、Vue Router 和 Element Plus。Markdown 渲染使用 markdown-it、DOMPurify 和 highlight.js。

## 本地启动

在仓库根目录执行以下命令，然后打开 Vite 输出的本地地址（默认 http://localhost:5173）。

```sh
cd frontend
npm ci
npm run dev
```

## Mock 模式

启动后即进入 Mock 模式，无需环境变量、后端服务、数据库或模型密钥。登录可使用任意非空账号和至少 6 位的密码。示例数据和操作结果由前端生成，业务数据按账号保存在当前浏览器的 localStorage 中，登录状态保存在 sessionStorage 中。

## 当前功能

- 账号注册、登录和退出。
- 知识库创建、编辑和删除。
- 文档导入、处理状态、失败重试、详情和分块查看。
- 按知识库进行检索，配置检索策略及 Top K，查看命中的片段。
- 多轮问答、会话管理、模拟流式回答和引用查看。
- 长期记忆管理及启用开关。

## 限制

账号校验、文档处理、检索及模型回答均为本地模拟。文档导入仅支持不超过 2 MiB 的非空 UTF-8 Markdown 或 TXT 文件。数据仅在当前浏览器中保存，清理站点数据后，本地修改会丢失。
