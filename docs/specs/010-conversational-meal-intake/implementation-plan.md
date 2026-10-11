# Dual Entry Meal Intake Implementation Plan

> For agentic workers: use subagent-driven-development task-by-task, with task and final reviews. Steps use checkboxes.

**Goal:** 管理页与聊天共用可确认的个人餐食草稿。
**Architecture:** 轻量模型提取带原文依据的字段，代码校验词表；管理页复用原创建接口，聊天用独立持久化草稿状态及事务保存回执。
**Tech Stack:** Java 21、Spring Boot、AgentScope、MyBatis/MySQL、原生 JavaScript、JUnit；事务测试可用仅 test scope 的 H2。
**Spec:** [spec.md](spec.md)。前置完成 [009](../009-ingredient-recipe-suggestions/spec.md)。

## Global Constraints

- 聊天只有明确录入请求才开始草稿；普通餐食描述不触发。
- 必填菜名和至少一个合法餐次，其余标签可空；字段只能来自用户明确描述，保存再次走 MealService 校验。
- 单条新增，不自动覆盖；只写当前用户 PERSONAL MySQL 库，知识固定 USER_PROVIDED，不写公共索引。
- 草稿输入上限 4000 字符，模型默认 10 秒、单次尝试；失败不伪造成功。
- 同一实例聊天保存的餐食和草稿回执原子提交，锁内读最新状态；跨实例和管理页旧 POST 不宣称严格幂等。

## Shared Contracts

包 `com.diet.service.meal.intake`。

```java
// 所有 DTO 使用 record，列表／映射防御复制，旧会话字段缺失默认 null。
record MealDraftData(String name, List<String> mealTime, List<String> mood,
    List<String> scene, List<String> healthGoal, List<String> cuisine,
    List<String> taste, List<String> convenience, MealKnowledge knowledge) {
    MealRequest toMealRequest();
}
enum MealDraftStatus { NEEDS_INPUT, READY, SAVED, CANCELLED }
record MealDraft(String draftId, String originalText, MealDraftData data,
    List<String> missingFields, List<String> warnings, Map<String,List<String>> evidence,
    MealDraftStatus status, Long savedMealId) {
    MealDraft saved(Long mealId);
    MealDraft cancelled();
}
interface MealDraftModel { Mono<String> extract(String systemPrompt, String userPrompt); }
// 生成／修改草稿不创建 meal_item 或 meal_knowledge。
MealDraft MealDraftService.generate(String text, MealDraft previous);
MealDraft MealDraftService.generate(String text);
```

模型提取 JSON：`name`、`preparation` 使用 `{value,evidence}`；七槽位及 `ingredients` 使用此对象数组。允许 `multipleMeals` 布尔值；拒绝其它字段。返回稀疏字段用于明确更新，未提字段保留原草稿；不得通过上一版草稿洗掉原文依据。

## Task 1: 共用提取、校验与草稿接口

**Files:** 新建 intake DTO、MealDraftService、MealDraftModel、AgentScopeMealDraftModel、MealIntakeProperties/Configuration；新增 MealDraftController 与对应测试。只在 application.yml 增加非凭据参数。

- [ ] 写失败测试：完整口语生成 READY；缺菜名／餐次生成 NEEDS_INPUT；未知标签与缺依据字段不接受；非法 JSON、额外控制字段、多菜、超时失败；更新仅修改明确字段。

```java
MealDraft draft = service.generate("宫保鸡丁，中午吃，微辣", null);
assertThat(draft.data().mealTime()).containsExactly("午餐");
assertThat(draft.data().cuisine()).isEmpty();
assertThat(draft.status()).isEqualTo(MealDraftStatus.READY);
```

- [ ] 运行 `mvn "-Dtest=MealDraftServiceTest,MealDraftControllerTest" test`，验证缺功能失败。
- [ ] 实现带原文依据的严格 JSON 提取，注入数据库启用词表；给出缺失字段和警告，不提供数据库工具。AgentScope 一次无历史、无工具轻量调用，边界用 timeout/单次预算。
- [ ] 实现 `POST /api/v1/diet/meals/personal/draft`，body `{text}`，响应为上述 MealDraft；记录解析终态。管理页只需此接口，保存复用旧 POST。
- [ ] 跑针对性和完整测试，独立审查并提交本任务文件；后续不得改变共享 DTO 而不同步消费者。

## Task 2: 聊天草稿状态与事务确认

**Files:** intake/MealIntakeWorkflow、MealDraftSaveService；修改 ChatResponse、SessionState、SessionStateService、DietOrchestratorService；新增路由／会话／事务测试。
**Interfaces:** ChatResponse 可选 `mealDraft`；SessionState 可选 `mealDraft` 及 `withMealDraft`；已有 with 方法保持草稿。Workflow 消费 Task 1 的 DTO。

- [ ] 写失败测试：明确录入优先于菜谱；普通描述不触发；餐次补充／修改、取消、保存／重复保存、过期和跨用户拒绝；旧 JSON 与其它 with 方法保留状态。

```java
var draft = chat("帮我录入番茄鸡蛋面").mealDraft();
assertThat(draft.missingFields()).contains("mealTime");
assertThat(chat("午餐").mealDraft().status()).isEqualTo(MealDraftStatus.READY);
```

- [ ] 确认失败后实现 Workflow：只有明确录入或当前草稿的精确补字段／修改／确认／取消动作被拦截；通用“是／好”走普通链路。
- [ ] 保存动作 `context.mealDraftAction={draftId,action:"SAVE"|"CANCEL"}`；校验当前 session/user/draft，明确确认文本绑定当前草稿；READY 才可创建，SAVED 返回原 mealId。
- [ ] 独立 `@Transactional` 保存 Bean 被 Workflow 调用：MealService.createPersonalMeal + SessionStateService.save 在同事务内。Orchestrator 在会话锁内重新加载状态，避免旧快照重复创建。
- [ ] 用真实测试数据库验证事务：保存回执失败时餐食 INSERT 回滚；成功与重复确认仅一条餐食。H2 限 test scope，不修改生产 SQL。
- [ ] 跑针对性和完整测试，独立审查并提交本任务文件。

## Task 3: 管理页与聊天草稿卡

**Files:** static/assets/js/api.js、app.js 和必要样式；新增独立前端交互测试。
**Interfaces:** Task 1 `/meals/personal/draft` 的 MealDraft JSON；Task 2 chat 响应 `.mealDraft` 及 `.mealDraftAction`。

- [ ] 用实际前端脚本和受控 API 写失败测试：整理仅回填不保存、缺必填无法保存、知识字段保留；聊天卡确认使用正确 session/draftId、取消、过期／换用户卡禁用、失败可重试。
- [ ] 管理页增加口语输入与整理按钮，回填可编辑标签和描述／食材／做法；手动 CRUD 保持可用。
- [ ] 聊天渲染草稿卡、缺字段提示和确认／取消；生成或提交期间禁用按钮，成功刷新个人库，旧卡不能执行。
- [ ] JS 语法与交互测试通过；使用浏览器检查布局及操作路径，受控测试结果不得称为真实云模型效果。
- [ ] 独立审查并提交本任务文件。

## Final Verification

- [ ] 整体代码审查，处理规格或质量缺口。
- [ ] `mvn test`、`mvn -DskipTests package`、前端交互测试通过；文档链接及 diff 检查通过。
- [ ] 更新 009/010 验收、README/指南和必要模块注释；记录默认开关及真实服务未验收的边界。
