# 食谱与食材替换 MCP 运行说明

> **已退役。** 2026-10-01 起不再存在本地食谱/替换 MCP Server，以下命令仅为历史记录，请勿用于当前代码。现役运行方式见 [阶段 004 菜谱 RAG 手册](../004-recipe-rag/runbook.md)。

## 启动

默认关闭。开发环境在项目根目录执行：

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.arguments=--diet.cooking.enabled=true"
```

也可设置环境变量 `DIET_COOKING_ENABLED=true`。原应用仍需要自己的数据库和模型配置；食谱工具进程本身不启动 Spring、MySQL、Qdrant 或 LLM，不需要云 Key。不要将 MCP 的 stdio 入口当作 HTTP 服务启动。

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| diet.cooking.enabled | false | 开启做饭路径 |
| diet.cooking.timeout | 3s | 已连接后的单次工具结果等待时间 |
| diet.cooking.initialization-timeout | 20s | 初始化/发现/注册各步骤的独立等待预算 |
| diet.cooking.server-jar | 空 | 可选的本机应用 JAR；打包运行自动使用当前应用 JAR，开发运行使用类路径 |

首次使用按需启动一个子 JVM，后续调用复用。冷启动包含 JVM、SDK 加载与握手，不能把 3 秒工具等待时间当作整个首次请求的响应上限。当前客户端串行调用；等待其他请求及连接关闭清理也不计入单次工具结果等待时间。配置超时应使用正时长。

## 对话与结构化请求

沿用 `POST /api/v1/diet/chat` 的身份和会话约定。支持以下明确句式：

- `用番茄和鸡蛋找食谱`：已有食材只影响覆盖度排序，不要求食谱包含全部已有食材。
- `番茄炒蛋怎么做`、`recipe_01怎么做`：名称有多个匹配时要求指定 ID。
- `recipe_08里的牛奶可以用什么替换`：只查询该食谱、该方向的记录规则。

更准确的调用使用 `context.cookingRequest`。例如请求体中的 `context`：

```json
{
  "cookingRequest": {
    "action": "search",
    "availableIngredients": ["番茄", "鸡蛋"],
    "requiredIngredients": ["番茄"],
    "excludedIngredients": [],
    "limit": 3
  }
}
```

查看做法替换为 `{"action":"detail","recipeId":"recipe_01"}`；替换食材使用 `{"action":"substitute","recipeId":"recipe_08","ingredient":"牛奶"}`。详情也可用 `query` 指定名称。`limit` 必须是 1～5 的整数。别名使用公共食材词表；未知食材、未知字段、null 和非法类型要求澄清。

`cookingRequest` 的 required/excluded 与服务端会话限制取并集，仅作用于当前做饭请求；传空数组不能清除已有会话限制。持久化限制沿用阶段 002 的 `context.retrievalConstraints` 完整替换契约。待确认限制不会被做饭请求绕过，必须先明确确认或取消。当前食谱没有完整口味/餐次标签，存在此类会话限制时要求澄清，不能默默忽略。

结果在原 `speechText` 中展示，不生成个人菜单 mealId，不改“换一批”的已推荐 ID。食用油、盐等缺少调味料也会列出。当前没有专用食谱前端卡片。

## 工具链与数据

`CookingService → AgentScope 1.0.11 MCP Client → 独立 stdio JVM → MCP Java SDK 0.17.0 → LocalRecipeProvider`。实际完成 tools/list、Toolkit 注册与 tools/call，白名单仅 `search_recipes`、`get_recipe`、`suggest_substitutions`。本机 SDK 握手实测协商版本为 `2024-11-05`。

第一版包含 12 个 `LOCAL_EXAMPLE` 食谱和 8 条替换规则，均有来源引用和版本；食谱状态为 `example_unverified`。这些是自写演示数据，未独立核实，不是正式食谱数据库。替换有方向和特定食谱范围，不推断可逆、等量或营养等价。

替换先查询规则；有候选时再读原食谱，按“原食材集合减去目标、加上替代品”核对最终集合及完整限制。原食谱只作为核对证据，不展示成可食用建议，所以允许其中包含正在被替换的禁用食材。搜索用 1 次调用，按名称查详情通常 2 次，替换最多 2 次；每轮封装另设 3 次硬上限。

输出采用记录驱动的确定模板，不额外调用 LLM。未知比例明确未记录；工具失败或非法结果不生成替代事实。Trace 的 `COOKING_TOOL_CALLED` 包含工具、规范参数、公共记录、耗时与错误类型；`success` 表示协议调用返回正常，业务仍会继续检查内容。

服务端 stdout 只用于 JSON-RPC，日志写 stderr；应用关闭释放客户端和子进程。客户端创建唯一临时 PID 文件，由服务端写入 PID，校验父进程后保存句柄，不依赖 Windows 上可能缺失的进程参数。SDK 关闭最多等待 1 秒，随后仅对持有句柄强制终止并最多再等 1 秒；若旧进程仍未退出则停止重连。Windows 上 SDK 关闭时可能记录退出码 1，集成测试另行确认子进程确实退出。通常超时清理后，下次请求可以重新初始化。

外部供应商接入点为 `RecipeProvider`，尚未配置供应商或自动抓取。后续 provider 必须保持严格参数、完整食材和来源字段；更换来源时应重新评测。

## 复现验证

使用与 Maven 一致的 Java 21 运行时。在依赖已缓存的机器执行：

```powershell
mvn -o test
mvn -o "-Dtest=CookingEvaluationTest" test
mvn -o -DskipTests package
mvn -o "-Dtest=RecipeMcpIntegrationTest" "-Dcooking.eval.jar=target/diet-agent-1.0-SNAPSHOT.jar" test
```

未缓存依赖时，首次去掉 `-o`。协议测试和 24 条任务评测运行真实子进程；不需要 Docker、MySQL 或 embedding API。报告输出 `target/evaluation/cooking/report.json`，包括逐任务输出、调用数、限制违规数、路由正确率、工具成功率、初始化耗时、p50/p95 和语料 SHA-256。报告在测试开始时删除，验收失败不会继续使用旧报告。

固定任务包含预期工具拒绝：未知 ID 和明确禁用食材会降低“成功调用数/总调用数”，不代表传输失败。路由指标只描述固定的有限语法/结构化任务，不能外推到任意自然语言、真实用户满意度或 agentic RAG。
