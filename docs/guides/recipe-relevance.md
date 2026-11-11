# 菜谱重排与相关性筛选

更新日期：2026-10-19。已完成可配置 API 接入和本地 HTTP fixture 验证；目前没有 Rerank Key，未运行真实模型评测，也没有已校准的阈值。默认仍为 identity，不能把本文中的能力写成已在线启用或已取得指标提升。

## 解决什么问题

Top-K 表示相对靠前，不表示足够相关。此前语料外查询也会返回最相似的菜谱，直接交给回答模型可能答非所问。本阶段增加一个专用 Cross-Encoder：同时读取完整问题与候选菜谱，返回相关性分数，再决定哪些证据可以进入生成。

```text
指代解析后的完整问题 ──────────────────────┐
  → 可选改写 → BM25/dense 并行 → RRF → 父候选最多20道
                                          ↓
                              一次 Rerank API 批量评分
                                          ↓
                              分数 >= 显式阈值 → 最多3道
                                          ↓
                                   引用证据生成回答
```

重排读改写前、指代解析后的问题，避免改写丢掉用户限定条件；生成也保留完整问题。每道候选按菜名、别名、食材和步骤拼接，默认最多 3000 个 Java 字符，报告记录实际发送文本的 SHA-256。长文截断会影响效果，修改预算后应重新校准。

Embedding 的 `bge-m3` 与 Rerank 的 `BAAI/bge-reranker-v2-m3` 是两个模型。前者分别编码问题、文档用于召回；后者联合打分用于精排。RRF 分数与重排分数分别保留，不能把两者混用。重排分数也不等于相关概率。

## 配置位置与开启方式

配置在 `src/main/resources/application-recipe-rag.yml` 的 `diet.recipe-rag.rerank`。默认 `enabled: false`，不会请求重排服务。启用后检查配置，缺 Key 或开启过滤但没给阈值时启动失败。

| 配置 | 默认值 / 含义 |
| --- | --- |
| enabled | false；保持现有 identity 行为 |
| endpoint | `https://api.siliconflow.cn/v1/rerank`，可由 `RECIPE_RERANK_ENDPOINT` 覆盖 |
| api-key | 读取 `RECIPE_RERANK_API_KEY`；只放本机环境变量或 IDEA 环境变量 |
| model | `BAAI/bge-reranker-v2-m3`，可由 `RECIPE_RERANK_MODEL` 覆盖 |
| timeout | 2s；单次调用，无自动重试 |
| candidate-top-k | 20；融合并恢复父块后，最多送多少道菜谱评分 |
| top-n | 3；最终最多多少道进入生成 |
| max-document-chars | 3000；每篇评分文本字符上限 |
| filter-enabled | true；仅重排对照可设 false |
| min-score | 无默认值；必须用 dev 校准后显式设置 |

IDEA 的 Run Configuration：保留原有模型、数据库配置，在 Environment variables 增加 `RECIPE_RERANK_API_KEY`。Program arguments 保留现有 profile 和语料路径，再加 `--diet.recipe-rag.rerank.enabled=true --diet.recipe-rag.rerank.filter-enabled=false`，即可只做重排联调；这时尚无相关性拒绝能力。

校准后，将 `filter-enabled` 改为 true，再加 `--diet.recipe-rag.rerank.min-score=<开发集冻结阈值>`。尖括号内容必须替换，不能原样运行。模型、服务商、文本预算变化后，旧阈值不能直接沿用。项目不会自动读取任意 `.env` 文件，Maven 终端与 IDEA 也不共享运行配置中的环境变量。

API 采用 `model/query/documents/top_n/return_documents` 的文本重排协议，响应需有 `results[].index/relevance_score`，其他协议需要适配。请求所有候选分数后本地筛选；按 index 取回本地原文，服务端返回的正文不能替换证据。参考 [SiliconFlow 接口](https://docs.siliconflow.cn/docs/api/rerank-post) 与 [BGE 模型说明](https://bge-model.com/Introduction/reranker.html)。

## 状态与故障边界

| 状态 | 含义 | 行为 |
| --- | --- | --- |
| BYPASSED | 未启用真实重排 | 保持默认链路 |
| ACCEPTED | 至少一道通过筛选 | 仅将通过的本地证据交给生成器 |
| FILTERED | 成功处理但无可用证据 | 不调用生成器，说明证据不足 |
| UNAVAILABLE | API 超时、HTTP 错误、非法或不完整评分 | 不调用生成器，说明筛选服务不可用 |

重复/越界/漏项索引、非数字/非有限分数、空或非法 JSON 都按服务失败处理。错误仅记录受控代码，不记录 Key 或供应商原始错误正文。真实重排不使用原先“失败返回全部候选”的回退，因为这会绕过证据筛选。

Trace 新增 `RECIPE_RELEVANCE_CHECKED`，包含模型、阈值、候选的 RRF/重排分数、保留数量、耗时、错误代码和文本哈希。菜谱失败时视频分支可继续返回真实搜索结果；视频也失败则明确失败，不编造链接。视频功能仍需单独启用 `video-mcp`。

此功能检查相关性，不等于事实核验、过敏原校验或“不要花生”的硬约束解析。已有结构化食材约束仍由父块过滤处理；本阶段没有新增口语约束抽取。

## 先跑不依赖真实服务的验证

在项目根目录的 PowerShell 执行：

```powershell
mvn "-Dtest=HttpRecipeRerankerTest,RecipeRagConfigurationTest,RecipeQueryServiceTest,RecipeVideoCoordinatorTest,RecipeRerankEvaluationReportTest,RecipeRerankReplayTest" test
```

HTTP 测试仅启动本机临时服务，返回人工固定分数，用于验证协议、排序、阈值边界与异常隔离。它们不能证明真实模型能过滤某条 OOD，也不能提供真实推理延迟。

## 有 Key 后：先采集 dev 分数

入口 `src/test/java/com/diet/evaluation/recipe/RecipeRerankEvaluationTest.java`。使用原 v2 的 90 条固定查询（60 dev、30 test），不改 qrels；原锁定 test 已在历史实验中被查看，新增阈值研究仍只用 dev，若需要新的无偏结论应补充独立未见保留集。

准备固定 commit 的 HowToCook、Ollama `bge-m3`、Qdrant，以及终端可读取的 `RECIPE_RERANK_API_KEY`。此显式 live 命令会调用 embedding/重排服务并创建评测索引，可能产生 API 费用。普通 `mvn test` 不会触发。

```powershell
mvn "-Dtest=RecipeRerankEvaluationTest" "-Ddiet.recipe-rerank.live=true" "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" "-Ddiet.recipe-rerank.repeats=3" test
```

默认只跑 dev、采集分数，不猜阈值。首次可加 `-Ddiet.recipe-rerank.limit=3` 做连通性检查；这种小样本报告不能代替完整评测。Qdrant 默认 `http://localhost:16333`，可由 `-Ddiet.recipe-rag.qdrant.base-url=...` 覆盖。只在独立集合 `diet_recipe_relevance_eval` 构建、清理本轮索引；覆盖集合名必须以 `_eval` 结尾，不可指向生产集合，不可并发使用同一个评测集合。

供应商覆盖参数沿用配置表中的环境变量；评分超时另用 `-Ddiet.recipe-rerank.timeout-seconds=5`，候选和文本预算用 `-Ddiet.recipe-rerank.candidate-top-k=20`、`-Ddiet.recipe-rerank.max-document-chars=3000`。如果本机必须通过系统代理访问 API，可在测试命令增加 `"-DargLine=-Djava.net.useSystemProxies=true"`，实际代理是否生效仍需按本机环境验证。

报告保存在 `target/evaluation/recipe/relevance/<runId>/report.json`，每条查询结束写 checkpoint。记录语料、数据集、模型、候选预算、查询/文本哈希和 Git revision；未提交工作应同时保存 diff，revision 本身不能复现未提交代码。认证错误或连续故障会提前停止，报告 `complete=false`。

每条查询只召回一次、评分一次，同一组候选派生三组结果：

| 组别 | 处理 |
| --- | --- |
| BASELINE | 原 RRF 排序 |
| RERANK_ONLY | 按相关性分数排序，不拒绝 |
| FILTERED | 按相关性分数排序，并按指定阈值拒绝 |

比较 Recall@1/5/10、MRR@10、nDCG@10、空预期查询错误接受率、near-OOD 错误接受率、正样本误拒率，以及基线已召回相关证据的丢失率。报告评测 Top-K 为 10，用于看召回和排序；线上生成预算为 3，二者口径不同。

检索失败、单支降级和评分失败分别统计，排除出三组共享的正常质量样本，分母为零时写 null。故障不能算拒答成功；`comparisonReady` 只有在完整、无故障/降级且指定阈值时才为 true，它不代表指标已经达标。P50/P95 是检索与评分阶段耗时，不是包含生成和视频的端到端延迟。三组复用测量值，不能称为三次独立线上 A/B 实验。

## 离线调整阈值与冻结测试

有真实 dev 分数报告后，可不再调用 API，在同一批分数上离线比较阈值：

```powershell
# 0.5 仅为命令格式示例，不是项目推荐阈值；路径换成本机 dev 报告。
mvn "-Dtest=RecipeRerankEvaluationTest" "-Ddiet.recipe-rerank.replay=target/evaluation/recipe/relevance/DEV_RUN/report.json" "-Ddiet.recipe-rerank.min-score=0.5" test
```

回放检查数据集指纹、样本身份、完整评分和检索健康诊断，写新报告并保存来源哈希；不覆盖原报告、不测新延迟。先约定 dev 上可接受的误拒上限，再权衡 OOD 错误接受、排序质量和延迟；不能只挑能过滤旧 4 条 OOD 的阈值。

冻结模型、候选/文本预算和阈值后，记录 dev 报告 runId，再运行一次 test：

```powershell
# 同样替换示例阈值和 DEV_RUN；不根据 test 结果再调参。
mvn "-Dtest=RecipeRerankEvaluationTest" "-Ddiet.recipe-rerank.live=true" "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" "-Ddiet.recipe-rerank.split=test" "-Ddiet.recipe-rerank.frozen=true" "-Ddiet.recipe-rerank.calibration-run-id=DEV_RUN" "-Ddiet.recipe-rerank.min-score=0.5" "-Ddiet.recipe-rerank.repeats=3" test
```

test 回放也要求冻结标识与校准 runId，且禁止变更原 test 报告的阈值。这是防误操作的流程约束，不是加密审计或对模型配置一致性的自动认证。

旧七组检索评测的 rewrite/rerank 标签采用 identity，不能用旧报告证明本次模型收益。本入口只测候选相关性和准入，Ragas 的回答忠实性与回答质量仍属于另外一层评测。

## 读代码顺序

1. `RecipeRagProperties.Rerank`、`RecipeRagConfiguration`：开关与配置校验。
2. `RecipeQueryConfiguration`：20 候选预算与最终输出预算分离。
3. `HttpRecipeReranker`：构造请求、索引映射、分数校验和阈值筛选。
4. `RecipeRerankResult`、`RecipeQueryService`：结构化结果与生成前准入。
5. `RecipeVideoCoordinator`：菜谱失败与视频成功的组合。
6. `RecipeRerankEvaluationReport`、`RecipeRerankEvaluationTest`：三组共享候选的统计和运行入口。

规格与验收见 [013](../specs/013-recipe-relevance/spec.md)。
