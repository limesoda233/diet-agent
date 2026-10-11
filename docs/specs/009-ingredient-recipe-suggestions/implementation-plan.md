# Ingredient Recipe Suggestions Implementation Plan

> For agentic workers: use subagent-driven-development for implementation and review. Steps use checkboxes.

**Goal:** 食材问答先输出有证据的选菜说明，再显示菜谱。
**Architecture:** 原问题决定回答目的；复用现有检索和一次 Answer Agent 调用。推荐说明与确定性回退均使用本轮引用。
**Tech Stack:** Java 21、Spring Boot、AgentScope、JUnit 5。
**Spec:** [spec.md](spec.md)

## Global Constraints

- 不修改 MySQL 推荐、公共索引、锁定 queries/qrels、默认改写／重排开关和视频并行流程。
- 不自动假定用户有调味料，不把生／熟或鲜／干原料直接等同，不编造替换、营养或步骤。
- 用户原问题决定回答目的，改写文本只用于检索。

## Task 1: 食材问法、回答目的与推荐说明

**Files:** 修改 RecipeQueryRouter、AgentScopeRecipeAnswerGenerator；新增 service/recipe/rag/model/RecipeAnswerPurpose；测试 RecipeVideoFinalFixTest、RecipeAnswerGeneratorTest。
**Interfaces:** `RecipeAnswerPurpose.fromText(String)` 返回 `RECIPE` 或 `INGREDIENT_SUGGESTION`；Router 调用同一判定，回答层从原始 RecipeQuery.text 判断。

- [ ] 添加先失败测试：给定食材询问无需 IntentAgent 就进入 RAG；受控回答仅返回菜谱时仍有推荐开头，错误引用和异常回退保留合法引用，明确拒答不添加推荐。

```java
var response = fixture.chat("冰箱里有鸡腿肉丁、花生米、辣椒、大葱，能做什么菜", Map.of());
assertThat(response.speechText()).contains("宫保鸡丁", "[S1]");
verifyNoInteractions(fixture.intent);
```

- [ ] 运行 `mvn "-Dtest=RecipeVideoFinalFixTest,RecipeAnswerGeneratorTest" test`，记录预期路由／文案失败。
- [ ] 在 Router 接入食材建议问法；回答 Prompt 说明先推荐、核对原料、再菜谱。解析支持推荐说明，缺失时使用引用对应候选的保守说明，不能用非法引用。

```java
RecipeAnswerPurpose purpose = RecipeAnswerPurpose.fromText(query.text());
// INGREDIENT_SUGGESTION 的模型说明缺失时，用本轮候选和引用生成保守前言。
```

- [ ] 针对性测试通过后运行完整 `mvn test`，审查仅一次回答模型调用和无检索副作用。
- [ ] 独立规格／质量审查，修复发现，保存测试证据与验收记录，再提交本任务文件。
