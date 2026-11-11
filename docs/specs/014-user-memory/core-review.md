# 用户长期记忆核心审查

审查范围：MemoryStore/Service/Policy/Worker/Validation、迁移 005、MemoryController，以及编排器、MealService、RecipeQueryService、ChatResponse 的记忆接入。未审查现有 RAG/eval 改动和尚未完成的页面/提取器实现。以下为静态代码审查；未改动源代码，未运行主线测试。

以下行号与问题描述针对首次审查快照。主线同步修复期间的复读结果：

| 问题 | 复读状态 |
| --- | --- |
| 1 删除无水位 | 已新增无记录时写入 tombstone 的路径，静态检查通过，待主线回归测试 |
| 2 永久阻止新声明 | `remember` 已允许更新较新的显式 ADD，静态检查通过，待主线回归测试；管理页改值到既有 tombstone 仍被 duplicate 检查拒绝 |
| 3 空结果漏过敏 | 已共用 `extractFacts` 并扩大 SELF 检测，修复原示例及 Worker 不一致；全句“如果/假设/不过敏”取消保护的问题仍存在，提取到任一无关 AVOID 也会掩盖漏提取过敏 |
| 4 临时限制路由丢失 | 复读时仍存在 |
| 5 视频限制绕过 | 复读时仍存在 |

## 1. [P1] 删除先于旧任务执行时没有留下删除水位

位置：`src/main/java/com/diet/service/memory/MemoryStore.java:122-124`。

`revokeFromMessage` 只 UPDATE 已存在的行。可复现顺序：任务 A 是 ADD(TASTE, 清淡)，在线提取失败后等待重试；较新的任务 B 是 REMOVE(TASTE, 清淡)，先成功处理。因为此时没有档案行，B 更新零行，且不推进 epoch、不保存 tombstone。A 随后重试时，`remember` 查不到旧行，仍创建 ACTIVE 偏好，复活用户已撤销的事实。独立会话的并发任务也可出现此顺序。

建议：即使当前不存在该键，也保存按用户/类型/值限定的删除水位；ADD 必须比较自身任务顺序与删除水位。补充 B REMOVE → A ADD 的乱序测试。

## 2. [P1] Tombstone 永久屏蔽后续重新声明

位置：`src/main/java/com/diet/service/memory/MemoryStore.java:105`；相关位置 `:138-139`。

`DELETED` 无条件返回 null，无法区分旧任务与删除之后的新证据。用户在页面删除“花生过敏”后，再明确声明“我对花生过敏”，新任务仍然不能形成 PENDING 记录；本轮 `facts` 会阻止推荐，但下一轮/新会话已经没有档案保护。普通偏好同样永久无法重新记录，页面也不能恢复，因为列表隐藏 DELETED，更新成该值又被 duplicate 查询拒绝。

建议：仅阻止删除水位之前的任务；允许删除后的明确新声明复用键并增加版本，过敏重新进入 PENDING。测试删除 → 新 ADD → 下一会话仍受保护，并保留旧 ADD 无效的测试。

## 3. [P1] 空提取结果可以绕过明确过敏声明

位置：`src/main/java/com/diet/service/memory/MemoryService.java:19-22`、`:39-42`。

在线漏提取保护只识别有限前缀。例如受控提取器返回 `[]` 时，“我吃花生会过敏，推荐晚餐”不匹配 `(我对|本人对|我有|我不能吃|我不吃)`，因此任务被置 DONE，`requiresClarification=false`、`hasAllergy=false`，后续允许推荐。整条消息中出现“如果”也会取消保护，包括“我对花生过敏，如果没有鸡肉可以换牛肉吗”这样的真实声明。后台 `process` 完全没有该检查，即使在线因明确声明未提取而要求澄清，重试返回空数组也直接完成任务。

建议：将安全声明完整性检查统一放在在线与 Worker 共用的验证路径，按局部子句判断主体、假设与否定；遇到未解决的过敏/禁忌表达应澄清/保留失败状态。加入空数组的受控模型测试，覆盖上述两句及后台重试。

## 4. [P1] 临时忌口在模型兜底菜谱路由中丢失

位置：`src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java:309-310`；相关位置 `:416-417`、`:470-475`。

`withMemoryRecipeContext` 仅在原始请求已经匹配菜谱规则时合并本轮 AVOID_INGREDIENT。若请求最初不匹配规则，之后由意图模型识别为 RECIPE_QUERY，临时事实既没有写长期表，也没有进入最后强制构建的 recipeQuery。例如“今天我不吃香菜，教我炒鸡蛋”被模型路由到菜谱时，TEMPORARY 香菜事实不会成为 excludedIngredients；检索/个人做法校验因此没有硬限制，重新依赖答案模型理解原文。选择回复和结构化餐食检索也应检查同类丢失路径。

建议：将本轮有效限制作为独立工作态传递到所有分支，待最终路由确定后合并，而不是在预路由阶段按 `matches` 决定是否传递。补充规则不匹配、意图返回 RECIPE_QUERY 的测试并断言最终 query 排除香菜。

## 5. [P1] 视频路径绕过长期与临时食材禁忌

位置：`src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java:340-360`、`:503-504`。

记忆预处理只统一拦截 ALLERGEN；已有 ACTIVE AVOID_INGREDIENT 不影响纯视频分支，该分支直接按请求搜索并返回所有视频。并行菜谱/视频路径虽然为文字菜谱准备了 excludedIngredients，但视频请求只使用 `prepared.query().text()`，长期记忆排除项不会进入视频侧，且结果没有可核验配料过滤。比如档案忌口“香菜”，新会话请求“只看香菜炒蛋视频”，仍会返回对应跟做视频。

建议：视频入口统一接收有效硬限制；视频缺乏可核验配料时，应明确说明无法核对并避免把结果作为符合该用户忌口的推荐。测试纯视频和菜谱并行两条路径，包含新会话只依赖档案的情况。

## 已检查且未发现具体隔离问题

管理 API 的读写按 userId 限定，变更带版本检查；消息与任务同事务提交；模型在事务外调用；领取使用租约令牌，`apply` 校验令牌与租约，失败任务有重试上限；管理操作推进 epoch 使旧任务失效。以上结论仅针对本次读取的实现，不能替代并发与 MySQL 集成测试。
