# 005 哔哩哔哩做饭视频 MCP

状态：2026-10-02 已由用户授权实施。

## 目标

为现有 `search_cooking_videos` 业务入口增加真实的哔哩哔哩关键词搜索。Spring Boot 通过 AgentScope Java 1.0.11 的 stdio MCP 客户端启动固定版本的第三方 MCP Server，并把结果映射为稳定的 `CookingVideo` 领域对象。

## 选型

- 第一版选择哔哩哔哩。`@wangshunnn/bilibili-mcp-server@0.0.7` 提供无需登录的 `search_videos` 工具，参数为 `keyword/page/count`。
- 抖音官方关键词搜索需要 `video.search` 权限、用户授权、预先维护关键词，而且官方接口只返回最近一天的视频，不适合作为练习项目的稳定教程检索源。
- 第三方 Bilibili MCP 使用非官方公开搜索接口。它适合本地练习和演示；若产品化，必须改用获得明确授权的接口或完成平台接入评审。

## 功能边界

1. 仅搜索公开视频元数据：标题、UP 主、BV 号、时长和播放页 URL。
2. 不保存 Cookie，不下载视频，不读取评论、弹幕或字幕。
3. `BILIBILI` 与 `ANY` 使用哔哩哔哩 provider；`DOUYIN` 明确返回不支持。
4. MCP 包必须固定为 `0.0.7`，不得在运行时使用 `latest`。
5. 启用真实视频 provider 后，应用就绪时异步预热 MCP；请求复用共享初始化与 client。单次请求超时不得中断共享初始化，初始化独立限时，应用关闭时释放；初始化或调用失败不得产生伪造结果。
6. 普通推荐不得隐式调用视频搜索。菜谱查询的视频调用规则已由 006 覆盖：仅在启用 `video-mcp` profile 后，菜谱 RAG 与视频搜索才会并行执行；默认配置仍关闭视频分支。
7. 聊天接口通过 `videoBlocks` 返回标题、播放页 URL、平台、UP 主和时长；不得把播放链接拼入 `speechText`。
8. 前端只接受 `https://(www.)bilibili.com/video/BV...` 播放页，把每个结果渲染为整卡可点击的视频卡片，并使用 `target="_blank"` 与 `rel="noopener noreferrer"` 在新窗口安全打开。

## 配置

新增 `video-mcp` Spring profile。默认主配置仍禁用视频搜索。菜谱查询的并行编排与该规则的更新以 [006 并行规格](../006-parallel-recipe-video-ragas/spec.md) 为准。启用 profile 后使用：

```text
--spring.profiles.active=recipe-rag,video-mcp
```

可配置命令、参数、初始化超时和请求超时（视频搜索默认请求超时为 3 秒），Windows 默认命令为 `npx.cmd`，其他系统为 `npx`。

## 验收

- MCP client 初始化后发现并调用 `search_videos`，参数包含增强后的做饭关键词和 1～10 的 count。
- provider 能解析标题、UP 主、BV 号和 `MM:SS`/`HH:MM:SS` 时长，生成标准 Bilibili URL。
- HTML 高亮标签不会进入最终标题。
- `ChatResponse` 使用 `VIDEOS` 响应类型和结构化 `videoBlocks`，不会暴露 MCP source 或内部 query。
- 桌面端视频卡片支持双列布局，移动端降为单列，整张卡片可通过鼠标或键盘打开新窗口。
- MCP 错误、返回格式变化、空结果和不支持的平台都有明确行为。
- 默认离线测试不启动 npx；真实联调通过 opt-in 测试或手工命令执行。

## 参考

- AgentScope MCP 文档：https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/tool.md
- Bilibili MCP：https://github.com/wangshunnn/bilibili-mcp-server
- 抖音官方关键词视频搜索：https://open.douyin.com/platform/resource/docs/openapi/search-management/keywords-video-list/keywords-video/
