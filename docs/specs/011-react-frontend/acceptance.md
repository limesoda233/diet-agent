# React 前端重写验收

日期：2026-10-11。规格已确认；整体审查发现的问题已修复并独立复审通过。

## 交付

- React/TypeScript/Vite 替代旧原生前端，全部源码及测试在 `frontend/`。
- 聊天为默认入口，深绿导航与白底内容；桌面固定输入区，消息独立滚动，手机导航折叠。
- Enter 发送、Shift+Enter 换行、中文组合输入不误发；输入自动增高、失败保留、提交防重、回到最新；切页保留对话。
- 迁移推荐、三餐计划、反馈、Markdown/引用、Bilibili 视频、草稿预览/确认/取消，草稿包含原话和描述/做法供核对。
- 个人餐食 CRUD、口语整理、知识编辑、标签直接点选；公共餐食只读。网络写入锁独立于表单清空；删除旧响应不清空新编辑；标签失败可单独重试。
- Trace 筛选、详情时间线、标注及 deep link；意图评测指标和明细。兼容后端计算字段 `empty` 的标注往返，但不放宽任意未知字段。
- 用户身份按 API 实例固定；新用户/会话旧响应丢弃；公共检索/后端数据域/默认开关与锁定 qrels 保留。

## 验证证据

最终 `npm --prefix frontend test`：8 个测试文件，52 项通过，0 失败。覆盖 API 身份、Enter/IME、过期对话/草稿、文本保存刷新、知识新增/编辑、mutation 并发保护、标签恢复、Trace 往返和错误类型、调试异步竞态。

`npm run typecheck`、生产 Vite 构建成功。`mvn test`：346 项，0 失败/错误，10 项外部服务 opt-in 跳过。`mvn -DskipTests package`、`mvn clean -Pfrontend -DskipTests package` 成功；检查 JAR 确认包含生成的 index.html 及一组当前 CSS/JS，不含旧原生入口。

受控 Chrome/Playwright 加载实际生产构建，验证桌面 1440×900 与手机 390×844、长菜谱滚动输入区位置、Enter/Shift、聊天草稿保存、管理页生成不自动保存、Trace 标注与评测页面，未出现页面异常。截图和测试脚本保留在忽略的 `tmp/`。

移动键盘使用 `interactive-widget=resizes-content` 与 visualViewport 高度/偏移监听，组件测试验证打开/收起与位置更新；实体手机的软键盘、浏览器缩放和不同 iOS/Android 版本未逐一手工验收。浏览器 API 使用固定本地响应，结果不代表真实模型效果或真实视频平台可用性。

## 构建约定

`src/main/resources/static` 是忽略的生成目录，原生 JS/CSS 与旧替身测试已由 Git 记录删除，可从历史恢复。干净检出使用 `mvn -Pfrontend -DskipTests package`；IDE 单服务先运行 npm ci/build；开发热更新使用 Vite API 代理。见 [运行指南](../../guides/react-frontend.md)。
