# 多 Agent 餐食规划

阶段 015 为受控工作流：Planner 分解任务，Menu 从 MySQL 候选中组装计划，Review 检查需求遗漏。Java 负责范围、数据源、禁忌、候选归属、事实引用、去重和最终状态。最多修订一次、调用角色五次；默认关闭。

## 启用

先在现有 `diet_db` 执行 `src/main/resources/db/migrations/006_meal_planning.sql`。本项目没有自动执行该迁移；请用数据库客户端打开并执行。需要长期记忆时，还须执行 005 并启用该功能。迁移不会导入餐食数据。

在 IDEA 当前运行配置实际加载的环境变量文件中添加：

```dotenv
DIET_MEAL_PLANNING_ENABLED=true
```

复用已有 `DEEPSEEK_API_KEY` 和主/轻量模型设置，无需增加 Jev 密钥。Planner/Review 使用轻量模型，Menu 使用主模型；每次无状态调用、无工具权限、SDK `maxAttempts=1`。Jev 不是本阶段必需依赖。

启动后输入“未来三天只安排晚餐，第一天清淡，后两天川菜，尽量不重复”。也可发送聊天 `context`：

```json
{"mealPlan":{"startDate":"2026-10-23","days":2,"mealTimes":["午餐","晚餐"],"repeatPolicy":"HARD"}}
```

日期按 Asia/Shanghai 服务端日期解释。支持 1—3 个连续日，每天使用相同的所选餐次集合，最多九个任务；可以只选晚餐。局部口味可按第一天、第二天、第三天、前两天、后两天表达。不同日期指定不同餐次、非连续日期和修改旧计划目前要求澄清；不能静默增加餐次。原文与结构化范围冲突也会澄清。

`不要重复` 是硬要求，使用最大匹配；`尽量不重复` 是软要求，可能返回明确标记的重复。两者混合且范围不明确时需要澄清。候选池不足时可扩展一次，不会放宽禁忌或宣称整个数据库无解。

## 结果和故障

| 状态 | 含义 |
| --- | --- |
| COMPLETE | 所有任务合法覆盖，Review 通过 |
| PARTIAL | 有未选餐次或仍未解决的审查问题 |
| DEGRADED | 完整草稿通过 Java 校验，但语义复核不可用 |
| NEEDS_INPUT | 范围歧义、能力不足、风险阻断或上下文改变 |
| UNAVAILABLE | 整体检索失败或规划未能在预算内完成 |

Planner 非法/不可用时，只在已明确范围内确定性展开。Menu 非法或不可用时，用合法候选做确定性选择，策略会标为 `DETERMINISTIC_MENU`；即使 Review 通过，也不能将它计入正常多 Agent 成功。已知遗漏不会因第二次 Review 超时而被伪装成完整计划。

记忆启用时，规划开始冻结一次记忆及硬条件，检索不逐餐重新读取画像。结束前复核记忆和候选内容指纹；变更时返回 `CONTEXT_CHANGED`，不展示旧结果。该检查是输出前复核点，不是与所有用户编辑串行化的强一致快照。

缺少配料/交叉接触证明时，过敏信息保持保守阻断；当前不能核验价格、热量、克数、用具或耗时，需要先明确放宽相关条件。来自长期记忆的用具/耗时条件应先在“我的记忆”修改或删除。标签不能证明营养数值。公共/个人数据源不混查，不使用 embedding、公共菜谱索引或视频工具生成核心计划。

最终版本保存到 `meal_plan`、`meal_plan_version`。卡片反馈携带 `planId/planVersion/taskId/traceId/mealId`，后端验证用户、会话和实际展示记录，任务级审计保存在 `meal_plan_feedback`。014 的偏好归纳仍按真实用户反馈及会话证据去重，重复展示同一菜品不会自动增加偏好权重。生成计划本身不会作为“喜欢”证据。

## 排查和评测

Trace 中查 `PLAN_CONTEXT_READY`、`PLAN_SPEC_VALIDATED`、`PLAN_TASK_CANDIDATES_FETCHED`（逐任务数量与故障）、`PLAN_CANDIDATES_FETCHED`、`PLAN_VALIDATED`、`PLAN_REVIEWED`、`PLAN_FALLBACK`、`PLAN_FINALIZED`。`PLAN_MODEL_CALL` 记录角色、模型/Prompt/schema 版本、耗时及 SDK 返回的 token；缺失 token 保持 null。角色调用数不等于经过传输层独立测量的 HTTP 请求数。

默认总预算 40 秒，Planner/Menu/Review 分别 5/8/4 秒，检索并发最多 3。配置位于 `diet.meal-planning`；候选初始 5、扩展最多 10、去重事实最多 60，并有输入字节预算。JDBC 查询/事务另设超时；Reactor 取消不能保证驱动立即终止 SQL。

```powershell
mvn "-Dtest=Planning*Test,MealPlanningCoordinatorTest" test
mvn test
mvn "-DskipTests" package
npm --prefix frontend test
npm --prefix frontend run build
```

固定开发/锁定集位于 `src/test/resources/evaluation/planning`。当前受控评测使用假角色与固定候选，覆盖状态、硬约束及调用边界，不消耗真实模型 API。开发/锁定组互斥，重复三次是稳定性观测，不能当成三倍独立样本。

尚未运行真实旧流程/单 Agent/多 Agent 对照及人工盲评，不能宣称推荐质量提升百分比。扩大启用前，应固定同一 MySQL 快照、模型和 Prompt 版本，分别记录覆盖/失败/修订/降级、真实模型 token 和延迟；旧固定三餐流程只比较单日三餐可表达的交集。
