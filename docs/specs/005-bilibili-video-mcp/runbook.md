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

## 启动完整应用

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

应用启动不会立即下载或启动 MCP Server。第一次明确询问视频时才惰性启动，例如：

```text
帮我找番茄炒蛋的 B 站教学视频
```

普通餐食推荐和文字菜谱问答不会调用视频 MCP。

## 配置

配置文件为 `application-video-mcp.yml`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `diet.video-mcp.enabled` | `true`（仅该 profile） | 视频搜索开关 |
| `provider` | `bilibili-mcp` | 真实 Bilibili MCP provider |
| `timeout` | `30s` | 单次视频搜索总超时 |
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

## 故障定位

- `Cannot run program npx.cmd`：IDEA 的 PATH 没有 Node；配置 `diet.video-mcp.command` 为 `where.exe npx` 返回的完整 `.cmd` 路径。
- `bilibili_mcp_tool_missing`：固定 MCP 包没有暴露预期的 `search_videos`；不要自动切换未审查的新版本。
- `bilibili_mcp_search_failed`：上游 Bilibili 搜索失败或限流；当前请求返回不可用，不生成假链接。
- `bilibili_mcp_unrecognized_response`：第三方包输出格式发生变化，需要先更新解析测试再升级。
- 第一次调用较慢：通常是 npx 下载和进程启动；当前本机首次真实联调约 13 秒。

该 MCP 包使用非官方公开搜索接口。当前实现仅用于本地练习和面试演示；若产品化，应完成平台授权与服务条款评审。
