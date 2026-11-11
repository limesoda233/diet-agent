# 用户长期记忆最终聚焦审查

范围：当前记忆核心、请求工作态、菜谱/视频接入、反馈与推断，以及前端记忆列表/修改/开关、API、导航和聊天提示。仅静态审查，未改动产品代码，未重复运行主线测试。前次已修复的问题不再计入以下发现。

## 修复后复核结论

本报告下方四项保留为审查历史，当前实现已逐项修复。本次限定复核未发现新的阻塞问题；回归测试结果由主线执行并记录。

| 历史问题 | 当前状态与复核依据 |
| --- | --- |
| 1 临时忌口丢失 | 已解决。推荐、调整与规划均接收服务器 currentExclusions；最终检索前规范化并合并排除项，规划使用临时 effectiveContext，保存的 state 不写入这些工作态排除项 |
| 2 推断变硬过滤 | 已解决。healthDefaults 和 preferences 均要求 EXPLICIT；INFERRED 留在 rank 的小幅分数调整中 |
| 3 修改回历史值失败 | 已解决。只有非 DELETED 目标才构成重复；DELETED 目标复用 id、增加 version，管理操作推进 epoch；手动修改使用自身确认说明并清空 sourceMessageId，避免冒用旧聊天证据 |
| 4 三餐遗漏软重排 | 已解决。MealPlanService 内部应用默认槽位，并在每个餐次调用 rank，判断本轮显式覆盖时使用原始 baseSlots |

启动相关复核：MemoryStore 是无 SQL 初始化副作用的 Repository，构造器仅保存依赖；当前 MyBatis/JDBC/Spring Boot 技术栈提供 JdbcTemplate、事务管理器和 TransactionTemplate 自动配置。关闭记忆时不注册 MemoryService/Policy/Worker，Controller 的 disabled 列表分支不读取记忆表，FeedbackService 的可选装配要求存在 MemoryService，因此关闭时不会接入记忆反馈写入。未发现这条装配链中可确定导致默认启动失败的缺陷。本结论为源码/配置审查，未声称执行了完整应用启动。

## 1. [P1] 临时忌口没有进入餐食推荐与三餐规划的硬限制

位置：`src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java:391`、`:770`；相关位置 `src/main/java/com/diet/service/memory/MemoryRequestContext.java:10-16`。

`MemoryRequestContext` 已将可靠抽取的 AVOID_INGREDIENT 存入服务器工作态，但只有菜谱与视频读取它。餐食分支依旧只使用 `ConstraintParser.resolveContext` 的结果及 MySQL 中的长期档案。对于受控提取器识别为 SELF/TEMPORARY/AVOID_INGREDIENT(花生) 的“今天我避开花生，推荐晚餐”，有界规则既不识别“避开”，也不将它判为未解决限制，因此餐食查询仍可返回含花生的候选。TEMPORARY 没有长期记录，`MemoryPolicy.mealConstraints` 无法补回；结构化 retrievalConstraints 覆盖自然语言规则时也可丢失已抽取限制。

建议：在最终餐食检索/三餐规划前，独立合并服务器工作态的排除食材，校验规范名称与冲突。仅用于本轮，避免写回会话造成临时限制跨轮残留。测试绕过规则语法和结构化空约束时，最终 MealSearchRequest 仍排除花生。

## 2. [P2] 推断偏好被当成硬召回条件

位置：`src/main/java/com/diet/service/memory/MemoryPolicy.java:24-35`。

`defaults` 的两条流没有排除 sourceType=INFERRED。聚合得到的“喜欢川菜/辣/清淡”会填入 cuisine/taste/healthGoal，再送入 `MealMapper.search` 的 `WHERE JSON_OVERLAPS`。这会直接排除其他菜系/口味，甚至使结果为空，并参与澄清就绪判断及生成提示。规格明确要求推断仅用于软排序；页面也向用户说明“仅作为推荐排序的参考”。`rank` 已具备较小权重，但前置过滤已经丢掉其他候选。

建议：长期默认槽位只使用 EXPLICIT 来源；INFERRED 仅留在 `rank` 调整分数。补充推断川菜的用户仍能召回非川菜、而分数仅小幅变化的测试。

## 3. [P2] 修改回以前使用过的值被隐藏 tombstone 阻止

位置：`src/main/java/com/diet/service/memory/MemoryStore.java:164-165`。

页面将“川菜”修改为“粤菜”后，原键保留 DELETED tombstone。随后正常编辑“粤菜”改回“川菜”，duplicate 查询连 DELETED 都计入，必定返回“相同记忆已存在，请修改该记录”。被提示修改的记录又不会出现在列表中，因此无法通过管理页完成该修改；过敏原纠正同样受影响。事务会回滚，不会丢当前值，但正常编辑流程无法完成。

建议：区分活动重复与 tombstone，当前管理动作有新的 epoch，可以安全复用被删除键并推进版本/删除水位。补充 A → B → A 的页面/API 集成测试及旧任务不能复活旧版本的断言。

## 4. [P2] 三餐规划没有应用软偏好重排

位置：`src/main/java/com/diet/service/meal/MealPlanService.java:73-75`；相关位置 `src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java:696`。

单餐推荐在 MealRankService 后调用 `MemoryPolicy.rank`，但三餐规划的三个子查询直接将 MealRankService 的结果交给生成器。DISLIKE 不进入 defaults，因此用户“我一直不喜欢辣”的已确认偏好、或聚合得到的不喜欢辣，在单餐里会降权，在三餐规划里完全不起作用。修复第 2 项后，INFERRED LIKE 在三餐规划中也会完全失效。

建议：为三餐规划复用同一个软偏好重排入口，同时保留本轮显式要求优先；测试三餐每个子查询的候选顺序。

## 已复核路径

- 新删除水位与新显式声明恢复：本轮读取的代码已处理首次审查的两个主要生命周期问题。
- 临时菜谱限制：服务器覆盖保留字段，模型兜底路由后再次合并；视频纯路由与混合路由会按工作态保守拦截，首次审查问题已处理。
- 过敏完整性检查已改为在线/Worker 共用的按子句判断，没有重复报告旧实现。
- 推断仅聚合 TASTE/CUISINE 的本人证据，独立 session 计数；反馈曝光不会自动产生食材/口味事实；推断带 30 日有效期，读取排除过期记录，不能覆盖 EXPLICIT 与删除 tombstone。未发现可以具体复现的额外聚合撤销/TTL 缺陷。
- 前端 API 绑定请求发出时的用户，用户切换重建内容组件，旧请求结果检查 mounted/loadEpoch；本次未发现跨用户晚到响应污染。
- 未启用状态显示普通不可用页面；编辑/版本冲突错误保留当前记录与表单，允许重新加载；原话与聊天提示按 React 文本渲染。
