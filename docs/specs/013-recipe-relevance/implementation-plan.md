# 菜谱相关性筛选实施计划

> **For agentic workers:** Use executing-plans to implement this plan task-by-task in the current authorized checkout. Existing intent-comparison changes must be preserved.

**Goal:** 接入可配置的专用重排 API，建立不绕过筛选的失败契约及三组对照入口。

**Architecture:** 保留 `RecipeReranker.rerank` 兼容已有调用，通过默认 `rerankDetailed` 暴露结构化诊断。真实 HTTP 实现返回明确状态；业务层在生成前检查准入结果。关闭时 identity 不变。

**Tech Stack:** Java 21、Spring Boot、Reactor、JDK HttpClient、Jackson、JUnit/本地 HttpServer。

**Spec:** [spec.md](spec.md)

## Global Constraints

- 不改原有未提交意图评测工作；不改固定 query/qrels；不用 meal 数据构建公共索引。
- 默认 identity，真实 API 仅显式开启；阈值须显式配置。
- 未通过准入和服务故障都不调用生成模型，但必须分开报告。

## Task 1：评分契约与 HTTP 适配

Files: `RecipeRerankResult.java`、`HttpRecipeReranker.java`、`RecipeReranker.java`、`RecipeRagProperties.java`、`RecipeRagConfiguration.java`、`HttpRecipeRerankerTest.java`、`RecipeRagConfigurationTest.java`。

- [x] 写测试：本地返回 `{"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}]}`；断言映射到第二篇本地菜谱、阈值过滤第一篇且保留原 RRF 分数。
- [x] 运行 `mvn -Dtest=HttpRecipeRerankerTest,RecipeRagConfigurationTest test`，先确认缺功能失败。
- [x] 实现 `rerankDetailed(RecipeQuery,List<RetrievedRecipe>)`，状态 BYPASSED/ACCEPTED/FILTERED/UNAVAILABLE，使用 JDK 异步 HTTP，完整解析索引和有限分数，超时/非法响应只返回受控失败。
- [x] 覆盖空结果、重复/越界/漏项、HTTP 401/500、超时、错误文本包含密钥时不泄漏、默认无连接及缺配置启动失败，再运行上述测试。

## Task 2：业务准入与候选预算

Files: `RecipeQueryService.java`、`RecipeQueryConfiguration.java`、`RecipeVideoCoordinator.java`、对应测试。

- [x] 测试评分 FILTERED 和 UNAVAILABLE 时 `verifyNoInteractions(generator)`；测试视频成功不被评分失败吞掉。
- [x] 运行对应测试，确认失败再实现：候选预算独立、原始解析问题用于评分、拒答诊断保留 RRF/重排分数和错误原因。
- [x] 运行 `mvn -Dtest=RecipeQueryServiceTest,RecipeOrchestratorTest,RecipeVideoFinalFixTest test`。

## Task 3：评测与使用文档

Files: `RecipeRerankEvaluationTest.java`、`RecipeRerankEvaluationReport.java`、对应统计测试、运行指南。

- [x] 测试未重排/仅重排/过滤比较共用候选；服务失败与正确拒答分开计数；test 缺冻结标识拒绝运行。
- [x] 实现 dev 默认、test 显式冻结的 opt-in 入口，逐 case checkpoint、独立运行目录、保留输入/模型/阈值/文本哈希/耗时；不自动调阈值。
- [x] 写可直接运行的 PowerShell、IDEA 环境变量、阈值校准与效果边界说明。

## Task 4：验收

- [x] `mvn test`。
- [x] `mvn -DskipTests package`。
- [x] 审查失败降级、配置密钥、空证据及评测分母，填写 acceptance.md。
