# 食谱与食材替换 MCP 实施计划

> 使用 executing-plans 在当前会话逐项执行；已获用户批准，不重复询问执行方式。

**Goal:** 通过独立 MCP 进程为在家做饭提供可追溯食谱与有限范围的替换建议。
**Architecture:** 公共本地 JSON → RecipeProvider → MCP Java SDK stdio Server → AgentScope MCP Client → CookingService → 既有会话/Trace。工具返回结构化记录，展示文本采用确定模板，不增加无证据 LLM 文案。
**Tech Stack:** Java 21 运行时、AgentScope 1.0.11、其现有 MCP Java SDK 0.17.0、Jackson、JUnit 5。以已缓存 JAR 的实际签名为准。
**Spec:** [spec.md](spec.md)

## 全局约束

- 12 个 LOCAL_EXAMPLE 食谱、8 条有方向和食谱作用域的替换规则，不更改 001 固定评测数据。
- 三个只读工具白名单，stdio 真实独立进程；stdout 仅协议、stderr 日志；应用关闭释放进程。
- 每轮最多 3 次工具调用，单次默认 3 秒、初始化独立预算；默认关闭。
- 会话 required/excluded 由服务端合并；不把 recipeId 写进 mealId 或已推荐餐食列表。
- 原六类意图评测保持兼容；自然语言使用明确的有限语法，结构化 cookingRequest 覆盖准确接口。
- 不提供营养数字或无记录替换比例；缺少知识如实说明。

## 任务 1：数据与 provider（MCP-01、03、04）

新增 `service/cooking/RecipeProvider.java`、`LocalRecipeProvider.java`、`CookingArguments.java`、`resources/diet/cooking/recipes.json`、`substitutions.json`；扩充公共词表的基本调味料。

接口：`RecipeProvider.call(String tool, Map<String,Object> arguments)` 返回 JSON 对象 Map；参数 schema 与运行时严格校验一致；搜索按覆盖度降序/id 升序，详情带来源与步骤，替换结果核对所有最终食材。

- [x] 写 `RecipeProviderTest` 并运行确认缺实现失败。

```java
assertThat(provider.call("search_recipes", Map.of("requiredIngredients", List.of("番茄","鸡蛋")))).containsKey("recipes");
assertThatThrownBy(() -> provider.call("get_recipe", Map.of("recipeId","unknown"))).isInstanceOf(IllegalArgumentException.class);
```

- [x] 实现 JSON 加载校验、食材别名、未知字段/类型拒绝、来源/唯一 ID、搜索/详情/替换规则。
- [x] 运行 provider 测试，验证未知 ID、禁用食材、无规则、非对称替换与缺少调味料。

## 任务 2：真实 MCP 服务与客户端（MCP-02、05、06）

新增 `RecipeMcpServer.java`、`RecipeMcpClient.java`、`CookingProperties.java`、`CookingConfiguration.java` 与 stderr 专用日志配置；修改 `DietApplication` 增加 `--recipe-mcp-server` 无 Spring/DB 入口。

接口：客户端 `call(tool,args)` 返回严格解析结果，`toolNames()` 可检查发现结果，`close()` 释放 client 和参数文件。用现有 SDK 完成 initialize/tools/list/tools/call；工具白名单经 Toolkit 注册，业务调用仍经同一 MCP Client。

- [x] 写 `RecipeMcpIntegrationTest`，覆盖三个工具、错误返回、实际子进程关闭；运行确认失败。
- [x] Server 使用 `McpServer.sync(new StdioServerTransportProvider(mapper))` 注册工具；通过 SDK 处理协议，不手写 JSON-RPC。
- [x] 客户端配置 `McpClientBuilder.create("diet-recipes").stdioTransport(...).timeout(...).initializationTimeout(...)`，验证发现的工具集合后注册 Toolkit。
- [x] 验证源码运行与打包运行入口；无云 Key、无 Docker、无数据库即可完成 MCP 协议测试。

## 任务 3：做饭路由与约束（MCP-03、06、07）

新增 `CookingRequest.java`、`CookingRouter.java`、`CookingService.java`，修改 `DietOrchestratorService` 与可选 cooking 配置。保留旧构造器支持已有测试。

接口：`CookingRouter.route(message,context)` 返回 Optional<CookingRequest>；`CookingService.respond(request, constraints)` 返回来源可见的文本；在餐食 PERSONAL 空库检查之前处理独立公共食谱路径，持久化限制且不修改餐食 ID 列表。

- [x] 写 `CookingServiceTest` / `CookingOrchestratorTest`，覆盖关闭开关、服务端限制不可覆盖、pending 不绕过、非法结果、工具不可用。
- [x] 自然语言明确支持“用A和B找食谱”“食谱ID怎么做”“食谱ID里的A可以用什么替换”；无法确定 ID 或完整约束时澄清。
- [x] 每轮调用预算封装，失败返回确定说明；结果校验并模板展示来源、缺少食材、步骤和替换影响；记录工具名、规范输入、结果 ID、时间和错误。
- [x] 运行新业务测试及既有意图/餐食/RAG 回归。

## 任务 4：固定任务评测与交付（MCP-08）

新增 `src/test/resources/evaluation/cooking/tasks.json`、`CookingEvaluationTest`、本阶段 runbook.md / acceptance.md。

- [x] 建立至少 24 条固定任务，独立给出预期路由、工具结果及限制；记录数据与任务哈希。
- [x] 用真实 MCP 子进程执行，输出 `target/evaluation/cooking/report.json` 的路由正确率、工具成功率、违规数与延迟；失败前清除旧报告。
- [x] 运行默认离线全量测试、打包入口验证、独立代码审查，修复可复现问题。
- [x] 更新验收记录并保存本地提交；说明数据是示例、有限语法不等于真实 LLM 工具规划。
