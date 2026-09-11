# 003 食谱与食材替换 MCP 规格草案

状态：2026-09-12 草案，供确认后实施。已确认方向是支持在家做饭；数据来源尚无用户回复，暂以本地可追溯示例食谱与替换规则作为建议方案，不假定已选外部供应商。

## 目标与取舍

用户能从已有食材查食谱、查看记录的做法，并询问特定菜谱中缺少的食材能否替换。复用阶段 002 的食材规范化与明确限制，查询结果带来源、缺少食材和适用条件。

建议自建一个只读食谱 MCP Server，Diet Agent 使用 AgentScope MCP Client 调用。首先通过本机 stdio 通信，无外部账号依赖；RecipeProvider 接口预留外部食谱 API。直接接云 API 的替代方案覆盖面更广，但需要另外确认服务、费用和数据使用条件。只加普通 Java Tool 的实现更短，但无法验证独立 MCP 的发现、调用和故障处理，故不采用。

MCP 负责统一工具协议，不自动提高食谱数据质量。工具须提供名称、输入 schema 和结果；工具发现与调用分别通过 tools/list 和 tools/call 完成。[MCP 工具规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)

AgentScope 支持本地 stdio 客户端与工具注册；实现时以仓库固定的 AgentScope 1.0.11 实际 API 为准，不能仅照抄新文档的方法签名。[AgentScope MCP 文档](https://java.agentscope.io/v1/en/docs/task/mcp.html)

## 第一版数据

独立维护不少于 12 个示例食谱、8 条有场景限制的替换规则，不复用 RAG 评测的假设配方为正式事实。每条记录包含稳定 ID、版本、名称、规范食材、简短步骤、来源类型与引用、记录状态。自写示例标为 LOCAL_EXAMPLE，未经核实的份量、时间和做法不能冒充认证资料；缺失信息如实返回未知。

外部导入必须保留来源引用。第一版不自动抓取网页、不计算热量/营养克数、不承诺过敏原安全、不把示例自动写入用户餐食库。

## 工具契约

| 工具 | 输入 | 输出 |
| --- | --- | --- |
| search_recipes | availableIngredients、requiredIngredients、excludedIngredients、可选 query、limit（1～5） | recipeId、名称、命中食材、缺少食材、来源、版本；无匹配返回空数组 |
| get_recipe | recipeId、excludedIngredients | 食材、记录步骤、来源；存在禁用食材时拒绝作为可用食谱返回 |
| suggest_substitutions | recipeId、ingredient、availableIngredients、excludedIngredients | 针对该食谱的候选替换、适用步骤、口感/做法影响、规则来源；没有规则则明确未知 |

schema 拒绝未知字段、非法类型、过长输入与未知食材。available 只参与覆盖度排序，required 必须全满足，excluded 任一命中即排除。缺少调味料也要列出，不默认“家里一定有”。稳定排序先按可用食材覆盖度，再按 recipeId。

替换规则是有方向、有限范围的关系；不能假设可逆、可传递或等量。例如某种食材可用于某个炒菜的口感替换，不意味着可替代同类烘焙材料。未记录比例时不生成比例。替换结果须再次检查全部禁用食材；不存在可靠候选就返回无规则。

## 业务接入

新增独立 cooking 路径，沿用现有身份、会话和 Trace。第一版通过可选 `context.cookingRequest` 明确请求类型与字段，提供可复现的 API 演示；自然语言“怎么做、缺少某食材怎么替换”的路由另配固定样例测试。六类原始意图评测保持原标签与评分口径，不把新增能力混入旧意图分数。

工具层接收服务端解析的会话限制；模型参数不得覆盖它们。MCP Server 仅持有公共示例食谱，不读取个人 MySQL 菜单。个人信息无需传给独立工具进程。料理结果不伪装成已有 mealId，也不污染换一批的已推荐餐食 ID 列表。

初期使用受控的工具序列（查询、详情、可选替换），每轮最多 3 次调用，单次超时 3 秒，初始化单独设置超时。生成 Agent 只基于返回记录解释；工具发现与实际调用必须经过 MCP Client，不能旁路直接调用 provider 后声称用了 MCP。

暂不引入自由循环的 agentic RAG。后续有工具路由评测后，再考虑让模型自主规划调用顺序。

## 运行与故障

MCP Server 独立进程，stdout 只输出协议，日志写 stderr；应用关闭时释放子进程及连接。stdio 传输的协议约束见 [MCP 传输规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)。

只注册三个白名单工具。新增独立开关，默认关闭；未启用时原餐食推荐照常工作。工具超时、不可用、返回非法结构或未知 ID，明确说明食谱工具不可用或无结果，不临时编造食谱。公开调用记录包括工具名、规范输入、来源 ID、耗时、错误类型，不记录环境变量或密钥。

## 验收标准

| ID | 条件 |
| --- | --- |
| MCP-01 | 数据 schema、唯一 ID、来源、食材规范与替换作用域校验通过 |
| MCP-02 | 真正的独立进程完成初始化、tools/list、三个 tools/call；协议测试不需要云 Key |
| MCP-03 | required/excluded 在搜索、详情、替换全过程有效；available 不误作必需条件 |
| MCP-04 | 未知食材/食谱、无替换规则、禁止替换、非法响应都有确定行为 |
| MCP-05 | AgentScope 客户端注册与调用真实 MCP Server；关闭应用不遗留该子进程 |
| MCP-06 | 工具失败、超时与次数上限有故障测试；不影响原推荐路径 |
| MCP-07 | 做法与替换解释保留来源，模型不能编造 recipeId、替换比例或营养数值 |
| MCP-08 | 至少 24 条固定任务报告路由正确率、工具成功率、限制违规与响应耗时；真实 LLM 路由效果单独验收 |

规格确认后，实施顺序为数据/provider → MCP Server/契约测试 → AgentScope 客户端 → cooking 路由与 Trace → 固定任务评测和运行说明。真实食谱数据源与自然语言覆盖范围须在验收中如实标注。
