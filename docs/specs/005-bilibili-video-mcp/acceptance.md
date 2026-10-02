# 005 验收记录

日期：2026-10-02。

## 验收结论

阶段 005 已通过。应用保持默认关闭视频搜索；启用 `video-mcp` profile 后，使用 AgentScope Java 的 stdio MCP client 启动固定版本 `@wangshunnn/bilibili-mcp-server@0.0.7`，调用 `search_videos` 并返回标准化的 B 站视频结果。

## 验证证据

| 验收项 | 命令 | 结果 |
| --- | --- | --- |
| Provider 映射与 MCP client 生命周期 | `mvn "-Dtest=BilibiliMcpCookingVideoSearchProviderTest,AgentScopeBilibiliMcpClientTest,CookingVideoSearchServiceTest" test` | 8 个测试通过，0 失败，0 错误 |
| 真实 MCP 联调 | `mvn "-Dtest=BilibiliMcpLiveTest" "-Ddiet.video-mcp.live=true" test` | 1 个测试通过；协商协议 `2024-11-05`，服务端 `bilibili-mcp-server 0.0.7`，真实检索“番茄炒蛋”返回可解析结果 |
| 前端 Markdown 与视频卡片 | `node --test src/test/js/chat-renderer.test.js` | 6 个测试通过；覆盖安全 Markdown、危险链接、B 站 URL 白名单、卡片内容、新窗口属性和页面接入 |
| 全量回归 | `mvn test` | 99 个测试，0 失败，0 错误，7 个按条件跳过 |
| 构建产物 | `mvn -DskipTests package` | 通过 |

## 已确认行为

- `ANY` 与 `BILIBILI` 平台请求走 Bilibili MCP；`DOUYIN` 返回明确的未配置错误，不静默切换平台。
- MCP 返回的标题会移除 HTML 高亮标签，BV 号会转换为 `https://www.bilibili.com/video/{bvid}`。
- MCP 无结果时返回空列表；工具缺失、进程失败或响应格式不可解析时返回明确错误，不生成虚假链接。
- 默认配置仍使用 disabled provider。只有显式启用 `video-mcp` profile 才会启动外部 MCP 进程。
- 当前实现无需 Cookie 或 API Key，不读取收藏、评论和字幕，也不下载视频。
- 视频结果通过 `videoBlocks` 返回，`speechText` 只保留简短引导；卡片不会向浏览器暴露 MCP source 和内部 query。
- 菜谱回答使用稳定 Markdown 分节，前端渲染前会转义原始 HTML，只开放受控格式与 HTTP(S) 链接。

## 限制

- 所选 MCP package 通过非官方 B 站公开搜索接口取数，适合作为本地练习与面试演示。用于生产环境前需要重新核对平台授权、服务协议、限流和稳定性。
- 首次调用可能需要由 `npx` 下载并启动固定版本 package，因此通常比后续调用慢。IDEA 没有继承 Node.js PATH 时，需要显式配置 `diet.video-mcp.command`。
- 真实联调测试默认跳过，避免日常 `mvn test` 依赖外网和第三方平台状态。
