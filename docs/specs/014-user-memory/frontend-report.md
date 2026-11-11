# 014 用户长期记忆：前端实施报告

日期：2026-10-20。范围：Task 4 前端；未修改后端、数据库或公共菜谱索引。

## 页面与交互

- 新增 `#/diet/memories` 饮食记忆页面及 Lucide Brain 导航入口。饮食助手和调试导航按路径分组，避免新增入口破坏原来的 `slice(4)` 分界。
- 复用现有 `page`、`panel`、`card`、`field`、`button` 和语义颜色变量。记忆采用单列卡片，显示类型、状态、本人明确表达/推断偏好、口味或菜系态度、原话证据。
- 待确认记录可确认长期生效；编辑保留原值，空值有字段关联错误，最长烹饪时间要求正整数。过敏原编辑使用“确认并保存修改”，说明保存即确认修改后的长期过敏记忆。
- 撤销有单独确认和取消步骤；成功后依据服务端返回 `DELETED` 删除对应卡片。所有修改带当前 `version`，不在客户端伪造下一版本或生效状态。
- 收集开关只发送 `collectionEnabled`，收到成功快照后更新。明确说明关闭不会删除已有记忆。
- 初次加载、空记录、未启用、加载/操作失败均有对应状态；错误保留原记录，可重新加载。未启用仅显示“饮食记忆暂未启用”，不展示配置键或实现指导。
- 继续使用练习环境用户编号；侧栏明确说明该编号用于数据隔离，并非身份认证。

## API 与异步边界

通过现有 `createApi` 请求客户端调用：

- `GET /api/v1/diet/memories`
- `PUT /api/v1/diet/memories/settings`，仅 `{collectionEnabled}`
- `PATCH /api/v1/diet/memories/{id}`，仅 `{version,action}` 或 `{version,action:'UPDATE',value,stance}`；id 使用路径编码。

请求客户端捕获创建时的 `X-User-Id`。记忆页面以 `userId` 为 key 重建内容；卸载标记和加载序号阻止旧请求更新状态。修改期间使用同步 ref 拒绝重复修改并禁用相关操作/刷新；切换用户后旧修改成功或失败都不写入新页面。操作成功采用服务端返回的记录/快照，失败不做乐观成功展示。

## 聊天与反馈

- `ChatResponse.memoryNotices?: string[]` 在回答气泡外作为独立轻量 `role=status` 提示展示，并链接“管理饮食记忆”。只展示服务端提供的提示，不自行宣称后台归档成功。
- 提示和证据均通过 React 文本节点渲染；带 HTML 的输入不作为 HTML 执行。原回答与引用渲染保持原逻辑。
- `FeedbackRequest.traceId?: string` 从对应回答的 `response.traceId` 提取；没有 trace 时省略该字段，保留现有反馈兼容性。

## TDD 与验证

先新增 API、页面和提示测试，运行：

`npm --prefix frontend test -- src/api/client.test.ts src/features/memories/MemoryPage.test.tsx src/features/chat/ChatPage.test.tsx`

红灯：11 失败、15 通过。失败原因是记忆方法、页面入口与状态提示尚不存在；随后实现后同组 26 测试通过。反馈 trace 另先修改现有反馈测试并观察缺少 `traceId` 失败，再实现传递。

新增页面 9 项测试覆盖：类型/来源/证据安全展示、待确认过敏确认、空值校验与显式编辑保存、撤销确认/取消、收集开关保留记录、版本冲突不冒充成功、加载重试与未启用状态、新用户空态及晚到旧列表、晚到旧用户操作错误。

最终验证：

- `npm --prefix frontend test`：9 文件、63 测试全部通过，退出码 0。
- `npm --prefix frontend run build`：TypeScript 检查和 Vite 生产构建成功，退出码 0；1862 modules transformed。
- 构建资源只由 Vite 写入被忽略的 `src/main/resources/static`，没有手工修改或提交生成资源。

验证使用受控 fetch 响应与真实 React 页面/上下文/请求客户端，未连接真实 API 或数据库；未进行浏览器截图或真实后端端到端验收。后端完整验证及文档总入口由主任务统一交付。
