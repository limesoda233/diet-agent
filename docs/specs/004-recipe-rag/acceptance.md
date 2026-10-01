# 菜谱 RAG 验收记录

日期：2026-10-01。验收对象为阶段 004 的隔离 worktree，提交范围从 `efae202` 之后开始。原工作区的 README、`application.yml`、意图评测和其他未提交修改未被覆盖。

## 结果

| 条目 | 状态 | 证据 |
| --- | --- | --- |
| RRAG-01 | 通过 | `MySqlOnlyRecommendationTest`；生产代码无旧餐食向量检索服务。 |
| RRAG-02 | 通过 | `MealConstraintSearchTest`、`ConstraintParserTest`；MySQL 使用 JSON 包含/排除条件，未知食材知识不视为满足。 |
| RRAG-03 | 通过 | `RemoveMealRagMigrationTest`；`004_remove_meal_rag.sql` 只删除 `meal_index_progress` 与 `meal_index_task`。 |
| RRAG-04 | 通过 | `MealKnowledgeRepositoryTest`、`MealServiceKnowledgeTest`；个人做法不读取 RAG 开关。 |
| RRAG-05 | 通过 | `RecipeQueryServiceTest`、`RecipeOrchestratorTest`；直接问菜谱在个人空库检查与意图 Agent 之前路由，`mealId` 优先个人做法并可公共回退。 |
| RRAG-06 | 通过 | `HowToCookMarkdownParserTest`、`RecipeCorpusImporterTest`、`RecipeIndexCoordinatorTest`；固定 commit、稳定 ID、内容哈希和双索引版本。 |
| RRAG-07 | 通过 | `HybridRecipeRetrieverTest`；并行启动、单分支降级、双分支失败终止。 |
| RRAG-08 | 通过（离线） | `HybridRecipeRetrieverTest` 验证串并行排序一致；`RecipeParallelLatencyTest` 验证分支重叠；真实 p50/p95 待本地依赖可用后生成。 |
| RRAG-09 | 通过 | `RecipeRrfTest`、`RecipeAnswerGeneratorTest`；父菜谱去重、固定源路径/commit 引用、未知引用回退。 |
| RRAG-10 | 通过 | `RecipeQueryRewriteTest`、`RecipeRagConfigurationTest`；开关默认关闭，超时/错误保持原查询与顺序。 |
| RRAG-11 | 通过（离线） | 64 条、8 切片、48/16 开发/测试隔离；qrels 哈希锁定；检索、回答与七组实验入口分开。 |
| RRAG-12 | 通过 | `CookingVideoSearchServiceTest`；disabled/fake provider、严格参数、`example.invalid`，普通菜谱与推荐不隐式调用。 |

## 验证命令

```text
mvn test
Tests run: 74, Failures: 0, Errors: 0, Skipped: 5
BUILD SUCCESS

mvn -DskipTests package
BUILD SUCCESS
target/diet-agent-1.0-SNAPSHOT.jar
SHA-256: 0EDC38931A61725DA693D6349BE4BDACCC7646D0535768E78606D35098F693B8
```

5 个跳过项分别受真实 MySQL、固定 HowToCook checkout 或 Ollama/Qdrant opt-in 控制。另用固定 HowToCook checkout 运行：

```text
mvn -Dtest=RecipeEvaluationDatasetTest -Ddiet.recipe-rag.corpus-path=tmp/howtocook-eval test
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

该验证确认 56 条语料内 qrels 的相对路径与稳定 `recipe_id` 全部匹配生产导入器。临时语料 clone 已在验证后移除，未纳入提交。

快进合入 Windows 原工作区后，首次全量测试发现 JSONL 的 LF/CRLF 会改变按原始字节计算的 qrels 指纹。`199806f` 将换行规范化后再计算 SHA-256，并增加跨平台回归测试；锁定哈希不变。包含原工作区既有意图评测改动的最终验证结果为：`Tests run: 89, Failures: 0, Errors: 0, Skipped: 6`，随后 `mvn -DskipTests package` 成功。

静态边界检查：

```text
rg -n "MealRetrievalService|meal_index_task|meal_index_progress|search_recipes|get_recipe|suggest_substitutions" src/main src/test
```

旧 MCP 工具名和旧餐食 RAG 服务在生产代码中无命中；`MealRetrievalService` 只出现在负向断言测试。两个旧表名保留在历史迁移 `002_meal_retrieval.sql`、清理迁移 `004_remove_meal_rag.sql` 及迁移测试中，当前最终 schema 由 `004` 删除它们。

## 未完成的真实依赖验收

2026-10-01 对 `http://localhost:11434/api/tags` 和 `http://localhost:6333/collections` 的只读探测都在 3 秒后超时。因此没有运行 `RecipeRetrievalEvaluationTest` 的真实模式，也没有生成或声称 BM25/dense 七组实验的实际质量分数。待 Ollama `bge-m3:567m` 与 Qdrant 可用后，按 [runbook.md](runbook.md) 运行；输出位置为 `target/evaluation/recipe/report.json`。

回答生成仍需要项目现有的主聊天模型配置。查询改写和重排目前是可插拔、默认 identity 的安全基线；未配置实际模型 provider，不宣称它们带来质量提升。

## 来源与授权

- 菜谱正文来自 HowToCook `1.6.0` commit `a0b14bd388ee996943173679c3a7c9db7da04a6c`，该 commit 的 LICENSE 为 Unlicense。
- All-in-RAG 只参考 commit `583a61b09869bc3afc4552289171f6ec188f2c76` 第八章的方法，按其 README 的 CC BY-NC-SA 4.0 声明归属；未复制 Python 实现。
- 视频平台集成未实现。未来接入哔哩哔哩或抖音时须使用官方或明确授权接口，并单独处理凭据、限流、内容许可和平台条款。

## 提交

| 提交 | 内容 |
| --- | --- |
| `d7359ba` | 个人做法与 MySQL 食材约束 |
| `1ae2c0f` | 删除餐食推荐 RAG |
| `66ae6c3` | 固定语料解析与父子分块 |
| `5f89153` | Lucene BM25 |
| `fd5af82` | AgentScope embedding 与 Qdrant |
| `2979627` | 并行混合检索、RRF、父块聚合 |
| `a2d178a` | 查询改写、重排与配置 |
| `3fcef03` | 带引用菜谱回答与独立入口 |
| `3c8db5d` | 可复现双索引构建 |
| `57c99ec` | 64 条评测集与实验矩阵 |
| `ab27109` | 视频搜索契约替换旧食谱 MCP |
| `199806f` | 规范化 qrels 换行并增加跨平台指纹回归测试 |
