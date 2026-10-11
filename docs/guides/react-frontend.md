# React 前端运行与阅读

维护源码在 `frontend/src`，React + TypeScript + Vite；Spring Boot 的 `src/main/resources/static` 是生成目录，不能手改。依赖与构建产物、浏览器报告都已忽略，`frontend/package-lock.json` 必须提交。

## IDEA 或单地址运行

需要本机 Node.js 22+ 和 npm。首次在项目根目录执行：

```powershell
npm --prefix frontend ci
npm --prefix frontend run build
```

再在 IDEA 启动 `DietApplication`，或运行 `mvn spring-boot:run`。打开 `http://localhost:8080`，默认进入聊天。原 Java profiles、数据库和 API 环境变量继续配置在后端；前端不保存 API Key。修改前端源码后重新 build，浏览器刷新；若 IDE 未复制新资源，重新启动 Java。

打包含前端的 JAR（干净检出使用这个命令）：

```powershell
mvn -Pfrontend -DskipTests package
```

该 profile 自动执行 npm ci 和 build，然后 Spring Boot 打包静态资源。Windows 自动选择 npm.cmd。普通 `mvn test` 无需 Node；普通 `mvn -DskipTests package` 只使用当前已有的生成资源，因此首次必须先构建前端，或使用 frontend profile。

## 前端开发

先启动 Spring Boot，再在另一个终端执行：

```powershell
npm --prefix frontend run dev
```

访问终端显示的 Vite 地址（默认 http://localhost:5173），前端热更新；`/api` 代理到 http://localhost:8080，修改端口时调整 `frontend/vite.config.ts`。hash 路由兼容旧链接，无需 Spring Boot 配置 SPA fallback。

## 操作

聊天：Enter 发送、Shift+Enter 换行；中文输入法选字不发送。消息列表独立滚动，输入区留在底部。查看旧消息时不会强制拉到底，可点“回到最新”。切换页面后当前对话仍在，点击“新对话”或切换用户/数据源才重置。

个人餐食：输入口语，AI 整理后核对名称、餐次、标签、食材与做法，再保存。标签直接点击多选，不需要 Ctrl。聊天中也可以明确请求录入并确认草稿。公共餐食只读。用户编号是练习环境的请求身份切换，并非登录认证。

Trace 调试页支持聊天 traceId 链接、事件时间线、输入输出展开、JSON 和标注；意图评测页调用既有评测 API。离线 Ragas 仍使用原命令，不通过该页面启动。

## 代码入口与验证

- `src/app/App.tsx`：导航、身份切换和路由外壳。
- `src/api/client.ts` / `types.ts`：按用户固定的 API 实例与后端 JSON 契约。
- `src/context/AppContext.tsx`：身份与餐食缓存失效版本。
- `src/features/chat`：输入、消息、Markdown/视频、草稿动作。
- `src/features/meals`：列表、标签、编辑器、草稿与知识保存。
- `src/features/debug`：Trace 与意图评测。

```powershell
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
mvn test
mvn -DskipTests package
```

测试使用受控 fetch 响应，浏览器走查使用本地固定接口；真实 API/模型与视频服务仍需在配置完成后自行抽查。
