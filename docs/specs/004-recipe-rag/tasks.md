# 菜谱 RAG 与餐食推荐解耦实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除餐食推荐 RAG，把推荐恢复为确定性的 MySQL 检索，并交付可评测的 HowToCook 并行混合菜谱 RAG 与视频搜索 MCP 扩展边界。

**Architecture:** 餐食域保留 `meal_item`、`meal_knowledge` 和会话约束，通过 MyBatis/MySQL 完成候选过滤与排序；公共菜谱域使用固定 HowToCook 语料，Lucene BM25 与 AgentScope embedding + Qdrant 并行召回，经 RRF、父块展开、可选重排后生成带引用回答。直接菜谱问题不依赖推荐，推荐后的做法追问按 `mealId` 优先读取个人做法，再回退公共菜谱 RAG。

**Tech Stack:** Java 21、Spring Boot 3.3.13、MyBatis 3.0.4、MySQL 8、AgentScope Java 1.0.11、Reactor、Apache Lucene 9.12.3、JDK `HttpClient`、Qdrant、JUnit 5、Mockito。

**Spec:** [spec.md](spec.md)

## Global Constraints

- 餐食推荐只使用 MySQL；embedding、Qdrant、Lucene 不可用时推荐仍须成功。
- `meal_item` 和 `meal_knowledge` 是业务数据，任何迁移均不得删除或清空；只删除 `meal_index_task`、`meal_index_progress` 等旧派生状态。
- 用户私有做法不写入公共 Lucene 或 Qdrant 索引。
- 公共语料固定为 HowToCook `1.6.0`，commit `a0b14bd388ee996943173679c3a7c9db7da04a6c`。
- All-in-RAG 仅参考 commit `583a61b09869bc3afc4552289171f6ec188f2c76` 第八章的方法，不复制其 Python 实现。
- 初始 embedding 基线为 AgentScope `OllamaTextEmbedding` + `bge-m3:567m`，维度 1024。
- 默认检索模式为 `parallel_hybrid`；BM25 与 dense 分支独立超时，允许单分支降级，双分支失败时不得生成答案。
- 评测必须分开报告检索指标、回答指标、延迟和降级，不把 LLM judge 合并成不可解释的总分。
- 视频 MCP 只实现契约与 disabled/fake provider；普通推荐和菜谱问答不调用它。
- 保留工作区现有 README、`application.yml`、意图评测和 `tmp` 的用户未提交修改，不纳入阶段 004 提交。

---

## 文件结构

### 餐食域

- `service/meal/knowledge/MealKnowledgeRepository.java`：只负责 `meal_knowledge` CRUD 和按归属读取。
- `service/meal/constraint/IngredientCatalog.java`：食材规范化与白名单。
- `service/meal/constraint/MealConstraints.java`：确定性包含/排除约束。
- `service/meal/constraint/MealConstraintContext.java`：会话中的查询文本、约束来源和待澄清文本。
- `service/meal/constraint/ConstraintParser.java`：自然语言与结构化约束解析。
- `mapper/MealMapper.java` / `mapper/MealMapper.xml`：标签和食材约束的 MySQL 查询。

### 菜谱 RAG 域

- `service/recipe/rag/model/*`：父文档、子块、查询、命中、引用、回答与诊断记录。
- `service/recipe/rag/corpus/*`：HowToCook Markdown 解析、稳定 ID、内容哈希和导入清单。
- `service/recipe/rag/index/*`：Lucene 稀疏索引、AgentScope embedding 适配器和 Qdrant 向量索引。
- `service/recipe/rag/retrieval/*`：串行/并行检索、RRF、父块聚合、查询改写和重排。
- `service/recipe/rag/answer/*`：证据预算、AgentScope 回答生成、引用校验和拒答。
- `service/recipe/rag/RecipeRagConfiguration.java` / `RecipeRagProperties.java`：条件 Bean 和配置校验。
- `service/recipe/RecipeQueryRouter.java` / `RecipeQueryService.java`：直接问菜谱与推荐后追问入口。

### 评测与视频 MCP

- `evaluation/recipe/*`：固定数据集、指标、运行器与 JSON 报告。
- `service/video/*`：`search_cooking_videos` 契约、disabled/fake provider 和调用服务。

---

### Task 1: 个人做法仓储与确定性餐食约束（RRAG-01、RRAG-02、RRAG-04）

**Files:**
- Create: `src/main/java/com/diet/service/meal/knowledge/MealKnowledgeRepository.java`
- Create: `src/main/java/com/diet/service/meal/knowledge/MealKnowledge.java`
- Create: `src/main/java/com/diet/service/meal/constraint/IngredientCatalog.java`
- Create: `src/main/java/com/diet/service/meal/constraint/MealConstraints.java`
- Create: `src/main/java/com/diet/service/meal/constraint/MealConstraintContext.java`
- Create: `src/main/java/com/diet/service/meal/constraint/ConstraintParser.java`
- Modify: `src/main/java/com/diet/model/MealSearchRequest.java`
- Modify: `src/main/java/com/diet/service/meal/MealService.java`
- Modify: `src/main/java/com/diet/service/meal/MealSearchService.java`
- Modify: `src/main/java/com/diet/mapper/MealMapper.java`
- Modify: `src/main/resources/mapper/MealMapper.xml`
- Modify: `src/main/java/com/diet/model/MealItem.java`
- Modify: `src/main/java/com/diet/model/MealRequest.java`
- Modify: `src/main/java/com/diet/model/SessionState.java`
- Modify: `src/main/java/com/diet/service/session/SessionStateService.java`
- Modify: `src/main/java/com/diet/service/cooking/CookingArguments.java`
- Modify: `src/main/java/com/diet/service/cooking/CookingService.java`
- Delete: `src/main/java/com/diet/service/retrieval/IngredientCatalog.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealConstraints.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealKnowledge.java`
- Delete: `src/main/java/com/diet/service/retrieval/ConstraintParser.java`
- Delete: `src/main/java/com/diet/service/retrieval/RetrievalContext.java`
- Test: `src/test/java/com/diet/service/meal/MealKnowledgeRepositoryTest.java`
- Test: `src/test/java/com/diet/service/meal/MealConstraintSearchTest.java`
- Test: `src/test/java/com/diet/service/meal/MealServiceKnowledgeTest.java`
- Create: `src/test/java/com/diet/service/meal/constraint/ConstraintParserTest.java`
- Create: `src/test/java/com/diet/service/meal/constraint/MealConstraintContextTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/ConstraintParserTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/RetrievalContextTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/RetrievalSessionTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/MealKnowledgeCrudTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/MealKnowledgeRequestTest.java`

**Interfaces:**
- Produces: `MealKnowledgeRepository.save/delete/findByMealIds/findPersonal`；`MealSearchRequest(..., MealConstraints constraints)`；`MealService.search(..., MealConstraints constraints)`；`SessionState.mealConstraintContext()`。
- Preserves: `MealKnowledge` JSON 字段和 `retrievalConstraints` 请求字段的兼容读取。

- [x] **Step 1: 写知识保存不依赖 RAG 开关的失败测试**

```java
@Test
void savesAndReadsPersonalKnowledgeWithoutRetrievalConfiguration() {
    repository.save(41L, new MealKnowledge(List.of("番茄", "鸡蛋"), "家常菜", "先炒蛋", "USER_PROVIDED", "用户录入"));
    assertThat(repository.findPersonal(41L, 7L)).get()
            .extracting(MealKnowledge::preparation).isEqualTo("先炒蛋");
}
```

- [x] **Step 2: 写显式食材约束的 SQL 失败测试**

```java
var request = new MealSearchRequest(SourceMode.PUBLIC, 1L, SlotBundle.empty(), List.of(),
        new MealConstraints(List.of("番茄"), List.of("牛肉"), Map.of(), Map.of()));
assertThat(service.search(request)).extracting(MealItem::name).containsExactly("番茄炒蛋");
```

- [x] **Step 3: 运行定向测试并确认旧实现失败**

Run: `mvn -Dtest=MealKnowledgeRepositoryTest,MealConstraintSearchTest,MealServiceKnowledgeTest test`

Expected: FAIL，因为仓储与新请求签名尚不存在，且知识保存仍受 `diet.retrieval.enabled` 限制。

- [x] **Step 4: 实现最小仓储、约束包迁移和 MySQL 过滤**

```java
public List<MealItem> search(SourceMode sourceMode, Long userId, SlotBundle slots, MealConstraints constraints) {
    MealConstraints safe = constraints == null ? MealConstraints.empty() : constraints;
    return mealMapper.search(sourceMode, userId,
            jsonService.toJsonArray(slots.mealTime()), jsonService.toJsonArray(slots.mood()),
            jsonService.toJsonArray(slots.scene()), jsonService.toJsonArray(slots.healthGoal()),
            jsonService.toJsonArray(slots.cuisine()), jsonService.toJsonArray(slots.taste()),
            jsonService.toJsonArray(slots.convenience()),
            jsonService.toJsonArray(safe.requiredIngredients()),
            jsonService.toJsonArray(safe.excludedIngredients()), SEARCH_LIMIT).stream()
            .map(this::toMealItem).toList();
}
```

`MealMapper.xml` 对显式食材约束使用 `LEFT JOIN meal_knowledge k`、`JSON_CONTAINS` 和 `NOT JSON_OVERLAPS`；存在任一食材约束时附加 `k.ingredients IS NOT NULL AND JSON_LENGTH(k.ingredients) > 0`，禁止把未知知识当作满足条件。

- [x] **Step 5: 运行定向测试与阶段 001 回归**

Run: `mvn -Dtest=MealKnowledgeRepositoryTest,MealConstraintSearchTest,MealServiceKnowledgeTest,MySqlRetrievalBaselineTest,RetrievalDatasetTest test`

Expected: PASS；知识 CRUD 不读取任何 RAG 开关，阶段 001 数据集未改变。

- [x] **Step 6: 提交**

```bash
git add src/main/java/com/diet/service/meal src/main/java/com/diet/model src/main/java/com/diet/mapper/MealMapper.java src/main/resources/mapper/MealMapper.xml src/test/java/com/diet/service/meal
git commit -m "refactor: separate meal knowledge from retrieval"
```

### Task 2: 删除旧餐食 RAG 和派生索引（RRAG-01、RRAG-03）

**Files:**
- Modify: `src/main/java/com/diet/service/meal/MealPlanService.java`
- Modify: `src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java`
- Create: `src/main/resources/db/migrations/004_remove_meal_rag.sql`
- Delete: `src/main/java/com/diet/service/retrieval/EmbeddingClient.java`
- Delete: `src/main/java/com/diet/service/retrieval/HttpEmbeddingClient.java`
- Delete: `src/main/java/com/diet/service/retrieval/JdbcMealRetrievalStore.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealDocument.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealIndexCommand.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealIndexService.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealRetrievalRequest.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealRetrievalService.java`
- Delete: `src/main/java/com/diet/service/retrieval/MealRetrievalStore.java`
- Delete: `src/main/java/com/diet/service/retrieval/QdrantVectorStore.java`
- Delete: `src/main/java/com/diet/service/retrieval/ReciprocalRankFusion.java`
- Delete: `src/main/java/com/diet/service/retrieval/RetrievalConfiguration.java`
- Delete: `src/main/java/com/diet/service/retrieval/RetrievalDependencyException.java`
- Delete: `src/main/java/com/diet/service/retrieval/RetrievalProperties.java`
- Delete: `src/main/java/com/diet/service/retrieval/RetrievalSlots.java`
- Delete: `src/main/java/com/diet/service/retrieval/VectorStore.java`
- Delete: `src/main/resources/application-rag.yml`
- Delete: `src/test/java/com/diet/service/retrieval/HttpEmbeddingClientTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/HybridMealPlanTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/HybridOrchestratorTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/MealDocumentTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/MealIndexServiceTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/MealRetrievalServiceTest.java`
- Delete: `src/test/java/com/diet/service/retrieval/QdrantVectorStoreTest.java`
- Delete: `src/test/java/com/diet/evaluation/retrieval/HybridMySqlStoreTest.java`
- Delete: `src/test/java/com/diet/evaluation/retrieval/HybridRetrievalEvaluationTest.java`
- Delete: `docs/specs/002-hybrid-meal-rag/`
- Test: `src/test/java/com/diet/service/meal/MySqlOnlyRecommendationTest.java`
- Test: `src/test/java/com/diet/migration/RemoveMealRagMigrationTest.java`

**Interfaces:**
- Consumes: Task 1 `MealSearchRequest.constraints` 和 `MealKnowledgeRepository`。
- Produces: 推荐主链仅调用 `MealSearchService.search` + `MealRankService.rank`；数据库迁移只删除旧派生表。

- [x] **Step 1: 写无向量依赖推荐与迁移保留数据测试**

```java
assertThat(context.containsBean("mealRetrievalService")).isFalse();
assertThat(recommend("午餐清淡，不要牛肉")).isNotEmpty();
assertThat(query("select preparation from meal_knowledge where meal_id=9"))
        .isEqualTo("保留的用户做法");
```

- [x] **Step 2: 运行测试并确认仍存在旧 Bean/迁移**

Run: `mvn -Dtest=MySqlOnlyRecommendationTest,RemoveMealRagMigrationTest test`

Expected: FAIL，旧 `MealRetrievalService` 仍注册，清理迁移尚不存在。

- [x] **Step 3: 改写推荐调用并删除旧代码**

```java
List<MealItem> candidates = mealSearchService.search(
        new MealSearchRequest(state.sourceMode(), userId, state.slots(), excludes,
                state.mealConstraintContext().constraints()));
List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, state.slots(), excludes));
```

`004_remove_meal_rag.sql` 只执行：

```sql
DROP TABLE IF EXISTS meal_index_progress;
DROP TABLE IF EXISTS meal_index_task;
```

- [x] **Step 4: 运行推荐、会话、阶段 001 与迁移测试**

Run: `mvn -Dtest=MySqlOnlyRecommendationTest,RemoveMealRagMigrationTest,MealPlanServiceTest,RetrievalBaselineEvaluationTest,RetrievalMetricsTest,RetrievalReportTest test`

Expected: PASS；源码中不再有 `MealRetrievalService`、餐食 embedding 或餐食 Qdrant Bean。

- [x] **Step 5: 提交**

```bash
git add -A src/main/java/com/diet/service/retrieval src/test/java/com/diet/service/retrieval src/test/java/com/diet/evaluation/retrieval docs/specs/002-hybrid-meal-rag src/main/java/com/diet/service/meal src/main/java/com/diet/service/orchestrator src/main/resources
git commit -m "refactor: remove meal recommendation RAG"
```

### Task 3: 固定语料、Markdown 解析与父子分块（RRAG-06）

**Files:**
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeDocument.java`
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeChunk.java`
- Create: `src/main/java/com/diet/service/recipe/rag/corpus/RecipeCorpusManifest.java`
- Create: `src/main/java/com/diet/service/recipe/rag/corpus/HowToCookMarkdownParser.java`
- Create: `src/main/java/com/diet/service/recipe/rag/corpus/RecipeCorpusImporter.java`
- Create: `src/test/resources/recipe/howtocook/dishes/meat_dish/fixture.md`
- Test: `src/test/java/com/diet/service/recipe/rag/corpus/HowToCookMarkdownParserTest.java`
- Test: `src/test/java/com/diet/service/recipe/rag/corpus/RecipeCorpusImporterTest.java`

**Interfaces:**
- Produces: `RecipeCorpusImporter.importFrom(Path root, String commit)` → `ImportedCorpus(List<RecipeDocument>, List<RecipeChunk>, RecipeCorpusManifest)`。
- Stable IDs: `SHA-256(commit + "\n" + relativePath + "\n" + contentHash)`；子块 ID 再追加块类型和顺序。

- [x] **Step 1: 写结构解析和稳定 ID 测试**

```java
var corpus = importer.importFrom(fixtureRoot, HOW_TO_COOK_COMMIT);
assertThat(corpus.documents()).singleElement().satisfies(recipe -> {
    assertThat(recipe.ingredients()).contains("番茄", "鸡蛋");
    assertThat(recipe.steps()).hasSize(3);
});
assertThat(importer.importFrom(fixtureRoot, HOW_TO_COOK_COMMIT)).isEqualTo(corpus);
```

- [x] **Step 2: 运行测试并确认解析器缺失**

Run: `mvn -Dtest=HowToCookMarkdownParserTest,RecipeCorpusImporterTest test`

Expected: FAIL，父文档、子块和清单类型尚不存在。

- [x] **Step 3: 实现按标题分段、超长句切分和清单**

```java
public record RecipeChunk(String chunkId, String parentRecipeId, ChunkType type,
                          int order, String text, String contentHash) {}
```

解析 `dishes/**/*.md`，忽略隐藏文件、索引页和空菜谱；子块保留“必备原料和工具、计算、操作、附加内容”的语义边界，单块上限 1200 字符，二次切分重叠 120 字符。

- [x] **Step 4: 运行解析测试并核对清单哈希**

Run: `mvn -Dtest=HowToCookMarkdownParserTest,RecipeCorpusImporterTest test`

Expected: PASS；相同输入 ID/哈希一致，内容变化只影响对应父文档及子块。

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/diet/service/recipe/rag/model src/main/java/com/diet/service/recipe/rag/corpus src/test/java/com/diet/service/recipe/rag/corpus src/test/resources/recipe
git commit -m "feat: import HowToCook recipe corpus"
```

### Task 4: Lucene BM25 稀疏索引（RRAG-06、RRAG-09）

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/diet/service/recipe/rag/index/RecipeSparseIndex.java`
- Create: `src/main/java/com/diet/service/recipe/rag/index/LuceneRecipeSparseIndex.java`
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeHit.java`
- Test: `src/test/java/com/diet/service/recipe/rag/index/LuceneRecipeSparseIndexTest.java`

**Interfaces:**
- Consumes: `List<RecipeDocument>`、`List<RecipeChunk>`。
- Produces: `List<RecipeHit> search(String query, int topK)`，命中包含 `chunkId`、`parentRecipeId`、`score`、`rank`、`channel=BM25`。

- [x] **Step 1: 添加 Lucene 依赖并写中文菜名/食材检索失败测试**

```xml
<dependency><groupId>org.apache.lucene</groupId><artifactId>lucene-core</artifactId><version>9.12.3</version></dependency>
<dependency><groupId>org.apache.lucene</groupId><artifactId>lucene-analysis-common</artifactId><version>9.12.3</version></dependency>
<dependency><groupId>org.apache.lucene</groupId><artifactId>lucene-queryparser</artifactId><version>9.12.3</version></dependency>
```

```java
assertThat(index.search("番茄 鸡蛋 怎么炒", 5)).first()
        .extracting(RecipeHit::parentRecipeId).isEqualTo(tomatoEggId);
```

- [x] **Step 2: 实现索引版本目录、原子切换和 BM25 搜索**

使用 `DirectoryReader` 只读活动版本；先在临时版本目录构建并校验文档数，再原子更新 `active.json`。字段分别索引菜名、别名、食材、标题和正文，存储稳定 ID 与父块元数据。

- [x] **Step 3: 运行定向测试**

Run: `mvn -Dtest=LuceneRecipeSparseIndexTest test`

Expected: PASS；相同分数按 `parentRecipeId`、`chunkId` 稳定排序，重建失败仍能读取上一版。

- [x] **Step 4: 提交**

```bash
git add pom.xml src/main/java/com/diet/service/recipe/rag/index src/main/java/com/diet/service/recipe/rag/model/RecipeHit.java src/test/java/com/diet/service/recipe/rag/index
git commit -m "feat: add Lucene recipe index"
```

### Task 5: AgentScope embedding 与菜谱 Qdrant 索引（RRAG-06、07）

**Files:**
- Create: `src/main/java/com/diet/service/recipe/rag/index/RecipeEmbeddingModel.java`
- Create: `src/main/java/com/diet/service/recipe/rag/index/AgentScopeRecipeEmbeddingModel.java`
- Create: `src/main/java/com/diet/service/recipe/rag/index/RecipeVectorIndex.java`
- Create: `src/main/java/com/diet/service/recipe/rag/index/QdrantRecipeVectorIndex.java`
- Create: `src/main/java/com/diet/service/recipe/rag/RecipeRagProperties.java`
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeQuery.java`
- Test: `src/test/java/com/diet/service/recipe/rag/index/AgentScopeRecipeEmbeddingModelTest.java`
- Test: `src/test/java/com/diet/service/recipe/rag/index/QdrantRecipeVectorIndexTest.java`

**Interfaces:**
- Produces: `Mono<double[]> embed(String text)`；`void replaceVersion(String indexVersion, List<RecipeChunk> chunks, List<double[]> vectors)`；`Mono<List<RecipeHit>> search(RecipeQuery query, double[] vector, int topK)`。
- Query type: `RecipeQuery(String text, List<String> requiredIngredients, List<String> excludedIngredients, List<String> requiredTools, Integer maxMinutes)`。
- Qdrant payload: `indexVersion`、`chunkId`、`parentRecipeId`、`contentHash`、`sourcePath`；collection 名为 `diet_recipe_chunks_bge_m3_1024`。

- [x] **Step 1: 写真实 AgentScope API 适配测试**

```java
EmbeddingModel model = OllamaTextEmbedding.builder()
        .baseUrl("http://localhost:11434")
        .modelName("bge-m3:567m")
        .dimensions(1024)
        .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(3)).maxAttempts(1).build())
        .build();
assertThat(new AgentScopeRecipeEmbeddingModel(model).dimensions()).isEqualTo(1024);
```

- [x] **Step 2: 写 Qdrant collection 维度、过滤和原子版本测试**

用本地 `HttpServer` 断言 collection 创建为 1024 维 cosine、搜索必须过滤活动 `indexVersion`，维度不一致抛 `RecipeRagDependencyException`。

- [x] **Step 3: 实现 AgentScope 适配器和 Qdrant HTTP 客户端**

```java
public Mono<double[]> embed(String text) {
    return model.embed(TextBlock.builder().text(text).build())
            .filter(vector -> vector.length == dimensions)
            .switchIfEmpty(Mono.error(new RecipeRagDependencyException("embedding_dimension_mismatch")));
}
```

- [x] **Step 4: 运行定向测试**

Run: `mvn -Dtest=AgentScopeRecipeEmbeddingModelTest,QdrantRecipeVectorIndexTest test`

Expected: PASS；测试不要求本机运行 Ollama 或 Qdrant。

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/diet/service/recipe/rag/index src/main/java/com/diet/service/recipe/rag/RecipeRagProperties.java src/test/java/com/diet/service/recipe/rag/index
git commit -m "feat: add recipe dense index"
```

### Task 6: 串行/并行混合检索、RRF 与父块聚合（RRAG-07、RRAG-08、RRAG-09）

**Files:**
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeRetrievalResult.java`
- Create: `src/main/java/com/diet/service/recipe/rag/model/RetrievedRecipe.java`
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/RecipeRetrievalMode.java`
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/RecipeRrf.java`
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/RecipeParentResolver.java`
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/HybridRecipeRetriever.java`
- Test: `src/test/java/com/diet/service/recipe/rag/retrieval/RecipeRrfTest.java`
- Test: `src/test/java/com/diet/service/recipe/rag/retrieval/HybridRecipeRetrieverTest.java`

**Interfaces:**
- Produces: `Mono<RecipeRetrievalResult> retrieve(RecipeQuery query, RecipeRetrievalMode mode)`。
- Diagnostics: 每分支耗时、总耗时、降级原因、候选数、最终父文档 ID 和实际模式。

- [x] **Step 1: 写 RRF 稳定排序与父文档去重测试**

```java
assertThat(RecipeRrf.fuse(bm25Hits, denseHits, 60)).extracting(RecipeHit::parentRecipeId)
        .containsExactly("recipe-a", "recipe-b");
```

- [x] **Step 2: 写并行重叠、串并行等价和故障降级测试**

```java
var parallel = retriever.retrieve(query, PARALLEL_HYBRID).block();
var serial = retriever.retrieve(query, SERIAL_HYBRID).block();
assertThat(parallel.recipes()).isEqualTo(serial.recipes());
assertThat(probe.maximumConcurrentBranches()).isEqualTo(2);
```

- [x] **Step 3: 实现 Reactor 并行编排**

```java
Mono<BranchResult> sparse = Mono.fromCallable(() -> sparseIndex.search(query.text(), bm25TopK))
        .subscribeOn(Schedulers.boundedElastic()).timeout(branchTimeout);
Mono<BranchResult> dense = embedding.embed(query.embeddingText())
        .flatMap(vector -> vectorIndex.search(query, vector, denseTopK)).timeout(branchTimeout);
return Mono.zip(sparse.materialize(), dense.materialize())
        .map(signals -> fuseOrDegrade(query, signals.getT1(), signals.getT2()));
```

- [x] **Step 4: 运行检索测试**

Run: `mvn -Dtest=RecipeRrfTest,HybridRecipeRetrieverTest test`

Expected: PASS；串并行排序一致，单分支失败返回降级结果，双分支失败返回错误。

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/diet/service/recipe/rag/model src/main/java/com/diet/service/recipe/rag/retrieval src/test/java/com/diet/service/recipe/rag/retrieval
git commit -m "feat: add parallel hybrid recipe retrieval"
```

### Task 7: 查询改写、重排与配置装配（RRAG-10）

**Files:**
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/RecipeQueryRewriter.java`
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/IdentityRecipeQueryRewriter.java`
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/RecipeReranker.java`
- Create: `src/main/java/com/diet/service/recipe/rag/retrieval/IdentityRecipeReranker.java`
- Create: `src/main/java/com/diet/service/recipe/rag/RecipeRagConfiguration.java`
- Create: `src/main/resources/application-recipe-rag.yml`
- Test: `src/test/java/com/diet/service/recipe/rag/retrieval/RecipeQueryRewriteTest.java`
- Test: `src/test/java/com/diet/service/recipe/rag/RecipeRagConfigurationTest.java`

**Interfaces:**
- Produces: `Mono<RecipeQuery> rewrite(RecipeQuery original)` 和 `Mono<List<RetrievedRecipe>> rerank(RecipeQuery, List<RetrievedRecipe>)`。
- Defaults: `enabled=false`，identity 实现不调用模型；超时或错误返回输入对象和原顺序。

- [x] **Step 1: 写否定约束不可丢失与配置默认关闭测试**

```java
var original = new RecipeQuery("宫保鸡丁怎么做，不要花生", List.of(), List.of("花生"), List.of(), null);
assertThat(rewriter.rewrite(original).block().excludedIngredients())
        .containsExactly("花生");
assertThat(properties.isEnabled()).isFalse();
```

- [x] **Step 2: 实现接口、identity 默认值和条件 Bean**

配置完整声明固定 commit、Lucene 目录、Qdrant URL/collection、`bge-m3:567m`、1024 维、top-k、RRF k、分支超时和重排/改写开关；不得修改用户已有 `application.yml`。

- [x] **Step 3: 运行配置与查询测试**

Run: `mvn -Dtest=RecipeQueryRewriteTest,RecipeRagConfigurationTest test`

Expected: PASS；RAG 关闭时不创建外部连接，推荐 Bean 正常启动。

- [x] **Step 4: 提交**

```bash
git add src/main/java/com/diet/service/recipe/rag src/main/resources/application-recipe-rag.yml src/test/java/com/diet/service/recipe/rag
git commit -m "feat: configure recipe RAG pipeline"
```

### Task 8: 带引用回答、拒答与菜谱入口（RRAG-05、RRAG-09、RRAG-10）

**Files:**
- Create: `src/main/java/com/diet/agent/builder/RecipeAnswerAgentBuilder.java`
- Modify: `src/main/java/com/diet/agent/factory/AgentFactory.java`
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeCitation.java`
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeAnswer.java`
- Create: `src/main/java/com/diet/service/recipe/rag/answer/RecipeEvidenceAssembler.java`
- Create: `src/main/java/com/diet/service/recipe/rag/answer/RecipeAnswerGenerator.java`
- Create: `src/main/java/com/diet/service/recipe/rag/answer/AgentScopeRecipeAnswerGenerator.java`
- Create: `src/main/java/com/diet/service/recipe/RecipeQueryRouter.java`
- Create: `src/main/java/com/diet/service/recipe/RecipeQueryService.java`
- Modify: `src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java`
- Test: `src/test/java/com/diet/service/recipe/rag/answer/RecipeAnswerGeneratorTest.java`
- Test: `src/test/java/com/diet/service/recipe/RecipeQueryServiceTest.java`
- Test: `src/test/java/com/diet/service/recipe/RecipeOrchestratorTest.java`

**Interfaces:**
- Produces: `RecipeQueryService.answer(sessionId, userId, message, context)` → `RecipeAnswer(text, citations, refused, diagnostics)`。
- Structured context: `recipeQuery` 可含 `mealId`、`query`、`requiredIngredients`、`excludedIngredients`；未知字段拒绝。

- [x] **Step 1: 写直接查询、个人做法优先、公共回退与拒答测试**

```java
assertThat(service.answer("s", 7L, "宫保鸡丁怎么做", Map.of()).citations()).isNotEmpty();
assertThat(service.answer("s", 7L, "这道菜怎么做", Map.of("recipeQuery", Map.of("mealId", 41))))
        .extracting(RecipeAnswer::text).asString().contains("用户录入");
assertThat(service.answer("s", 7L, "量子计算机怎么做", Map.of()).refused()).isTrue();
```

- [x] **Step 2: 实现证据预算、Agent prompt 与引用校验**

```text
你只能使用下方 evidence 回答。每条食材和步骤必须引用 [S1] 形式的来源编号；
缺少关键步骤、约束冲突或证据不足时输出 refused=true；不得补写热量、营养和未出现的替换方案。
```

解析模型 JSON 后逐条验证引用 ID 属于本次 evidence；无效引用、候选外菜谱或约束冲突直接回退为确定性证据模板或拒答。

- [x] **Step 3: 在 PERSONAL 空库检查和六意图识别之前接入菜谱路由**

直接菜名问题进入公共 RAG；携带 `mealId` 时先调用 `MealKnowledgeRepository.findPersonal(mealId,userId)`，存在做法则直接回答，缺失才用餐食名称查公共 RAG。普通推荐不触发任何菜谱检索。

- [x] **Step 4: 运行回答与编排测试**

Run: `mvn -Dtest=RecipeAnswerGeneratorTest,RecipeQueryServiceTest,RecipeOrchestratorTest,IntentEvaluationDatasetTest test`

Expected: PASS；原六意图评测数据不变，菜谱问题无需先推荐。

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/diet/agent src/main/java/com/diet/service/recipe src/main/java/com/diet/service/orchestrator src/test/java/com/diet/service/recipe
git commit -m "feat: answer recipe questions with citations"
```

### Task 9: 菜谱索引命令与失败安全切换（RRAG-06）

**Files:**
- Create: `src/main/java/com/diet/service/recipe/rag/corpus/RecipeIndexCommand.java`
- Create: `src/main/java/com/diet/service/recipe/rag/index/RecipeIndexCoordinator.java`
- Test: `src/test/java/com/diet/service/recipe/rag/index/RecipeIndexCoordinatorTest.java`

**Interfaces:**
- Produces: `RecipeIndexCoordinator.rebuild(Path corpusRoot)` → `IndexBuildResult`；Windows 示例命令参数为 `--recipe-rag-index=E:/data/HowToCook`，其他环境同样传入绝对路径。
- Commit rule: Lucene 和 Qdrant 都完成后写活动版本；任一失败时保持上一版本。

- [x] **Step 1: 写双索引成功才切换的失败测试**

```java
vectorIndex.failNextBuild();
assertThatThrownBy(() -> coordinator.rebuild(corpusRoot)).isInstanceOf(RecipeRagDependencyException.class);
assertThat(coordinator.activeVersion()).isEqualTo(previousVersion);
```

- [x] **Step 2: 实现两阶段构建与命令入口**

构建版本使用 `corpusHash-parserVersion-embeddingModel`；先写非活动 Lucene/Qdrant 版本，校验父文档数和子块数一致，再更新活动清单。失败时删除本次未激活版本，不触碰旧版本。

- [x] **Step 3: 运行协调器和解析回归**

Run: `mvn -Dtest=RecipeIndexCoordinatorTest,RecipeCorpusImporterTest,LuceneRecipeSparseIndexTest,QdrantRecipeVectorIndexTest test`

Expected: PASS；重复输入幂等，失败后查询仍读取原活动版本。

- [x] **Step 4: 提交**

```bash
git add src/main/java/com/diet/service/recipe/rag/corpus/RecipeIndexCommand.java src/main/java/com/diet/service/recipe/rag/index/RecipeIndexCoordinator.java src/test/java/com/diet/service/recipe/rag/index/RecipeIndexCoordinatorTest.java
git commit -m "feat: add reproducible recipe index build"
```

### Task 10: 固定评测集、指标与消融矩阵（RRAG-08、RRAG-11）

**Files:**
- Create: `src/test/resources/evaluation/recipe/queries.jsonl`
- Create: `src/test/resources/evaluation/recipe/answers.jsonl`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeEvaluationDataset.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeRetrievalMetrics.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeAnswerMetrics.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeEvaluationReport.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeRetrievalEvaluationTest.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeParallelLatencyTest.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeExperiment.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeEvaluationDatasetTest.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeRetrievalMetricsTest.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeAnswerMetricsTest.java`
- Create: `docs/specs/004-recipe-rag/annotations.md`

**Interfaces:**
- Dataset: `queryId`、`query`、`slice`、`relevantRecipeIds`、`grades`、`mustRefuse`、`requiredFacts`、`forbiddenFacts`。
- Report: 语料/应用/模型版本，Recall@1/5/10、MRR@10、nDCG@10、Parent Recall、重复率、空结果率、回答指标及各阶段 p50/p95。

- [x] **Step 1: 写指标公式和数据泄漏保护测试**

```java
assertThat(metrics.recallAt(relevant("a", "b"), ranked("a", "c"), 2)).isEqualTo(0.5);
assertThat(dataset.devIds()).doesNotContainAnyElementsOf(dataset.testIds());
```

- [x] **Step 2: 建立至少 64 条独立查询并锁定 qrels 哈希**

八个切片各至少 8 条：精确菜名、别名、食材反查、技法、包含/排除、厨具/耗时、多条件长查询、语料外拒答。`annotations.md` 记录每条标注依据和固定语料文件，不从任何检索结果反推标签。

- [x] **Step 3: 实现七组消融运行与 JSON 报告**

```java
for (var experiment : List.of(RecipeExperiment.BM25, RecipeExperiment.DENSE,
        RecipeExperiment.SERIAL_HYBRID, RecipeExperiment.PARALLEL_HYBRID,
        RecipeExperiment.PARALLEL_PARENT, RecipeExperiment.PARALLEL_PARENT_RERANK,
        RecipeExperiment.PARALLEL_PARENT_REWRITE)) {
    report.add(run(experiment, dataset.lockedTest()));
}
```

- [x] **Step 4: 运行离线指标测试和可选真实依赖评测**

Run: `mvn -Dtest=RecipeRetrievalMetricsTest,RecipeAnswerMetricsTest,RecipeEvaluationDatasetTest,RecipeParallelLatencyTest test`

Run with local services: `mvn -Dtest=RecipeRetrievalEvaluationTest -Ddiet.recipe-rag.live=true test`

Expected: 离线测试始终 PASS；真实评测输出 `target/evaluation/recipe/report.json` 并记录不可用依赖，而不是伪造分数。

- [x] **Step 5: 提交**

```bash
git add src/test/java/com/diet/evaluation/recipe src/test/resources/evaluation/recipe docs/specs/004-recipe-rag/annotations.md
git commit -m "test: add recipe RAG evaluation matrix"
```

### Task 11: 视频搜索 MCP 契约骨架（RRAG-12）

**Files:**
- Delete: `src/main/java/com/diet/service/cooking/RecipeMcpServer.java`
- Delete: `src/main/java/com/diet/service/cooking/RecipeMcpClient.java`
- Delete: `src/main/java/com/diet/service/cooking/RecipeProvider.java`
- Delete: `src/main/java/com/diet/service/cooking/LocalRecipeProvider.java`
- Delete: `src/main/java/com/diet/service/cooking/CookingArguments.java`
- Delete: `src/main/java/com/diet/service/cooking/CookingConfiguration.java`
- Delete: `src/main/java/com/diet/service/cooking/CookingProperties.java`
- Delete: `src/main/java/com/diet/service/cooking/CookingRequest.java`
- Delete: `src/main/java/com/diet/service/cooking/CookingRouter.java`
- Delete: `src/main/java/com/diet/service/cooking/CookingService.java`
- Modify: `src/main/java/com/diet/DietApplication.java`
- Delete: `src/main/resources/diet/cooking/recipes.json`
- Delete: `src/main/resources/diet/cooking/substitutions.json`
- Delete: `src/test/java/com/diet/service/cooking/`
- Create: `src/main/java/com/diet/service/video/CookingVideo.java`
- Create: `src/main/java/com/diet/service/video/VideoPlatform.java`
- Create: `src/main/java/com/diet/service/video/CookingVideoSearchProvider.java`
- Create: `src/main/java/com/diet/service/video/DisabledCookingVideoSearchProvider.java`
- Create: `src/main/java/com/diet/service/video/FakeCookingVideoSearchProvider.java`
- Create: `src/main/java/com/diet/service/video/CookingVideoSearchService.java`
- Create: `src/main/java/com/diet/service/video/VideoMcpProperties.java`
- Test: `src/test/java/com/diet/service/video/CookingVideoSearchServiceTest.java`

**Interfaces:**
- Produces: `Mono<List<CookingVideo>> search(String query, VideoPlatform platform, int limit)`。
- Tool contract: `search_cooking_videos(query, platform?, limit?)`；`platform` 仅允许 `BILIBILI`、`DOUYIN`、`ANY`，`limit` 范围 1–10。

- [x] **Step 1: 写默认禁用、fake 成功和普通问答零调用测试**

```java
assertThat(disabled.search("宫保鸡丁", ANY, 3)).failsWithin(Duration.ofSeconds(1));
assertThat(fake.search("宫保鸡丁", BILIBILI, 2).block()).hasSize(2);
verifyNoInteractions(videoProvider); // 普通推荐和文字菜谱问答
```

- [x] **Step 2: 删除旧食谱/替换 MCP 并实现新 provider 边界**

服务只在结构化 `videoSearch` 或“视频/跟做/教程链接”等明确请求中调用；超时返回“视频搜索暂时不可用”，不影响文字菜谱结果。fake URL 使用 `https://example.invalid/...`，不冒充真实平台内容。

- [x] **Step 3: 运行视频、菜谱与编排回归**

Run: `mvn -Dtest=CookingVideoSearchServiceTest,RecipeOrchestratorTest,RecipeAnswerGeneratorTest test`

Expected: PASS；源码和资源中不再包含旧 `search_recipes/get_recipe/suggest_substitutions` MCP 注册。

- [x] **Step 4: 提交**

```bash
git add -A src/main/java/com/diet/service/cooking src/main/resources/diet/cooking src/main/java/com/diet/service/video src/test/java/com/diet/service/video
git commit -m "refactor: replace recipe MCP with video search contract"
```

### Task 12: 全量验证、运行手册与验收记录（RRAG-01 至 RRAG-12）

**Files:**
- Create: `docs/specs/004-recipe-rag/runbook.md`
- Create: `docs/specs/004-recipe-rag/acceptance.md`
- Modify: `docs/specs/004-recipe-rag/tasks.md`
- Modify: `docs/specs/README.md`

**Interfaces:**
- Runbook 覆盖：固定语料获取与校验、Ollama `bge-m3:567m`、Qdrant、索引命令、应用配置、评测命令、降级排查和版本切换。
- Acceptance 按 RRAG-01 至 RRAG-12 逐项记录命令、结果、限制和生成报告哈希。

- [x] **Step 1: 执行静态边界检查**

Run: `rg -n "MealRetrievalService|meal_index_task|meal_index_progress|search_recipes|get_recipe|suggest_substitutions" src/main src/test`

Expected: 只允许清理迁移或历史说明中的表名；生产代码没有旧餐食 RAG 和旧食谱 MCP 符号。

- [x] **Step 2: 运行默认离线全量测试与打包**

Run: `mvn test`

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS；默认环境不需要 Ollama、Qdrant、HowToCook 工作树或视频平台凭据。

- [x] **Step 3: 运行本地真实 RAG 验证**

Run: `mvn -Dtest=RecipeRetrievalEvaluationTest -Ddiet.recipe-rag.live=true test`

Expected: 使用固定 HowToCook commit、Ollama `bge-m3:567m` 和本地 Qdrant生成报告；如本机依赖不可用，验收记录保留明确的未验证项和原始错误，不写通过。

实际：2026-10-01 对 Ollama 与 Qdrant 的只读探测均在 3 秒后超时，未运行真实模式；`acceptance.md` 已按未验证记录，未生成或填充实验分数。

- [x] **Step 4: 写运行手册、验收证据并更新任务勾选**

`acceptance.md` 逐项关联 RRAG 编号、测试类、命令、结果与报告路径；记录 All-in-RAG 仅为方法参考、HowToCook 的固定版本与授权。

- [x] **Step 5: 提交**

```bash
git add docs/specs/004-recipe-rag docs/specs/README.md
git commit -m "docs: record recipe RAG acceptance"
```
