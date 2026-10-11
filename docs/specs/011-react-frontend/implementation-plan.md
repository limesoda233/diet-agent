# React Frontend Implementation Plan

> For agentic workers: use subagent-driven-development. Steps use checkboxes.

**Goal:** 全部前端迁移 React，固定聊天输入区并修复键盘与表单体验。
**Architecture:** frontend/ 独立 TypeScript SPA；类型化 API 与用户 Context，功能页独立。Vite 开发代理，生产构建生成 Spring Boot static。
**Tech Stack:** React、TypeScript、Vite、React Router、Vitest、Testing Library、react-markdown、remark-gfm。
**Spec:** [spec.md](spec.md)，用户已确认。

实施完成；下面保留任务分解作为设计依据，最终测试与独立审查结果以 [acceptance.md](acceptance.md) 为准。过程中增加了切页保留对话、软键盘视口适配、后端计算槽位字段往返与删除响应保护。

## Global Constraints

- 原 `/api/v1/diet`、X-User-Id、草稿与知识、Trace及评测契约保持。
- 不改 MySQL推荐/RAG/MCP/冻结评测数据；秘密只在后端。
- Enter发送/Shift+Enter换行/中文composition不发送；消息独立滚动。
- 换用户/新会话丢弃过期响应；新草稿只有最新版本可保存。
- 管理页生成不保存，知识字段与现有来源兼容。
- 受控测试不得称作真实模型效果。

## Task 1: 工程与共享契约

Files: frontend/package.json、锁文件、tsconfig、vite config、index、src/api/types.ts、client.ts、context/AppContext.tsx、test/setup.ts。

- [ ] 写 API 测试，验证身份头与 JSON/204/错误；运行 `npm test` 查看失败。
- [ ] 实现 `createApi(userId:string)` 返回所有原API方法，组件仅捕获当前用户实例，不读变化中的全局身份。
- [ ] 导出 SlotKey、Meal、MealKnowledge、MealDraft、ChatResponse、Trace、EvaluationReport 等实际后端类型。
- [ ] AppProvider 暴露 `userId,setUserId,api,mealVersion,refreshMeals`；切换身份导致功能状态重建。
- [ ] `npm run build` 使用 Vite outDir `../src/main/resources/static`，只在旧静态源码退役后启用清空该生成目录；早期临时dist配置避免覆盖旧代码。
- [ ] 共享接口通过后供后续功能消费，提交本任务。

## Task 2: 聊天

Files: frontend/src/features/chat/ChatPage.tsx、MessageContent.tsx、Composer.tsx 与测试。
Consumes createApi / AppContext frozen contract。

- [ ] 先验证 Enter/Shift/IME、失败留输入、重复提交、过期响应、草稿操作与feedback的失败测试。
- [ ] 实现viewport flex聊天头、滚动messages与底部composer；无强制历史滚动。
- [ ] Markdown安全渲染、recipe citation、Bilibili卡、displayBlocks、planBlocks、草稿卡与最新revision动作。
- [ ] 保存成功刷新mealVersion，Trace链接指向调试页，用户/来源/新会话隔离。
- [ ] 组件测试与类型检查，提交。

## Task 3: 餐食

Files: frontend/src/features/meals/MealsPage.tsx、MealEditor.tsx 与测试。

- [ ] 先验证生成只回填、必填阻止、知识新增与种子编辑保留、防重/stale/失败留字段。
- [ ] 个人库搜索/标签筛选、CRUD确认、口语预览与编辑知识；公共库只读。
- [ ] 加载/错误/空态就近提示；旧异步结果身份/编辑epoch保护。
- [ ] 组件测试、类型检查，提交。

## Task 4: 调试与外壳

Files: frontend/src/features/debug/TracePage.tsx、EvaluationPage.tsx、app/App.tsx、HomePage.tsx、styles.css。

- [ ] 测试Trace label合法JSON/详情、evaluation真实接口payload/错误恢复。
- [ ] 迁移时间筛选、session trace、事件时间线、标注与评测指标/明细。
- [ ] 完成hash导航、首页、mobile sidebar、颜色token/可见focus与responsive。
- [ ] 集成其余页，生产build、组件测试。

## Task 5: 退役与验收

- [ ] 删除已替代的4个旧静态源文件、旧原生JS fixture测试；保留新测试对应行为。
- [ ] Vite生产输出static；ignore generated/node_modules/reports，锁文件保留。
- [ ] Maven frontend profile 使用本机npm在generate-resources构建，不让普通mvn test依赖Node安装。
- [ ] 文档说明双服务开发、单服务IDE、干净checkout的frontend profile打包命令。
- [ ] npm test/typecheck/build、mvn test/package、mvn -Pfrontend -DskipTests package。
- [ ] 受控Playwright桌面/手机键盘、scroll、主要API流程截图走查；独立审查修缺口。
- [ ] 公共验收记录与本地提交，未授权不push。
