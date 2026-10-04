# 哔哩哔哩视频 MCP 运行手册

## 运行依赖

- Node.js 20 或更高版本；当前本机为 Node.js 24。
- `npx` 能被启动应用的进程找到。Windows 默认命令是 `npx.cmd`，其他系统默认 `npx`。
- 首次搜索需要访问 npm 下载固定包 `@wangshunnn/bilibili-mcp-server@0.0.7`，后续使用 npm 缓存。
- 搜索为只读操作，不需要 Bilibili Cookie。

检查：

```powershell
node --version
npx --version
where.exe npx
```

## 启动菜谱与视频并行查询

在 IDEA 的 `DietApplication` Run Configuration 中，将 Active profiles 改为：

```text
recipe-rag,video-mcp
```

Program arguments 保留菜谱 RAG 配置：

```text
--diet.recipe-rag.enabled=true
--diet.recipe-rag.corpus-path=E:/Mycode/HowToCook
```

如果 IDEA 找不到 npx，在 Program arguments 增加实际路径，例如当前机器：

```text
--diet.video-mcp.command=D:/nvm/npx.cmd
```

PowerShell 等价命令：

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag,video-mcp --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook"
```

启用 `video-mcp` 后，菜谱查询会并行启动 RAG 与视频分支。MCP Server 在第一次视频分支调用时惰性启动；profile 未启用时，默认配置不会启动视频分支。纯视频请求仍可单独触发视频搜索，例如：

```text
帮我找番茄炒蛋的 B 站教学视频
```

聊天接口返回结构化 `videoBlocks`。页面会展示视频标题、UP 主、平台和时长；点击卡片或“打开视频”会在新窗口进入 B 站播放页。

普通餐食推荐不会调用视频 MCP。仅启用 `recipe-rag` 时，文字菜谱问答保持纯 RAG；同时启用 `video-mcp` 后，菜谱查询并行搜索证据与视频。

## 配置

配置文件为 `application-video-mcp.yml`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `diet.video-mcp.enabled` | `true`（仅该 profile） | 视频搜索开关 |
| `provider` | `bilibili-mcp` | 真实 Bilibili MCP provider |
| `timeout` | `3s` | 单次视频搜索总超时 |
| `initialization-timeout` | `30s` | stdio MCP 握手超时 |
| `default-limit` | `3` | 默认返回数量，业务层限制为 1～10 |
| `arguments` | `-y`、固定 npm 包 | 不使用 `latest` |
| `search-tool` | `search_videos` | 固定 MCP 工具名 |
| `query-suffix` | ` 做法 教程` | 提高做饭视频相关性 |

`DOUYIN` 当前明确返回 `douyin_video_search_not_configured`。若用户未指定平台，`ANY` 使用 Bilibili。

## 验证

离线测试不会启动 npx：

```powershell
mvn "-Dtest=AgentScopeBilibiliMcpClientTest,BilibiliMcpCookingVideoSearchProviderTest,CookingVideoSearchServiceTest" test
```

真实只读搜索：

```powershell
mvn "-Dtest=BilibiliMcpLiveTest" "-Ddiet.video-mcp.live=true" test
```

真实 MCP 测试是 opt-in 的；上面的命令会启动固定版本的第三方 MCP Server 并访问公开视频搜索。是否能够运行取决于本机 Node.js、npm 网络访问和 Bilibili 上游可用性。该测试只覆盖 provider 的实时搜索，不能单独证明完整应用响应与 Trace 的端到端行为。

手工验收完整编排时，以同一菜谱查询检查以下四种情形，并查看 Trace 中两个分支的终态事件：

| 情形 | 操作 | 预期响应 |
| --- | --- | --- |
| 两分支成功 | 同时启用 `recipe-rag,video-mcp`，询问“宫保鸡丁咋做啊” | 有证据支持的文字菜谱、引用和视频卡片；Trace 有 RAG 与视频成功终态及组合事件 |
| MCP 停止 | 停止/阻断 MCP 子进程后再次询问 | 视频分支失败或超时并记录降级；RAG 仍返回带引用的文字菜谱 |
| 无 RAG 证据、视频成功 | 对语料中无可信证据的菜名发问，保持视频 MCP 可用 | 只说明暂无可信文字菜谱并展示视频卡片；不得把视频元数据当作食谱事实 |
| 两分支均不可用 | 使菜谱检索不可用且停止 MCP 后询问 | 返回暂不可用说明，不暴露内部异常或编造内容 |

视频分支的 3 秒总预算包含 MCP 初始化等待和搜索，不会在冷启动时额外叠加 30 秒。30 秒仅是 MCP 内部初始化的限制；首次下载或进程启动可能使本轮视频分支超时降级，RAG 结果仍会保留。手工验收时以响应和 Trace 中记录的分支状态为准。

## 故障定位

- `Cannot run program npx.cmd`：IDEA 的 PATH 没有 Node；配置 `diet.video-mcp.command` 为 `where.exe npx` 返回的完整 `.cmd` 路径。
- `bilibili_mcp_tool_missing`：固定 MCP 包没有暴露预期的 `search_videos`；不要自动切换未审查的新版本。
- `bilibili_mcp_search_failed`：上游 Bilibili 搜索失败或限流；当前请求返回不可用，不生成假链接。
- `bilibili_mcp_unrecognized_response`：第三方包输出格式发生变化，需要先更新解析测试再升级。
- 第一次调用可能较慢：通常涉及 npx 下载和进程启动；视频分支超时后仍会保留 RAG 结果。

该 MCP 包使用非官方公开搜索接口。当前实现仅用于本地练习和面试演示；若产品化，应完成平台授权与服务条款评审。
