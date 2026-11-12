# 餐食检索与录入诊断日志

云托管日志搜索 `DIET_DIAG`。每条日志是单行、空格分隔的 `key=value`，固定前缀 `DIET_DIAG v=1 event=...`。同一轮聊天用 `traceId` 串联；手动录入的解析请求有独立 `traceId`；营养选项预选是另一请求，使用 `requestId`。日志时间由云托管附加。

| 事件 | 关键字段 | 用途 |
| --- | --- | --- |
| `CHAT_START` | `traceId source freshSession sourceReset draftAction priorSlots` | 确认新会话、餐食库来源和已有槽位数量；`sourceReset=true` 表示旧会话属于另一餐食库，已新建会话 |
| `INTENT_RESOLVED` | `traceId source priorPhase rawIntent finalIntent rawSlots finalSlots` | 查看意图识别与规则修正是否加入或漏掉标签 |
| `MEAL_QUERY` | `traceId source requestedSlots slots requiredIngredients excludedIngredients matched durationMs` | 对照原始筛选和真正送进 MySQL 的筛选条件及召回数量；`outcome=BLOCKED reason=POLICY` 表示饮食安全规则阻断 |
| `MEAL_QUERY_FAILED` | `traceId source errorType durationMs` | 区分数据库查询异常和正常的零结果 |
| `MEAL_SEARCH` | `traceId source requestedSlots effectiveSlots candidates ranked excludedMealIds` | 对照本轮输入与记忆补入的槽位，以及重排后的数量 |
| `CHAT_DONE` / `CHAT_FAILED` | `traceId source responseType displayed durationMs` / `errorType` | 确认结果是否正常返回 |
| `MEAL_DRAFT_START` / `MEAL_DRAFT_DONE` / `MEAL_DRAFT_FAILED` | `traceId entry status slots missing durationMs` / `errorType reason` | 诊断手动“帮我选标签”或聊天录入；`entry=MANUAL` 与 `entry=CHAT` 区分入口，失败原因用固定代码而非异常原文 |
| `NUTRITION_SUGGEST` / `NUTRITION_SUGGEST_FAILED` | `requestId template questions durationMs` / `errorType` | 区分特殊餐食搭配选项和普通标签解析 |

`slots`、`priorSlots`、`requestedSlots`、`effectiveSlots` 的格式固定为 `mealTime:1,mood:0,scene:0,healthGoal:0,cuisine:0,taste:1,convenience:0`。只记录各维度的标签**数量**，不记录原话、具体标签、菜名、姓名、令牌或密钥。`freshSession=true` 代表本次请求没有携带旧会话 ID。

排查“明明有餐食却没有命中”时，先看同一 `traceId` 的 `MEAL_QUERY`：若 `matched=0`，检查是否有意外多出的槽位或食材限制；若 `matched>0` 但 `MEAL_SEARCH ranked=0`，检查排除的餐食 ID 和排序过滤。`requestedSlots` 与 `effectiveSlots` 不同，说明长期记忆添加了默认偏好。云端需要更详细的本轮槽位值时，可从小程序“设置 → 授权一次完整诊断”授权下一次聊天请求，再查看对应 Trace；完整诊断会短期记录原话与模型输入输出，按现有诊断授权机制处理。

手动录入按钮并行请求“普通标签解析”和“特殊餐食营养选项”。普通标签失败时页面现在会明确显示错误；`NUTRITION_SUGGEST template=UNKNOWN questions=0` 只表示该餐食没有专用份量问卷，并不代表普通标签解析成功。

手动解析的 `reason`：`MODEL_UNAVAILABLE` 为模型请求失败或超时，`EMPTY_MODEL_OUTPUT` 为模型无返回，`INVALID_MODEL_OUTPUT` 为 JSON 格式不合约，`NO_FIELDS`/`NO_EVIDENCE` 为解析内容无可用字段或缺乏原文依据，`INVALID_REQUEST` 为请求字段不合约，`OTHER` 为其他情况。所有原因码均不包含用户文本。

普通标签解析请求模型输出 JSON；若模型仍包上一层完整的 `json` 代码围栏，服务端会去除围栏后继续严格校验字段和原文证据。模型返回空白、非法字段、额外文字或未经原文支持的内容仍会失败。若升级后仍出现 `INVALID_MODEL_OUTPUT`，请提供该次 `traceId` 和日志时间，不要发送原始输入或模型输出。
