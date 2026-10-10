# 菜谱 RAG 评测集 v2 验收记录

日期：2026-10-04。

## Bad trace 修复

“宫保鸡丁咋做啊”此前不会命中 `RecipeQueryRouter`，随后进入旧意图分类并落到 `OTHER`。修复后：

- 本地菜谱路由支持“咋做、怎么弄、咋弄、制作步骤、要哪些食材”等常见表达；
- 意图模型、Jev criteria、前端 Trace 筛选和状态机新增 `RECIPE_QUERY`；
- 明显菜谱查询仍在通用 Intent Agent 之前进入 RAG；规则未命中的表达可由 `RECIPE_QUERY` 意图兜底；
- 菜谱回答的会话状态和 Trace 记录为 `RECIPE_QUERY`，不再借用 `OTHER`。

回归测试 `RecipeQueryRouterTest` 先以“宫保鸡丁咋做啊”稳定复现失败，修复后通过。

## v2 数据集

| 项目 | 结果 |
| --- | ---: |
| 查询总数 | 90 |
| 检索正例 | 78 |
| 不同 Gold 菜谱 | 39 |
| 多 Gold 查询 | 12 |
| test 检索正例 | 26 |
| test 不同 Gold 菜谱 | 18 |
| 单条 test 最大影响 | 3.846% |
| near-OOD | 4 |
| unsupported-answer | 4 |
| v2 fingerprint | `f59d67bece51a61cca10f2f70e0424f0ca42f4991003a30d59fbea1fc31de2f6` |

v1 的 64 条查询及 fingerprint `69ef43df658bd73154eb90daefed967a213aae67bfae20d8cfed0abf5e94bc4f` 保存在 `src/test/resources/evaluation/recipe/v1/`，没有根据历史测试结果原地修改。

v2 已通过生产 `RecipeCorpusImporter` 校验。检查过程中发现 `西红柿鸡蛋汤.md` 和 `茄子炖土豆.md` 虽存在于仓库，但不能被当前生产解析器导入，因此没有保留为 Gold；替代标注只使用实际进入索引的文档。

## 评测代码

- `retrievalExpectation` 与 `answerExpectation` 分开建模；
- `REFUSE_UNSUPPORTED` 仍参与检索 Recall/MRR/nDCG；
- dev/test 按 Gold recipe ID 的连通分组隔离；
- 报告记录数据集版本、语料文档数、覆盖统计和单条测试影响；
- 实验结果增加 empty-expectation accuracy 和 near-OOD false-positive rate；
- 实时评测默认运行完整 v2 test，不再只跑单个 bad case；通过 `-Ddiet.recipe-evaluation.version=v1` 可复现 v1。

## 验证

```text
mvn -Dtest=RecipeEvaluationDatasetTest -Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook test
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0

mvn test
Tests run: 103, Failures: 0, Errors: 0, Skipped: 7

mvn -DskipTests package
BUILD SUCCESS

mvn -Dtest=RecipeRetrievalEvaluationTest -Ddiet.recipe-rag.live=true \
    -Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook test
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

跳过项是需要显式启用的真实 Intent、MySQL、Ollama/Qdrant 或 Bilibili MCP 集成测试。真实检索评测使用冻结后的 v2 fingerprint 和 334 篇生产解析器实际导入的菜谱，结果如下：

| 实验 | Recall@1 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | P50 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| BM25 | 49.04% | 75.00% | 78.85% | 72.56% | 70.05% | 11 ms | 26 ms |
| Dense | 72.12% | 89.42% | 89.42% | 92.31% | 89.97% | 29 ms | 44 ms |
| 串行 Hybrid | 62.50% | 87.50% | 91.35% | 85.58% | 83.60% | 35 ms | 42 ms |
| 并行 Hybrid | 62.50% | 87.50% | 91.35% | 85.58% | 83.60% | 27 ms | 31 ms |
| 并行 + 父块 | 62.50% | 87.50% | 91.35% | 85.58% | 83.60% | 25 ms | 33 ms |
| 并行 + 父块 + 重排 | 62.50% | 87.50% | 91.35% | 85.58% | 83.60% | 25 ms | 34 ms |
| 并行 + 父块 + 改写 | 62.50% | 87.50% | 91.35% | 85.58% | 83.60% | 25 ms | 34 ms |

并行 Hybrid 与串行 Hybrid 的排序质量一致，P50 从 35 ms 降到 27 ms，P95 从 42 ms 降到 31 ms。当前重排和改写仍是 identity 基线，因此后三组质量分数一致，不能描述为模型重排或查询改写带来了提升。

四条 expected-empty 测试在各组实验中都收到非空结果，empty-expectation accuracy 为 0%，near-OOD false-positive rate 为 100%。这不是标注问题，而是当前检索器始终返回 top-k、尚未实现检索置信度阈值的结果。下一轮应只使用 dev 集选择拒答阈值，再在锁定 test 上复验；不得根据这次 test 结果修改 qrels。

回答生成评测本次未运行，因为主聊天模型没有作为评测依赖配置；报告中的 `answerMetrics.status` 为 `not_run`。
