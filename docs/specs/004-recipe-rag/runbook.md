# 菜谱 RAG 运行手册

> 本文保留阶段 004 的基础操作。真实视频 MCP 以阶段 005 为准，并行编排/Ragas 以阶段 006 为准，模型查询改写以阶段 007 为准；完整练习顺序见 [学习手册](../../guides/recipe-rag-mcp-evaluation.md)。

本手册对应公共菜谱 RAG。餐食推荐只查 MySQL；公共菜谱问答才使用 Lucene、embedding 和 Qdrant；个人餐食做法保存在 MySQL `meal_knowledge`；真实视频搜索见阶段 005。

## 固定版本

| 对象 | 版本 |
| --- | --- |
| HowToCook | `1.6.0` / `a0b14bd388ee996943173679c3a7c9db7da04a6c` |
| All-in-RAG 方法参考 | `583a61b09869bc3afc4552289171f6ec188f2c76` 第八章 |
| AgentScope Java | `1.0.11` |
| Lucene | `9.12.3` |
| embedding 基线 | Ollama `bge-m3:567m`，1024 维 |

HowToCook 固定 commit 使用 Unlicense。All-in-RAG 的固定 commit 在 README 中声明 CC BY-NC-SA 4.0；本项目只参考其流程、切分和评测方法，Java 实现未复制其 Python 代码。更新任一来源前重新核对目标 commit 的授权文件。

## 1. 准备 HowToCook

PowerShell：

```powershell
$env:GIT_LFS_SKIP_SMUDGE = "1"
git clone --depth 1 --branch 1.6.0 -c core.hooksPath=NUL https://github.com/Anduin2017/HowToCook.git E:/Mycode/HowToCook
git -C E:/Mycode/HowToCook rev-parse HEAD
```

Bash：

```bash
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 --branch 1.6.0 -c core.hooksPath=/dev/null \
  https://github.com/Anduin2017/HowToCook.git /Mycode/HowToCook
git -C /Mycode/HowToCook rev-parse HEAD
```

输出必须是 `a0b14bd388ee996943173679c3a7c9db7da04a6c`。索引只读取 `dishes/**/*.md`，不需要图片 LFS 对象。

## 2. 启动 Ollama 与 Qdrant

```powershell
ollama pull bge-m3:567m
docker compose -f compose.retrieval-dev.yml up -d qdrant
```

确认服务：

```powershell
Invoke-RestMethod http://localhost:11434/api/tags
Invoke-RestMethod http://localhost:16333/collections
```

collection 默认名为 `diet_recipe_chunks_bge_m3_1024`。已有 collection 的向量维度不是 1024 时，构建会明确失败；不要复用不同 embedding 模型的 collection。

## 3. 构建双索引

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag --diet.recipe-rag.enabled=true --recipe-rag-index=E:/Mycode/HowToCook"
```

命令依次完成：固定语料解析、稳定父子 ID、全部 embedding、Lucene 暂存版本、Qdrant 暂存版本、双索引激活。任一构建失败时保留上一活动版本，并清理本次未激活版本。看到 `Recipe index activated` 后本轮构建已完成；当前 Spring 上下文仍会继续运行，可按 `Ctrl+C` 结束后再正式启动应用。

索引版本由语料哈希、解析器版本、embedding 模型和维度共同决定。Qdrant point ID 同时包含索引版本与 chunk ID，失败的新版本不会覆盖旧版本 payload。

## 4. 启动应用

先按主 README 配置 MySQL 和主聊天模型，再运行：

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook"
```

关键配置见 `src/main/resources/application-recipe-rag.yml`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `diet.recipe-rag.enabled` | `false` | 公共菜谱 RAG 总开关 |
| `diet.recipe-rag.retrieval.mode` | `parallel_hybrid` | `sparse_only`、`dense_only`、`serial_hybrid` 或 `parallel_hybrid` |
| `diet.recipe-rag.retrieval.branch-timeout` | `2s` | BM25/dense 独立超时 |
| `diet.recipe-rag.query-rewrite.enabled` | `false` | `false` 为 identity；`true` 使用 `DietLightChatModel` 做单轮改写，失败回退原查询 |
| `diet.recipe-rag.rerank.enabled` | `false` | 重排扩展点；默认 identity |

普通推荐在 RAG 关闭、Ollama/Qdrant/Lucene 不可用时仍只使用 MySQL。

启用真实查询改写的学习命令：

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook --diet.recipe-rag.query-rewrite.enabled=true --diet.recipe-rag.query-rewrite.timeout=10s"
```

改写只影响检索文本，结构化食材/工具/时间约束保持原值，回答生成仍收到用户原问题。生产默认超时为 2 秒；10 秒仅用于首次本地观察。

## 5. 请求示例

直接菜谱查询不依赖推荐：

```json
{
  "sessionId": "session-id",
  "message": "宫保鸡丁怎么做",
  "sourceMode": "PUBLIC",
  "context": {}
}
```

推荐后的个人做法查询：

```json
{
  "sessionId": "session-id",
  "message": "这道菜怎么做",
  "sourceMode": "PERSONAL",
  "context": {
    "recipeQuery": {
      "mealId": 41,
      "excludedIngredients": ["花生"]
    }
  }
}
```

`recipeQuery` 只允许 `mealId`、`query`、`requiredIngredients`、`excludedIngredients`、`requiredTools`、`maxMinutes`。存在个人 `meal_knowledge` 时优先回答；没有时用餐食名称查公共 RAG。未知字段被拒绝。

## 6. 视频搜索契约

默认 provider 为 disabled。仅用于本地契约演示的 fake provider：

```text
--diet.video-mcp.enabled=true --diet.video-mcp.provider=fake
```

结构化输入：

```json
{"videoSearch":{"query":"宫保鸡丁","platform":"BILIBILI","limit":3}}
```

业务契约名为 `search_cooking_videos`，平台参数允许 `BILIBILI`、`DOUYIN`、`ANY`，limit 为 1～10。fake URL 使用 `example.invalid`。启用 `video-mcp` profile 后，真实 provider 调用 Bilibili MCP 的 `search_videos`；当前抖音明确返回未配置，具体启动与验证见阶段 005 运行手册。

## 7. 评测

始终可运行的离线评测：

```powershell
mvn "-Dtest=RecipeRetrievalMetricsTest,RecipeAnswerMetricsTest,RecipeEvaluationDatasetTest,RecipeParallelLatencyTest" test
```

菜谱评测默认加载 v2：90 条查询、显式检索/回答期望、多 Gold、near-OOD，并按 Gold 菜谱实体隔离 dev/test。复现历史 v1 时增加：

```powershell
"-Ddiet.recipe-evaluation.version=v1"
```

验证 qrels 与固定语料 ID：

```powershell
mvn "-Dtest=RecipeEvaluationDatasetTest" "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" test
```

真实 Ollama/Qdrant 七组实验：

```powershell
mvn "-Dtest=RecipeRetrievalEvaluationTest" "-Ddiet.recipe-rag.live=true" `
  "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" test
```

报告写入 `target/evaluation/recipe/report.json`。报告记录数据集版本、fingerprint、有效语料文档数、正向查询数、唯一 Gold、多 Gold 数以及单条 test 的最大百分点影响；实验结果分别列出 Recall@1/5/10、MRR@10、nDCG@10、Parent Recall、重复率、空结果率、near-OOD 误召回率、p50/p95、降级次数和切片指标。`REFUSE_OUT_OF_CORPUS` 与 `REFUSE_UNSUPPORTED` 分开解释，后者仍参与检索指标。历史七组报告的 rewrite/rerank 标签仍按 identity 口径解释，不能改写成模型增益。

真实 query rewrite 只在 dev split 做显式对照：

```powershell
mvn "-Dtest=RecipeQueryRewriteEvaluationTest" `
  "-Ddiet.recipe-rewrite.live=true" `
  "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" `
  "-Ddiet.recipe-rewrite-evaluation.limit=12" test
```

报告写入 `target/evaluation/recipe/rewrite-dev-report.json`，包含每条原查询、实际查询、两套 Top10、Gold 和改写耗时，以及两组聚合指标。该入口不读取锁定 test 做调参，也不修改 qrels。

### Ragas 回答质量基线（显式启用）

先准备上述固定 commit 的 HowToCook、Ollama `bge-m3:567m`、Qdrant 和主聊天模型的 `agentscope.deepseek.api-key`。还需要 Java 21、Maven、`uv`，以及可访问的 OpenAI-compatible Judge 和 embedding 服务。以下变量在当前 PowerShell 会话中设置；请在本机安全地填写真实密钥，不要将其写进仓库。脚本不会自动加载 `.env`，也不会下载 Judge 模型。

```powershell
$env:RAGAS_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:RAGAS_LLM_API_KEY = "<set locally>"
$env:RAGAS_LLM_MODEL = "deepseek-chat"
$env:RAGAS_EMBEDDING_BASE_URL = "http://localhost:11434/v1"
$env:RAGAS_EMBEDDING_API_KEY = "ollama"
$env:RAGAS_EMBEDDING_MODEL = "bge-m3:567m"
./scripts/evaluate-recipe-ragas.ps1 -CorpusPath E:/Mycode/HowToCook
```

脚本先运行真实检索 Maven 评测并验证新生成的 `target/evaluation/recipe/report.json`，再单独运行回答导出 Maven 测试，得到冻结的 `ragas-input.jsonl`；随后执行 `uv sync --frozen`，调用 Python CLI 评分。正常输出为 `target/evaluation/recipe/ragas-report.json` 与 `ragas-cases.csv`。任一阶段退出非零、输入或报告缺失、报告没有有效值时脚本失败，后续阶段不会启动。`OutputDir` 可调整 Ragas 输出目录；`InputPath` 可在复用已有输入时指定。

已有完整的 Java 报告和冻结 JSONL 时，可以跳过生成阶段，避免再次支付回答生成费用：

```powershell
./scripts/evaluate-recipe-ragas.ps1 -CorpusPath E:/Mycode/HowToCook -SkipJavaExport `
  -InputPath target/evaluation/recipe/ragas-input.jsonl `
  -OutputDir target/evaluation/recipe
```

复用时 CLI 会校验 JSONL 与 Java 报告的数据集版本和 hash；输入文件内容的 SHA-256 也写入 Ragas 报告。`-SkipJavaExport` 只省去生成费用，Judge 评分仍会发起请求。

三个语义指标 `faithfulness`、`answer_relevancy`、`context_utilization` 只统计 `ANSWER` 且成功生成的样本。报告的每项 `mean` 应与 `valid_count`、`failure_count` 一起读；`partial` 表示已有有效值但部分指标失败，全部无有效值则失败并返回非零。`same_model_judge=true` 表示生成模型与 Judge 同名，该分数是同模型自动评测，不能当作独立人工评审。OOD 和不支持回答的拒答样本另由 Java 报告统计，不混入语义均值。锁定 test 的结果不得用于修改 qrels；评测集 v1 和既有 v2 基线保持原样。

## 8. 故障定位

- `Recipe sparse index has not been built`：先运行索引命令，检查 Lucene 目录中的 `active.json`。
- `qdrant_active_version_missing`：确认 Lucene 活动版本存在，并以相同配置重启应用；启动配置会用 Lucene 活动版本恢复 Qdrant 查询过滤器。
- `embedding_dimension_mismatch`：模型输出、配置维度和 Qdrant collection 不一致；新建匹配维度的 collection 后重建。
- `recipe_retrieval_all_branches_failed`：BM25 与 dense 都失败，系统拒答，不调用生成模型编造答案。
- 单分支超时或失败：响应 diagnostics 包含降级原因，另一分支仍可返回证据。
- 视频请求返回“暂时不可用”：这是默认 disabled provider 的预期行为，不影响文字菜谱和普通推荐。
