# 005 验收记录

## 2026-10-09 冷启动超时修复

用户报告 `InterruptedException` 指向 `initializedClient` 中的 `Mono.block()`，同时出现 Reactor `onErrorDropped`。用延迟初始化的受控 MCP client，外层视频搜索超时稳定复现相同堆栈：取消请求会中断并关闭初始化，下一次请求不能复用就绪的 client。

本次改用 AgentScope async wrapper、共享异步初始化与应用就绪预热。单个请求超时不会取消公共初始化；握手和工具发现仍独立限时，失败候选清理，后续请求可重试初始化，应用关闭不会继续使用 client。SDK 的普通 request timeout 同样影响握手，因此 SDK 内部预算覆盖初始化，实际工具搜索再单独限制为 3 秒。

验证结果：

- 冷启动取消回归先失败并产生用户同款堆栈；修复后通过。覆盖并发初始化复用、失败重试、初始化超时清理、关闭中止和独立搜索超时。
- `mvn "-Dtest=AgentScopeBilibiliMcpClientTest,BilibiliMcpWarmupTest,BilibiliMcpLiveTest" "-Ddiet.video-mcp.live=true" test`：13 项，0 failures/errors/skips，其中两项真实 B 站 MCP 搜索通过；新增 live 测试在预热完成后使用生产默认的 3 秒搜索预算。
- 独立审查补充了“应用关闭时工厂晚返回”的资源释放竞争，回归先失败、修复后通过；晚返回 client 恰好关闭一次，不执行初始化或工具搜索。
- `mvn "-Dtest=AgentScopeBilibiliMcpClientTest,BilibiliMcpWarmupTest" test`：12 项通过。
- `mvn test`：202 项，0 failures/errors，9 项 opt-in/条件跳过。真实 MCP 已由上面的显式命令单独验证。
- 完成关闭竞争修复后再次运行 `mvn "-Dtest=BilibiliMcpLiveTest" "-Ddiet.video-mcp.live=true" test`：两项真实搜索均通过，包括预热后 3 秒生产搜索预算；没有复现旧中断堆栈。

这次 live 验证覆盖真实 client/provider/service 搜索，没有启动完整聊天 HTTP 应用。更新代码后需重新编译并重启 IDEA 中的应用，保留 `video-mcp` profile；等待 `Bilibili MCP ready; search tool verified` 日志后测试。预热未完成时的单次请求仍可能超时降级，不能将预热理解为永不超时。

以下为 2026-10-02 的历史验收。

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
