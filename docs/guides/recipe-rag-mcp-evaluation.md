# 从零运行并学习 Diet Agent 的 RAG、查询改写、MCP 与评测

这份手册按“先看得见，再理解，最后做实验”的顺序组织。每一步都有明确观察点，适合本地练习和面试前复盘。

## 1. 先理解四个外部组件

| 组件 | 本项目中的作用 | 不负责什么 |
| --- | --- | --- |
| Ollama | 在本机运行 `bge-m3:567m`，把菜谱块和查询变成 1024 维向量 | 不存菜谱，不生成最终回答 |
| Qdrant | 保存子块向量并做 cosine 相似度搜索 | 不处理 MySQL 餐食推荐 |
| Lucene | 保存同一批子块，执行 BM25 关键词检索 | 不调用大模型 |
| DeepSeek / AgentScope Model | 可选的 query 改写和最终证据回答 | 不替代 Lucene、Qdrant 或业务约束 |

完整公共菜谱链路是：

```text
用户问题
  → 菜谱意图路由
  → 可选 query 改写
  → BM25 与 Dense 并行召回
  → RRF 融合、父菜谱恢复、约束过滤
  → 基于证据生成带引用回答
```

视频 MCP 是另一条并行分支。它通过 stdio 启动固定版本的 Bilibili MCP Server，调用其 `search_videos` 工具并返回可点击卡片。视频元数据不会成为文字菜谱的事实证据。

## 2. 第一次学习：先跑不依赖外部服务的测试

在 PowerShell 中进入项目：

```powershell
Set-Location E:/Mycode/diet-agent
mvn "-Dtest=RecipeQueryRewriteTest,AgentScopeRecipeQueryRewriterTest,RecipeEvaluationDatasetTest,RecipeRetrievalMetricsTest,RecipeAnswerMetricsTest,RecipeParallelLatencyTest" test
```

建议边看测试边读以下入口：

- `RecipeQueryRewriter`：失败回退和硬约束保护。
- `AgentScopeRecipeQueryRewriter`：一次无记忆、无工具调用的 LLM 改写。
- `HybridRecipeRetriever`：BM25 / Dense 并行与降级。
- `RecipeRrf`：两个有序列表怎样融合。
- `RecipeEvaluationDataset`：query、qrels、split 和 fingerprint 怎样加载。

这里最关键的区别是：query 改写只改 `text`。食材必须包含、食材排除、工具和最长时间完全保留服务端原值。检索使用改写文本，回答生成仍收到用户原问题。

## 3. 启动检索依赖

### 3.1 固定 HowToCook 版本

```powershell
git -C E:/Mycode/HowToCook rev-parse HEAD
```

输出应为：

```text
a0b14bd388ee996943173679c3a7c9db7da04a6c
```

### 3.2 Ollama

如果 Ollama 没有作为系统服务运行，在一个单独终端执行：

```powershell
ollama serve
```

另一个终端执行：

```powershell
ollama pull bge-m3:567m
Invoke-RestMethod http://localhost:11434/api/tags
```

### 3.3 Qdrant

```powershell
docker compose -f compose.retrieval-dev.yml up -d qdrant
Invoke-RestMethod http://localhost:16333/collections
```

## 4. 构建公共菜谱索引

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook --recipe-rag-index=E:/Mycode/HowToCook"
```

观察日志中的语料数、索引版本、embedding 模型、Lucene 激活和 Qdrant 激活。看到 `Recipe index activated` 后索引已经完成；当前命令仍保持 Spring 应用运行，可按 `Ctrl+C` 结束，再正式启动应用。

索引只需在语料、解析器、embedding 模型或维度变化后重建。普通重启不需要重复构建。

## 5. 启动应用并观察 query 改写

先在当前 PowerShell 或 IDEA Run Configuration 中安全配置 `DEEPSEEK_API_KEY`。项目不会自动读取 `.env`，不要把 Key 写进仓库或命令历史。

### 5.1 先跑 identity 基线

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook"
```

此时 `diet.recipe-rag.query-rewrite.enabled=false`，检索直接使用原查询。

### 5.2 再启用真实改写

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook --diet.recipe-rag.query-rewrite.enabled=true --diet.recipe-rag.query-rewrite.timeout=10s"
```

学习环境把改写超时临时放宽到 10 秒，便于第一次观察云模型；确认稳定后再评估生产默认的 2 秒预算。模型异常、超时、空响应或非法 JSON 都会回退原查询。

先按 [React 前端运行指南](react-frontend.md) 构建页面，然后在 IDEA 中也可以直接启动 `DietApplication`：

- Active profiles：`recipe-rag`
- Program arguments：使用上面 `spring-boot.run.arguments` 中 `--` 开头的参数
- Environment variables：配置 `DEEPSEEK_API_KEY`，以及需要时的 `DEEPSEEK_BASE_URL`、`DEEPSEEK_LIGHT_MODEL`

### 5.3 用 HTTP 请求查看结果和 Trace

```powershell
$headers = @{ "X-User-Id" = "1" }
$sessionId = (Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/diet/sessions `
  -Headers $headers).sessionId

$body = @{
  sessionId = $sessionId
  message = "马铃薯丝怎么炒得酸辣"
  sourceMode = "PUBLIC"
  context = @{
    recipeQuery = @{
      query = "马铃薯丝怎么炒得酸辣"
    }
  }
} | ConvertTo-Json -Depth 6

$response = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/diet/chat `
  -Headers $headers -ContentType "application/json" -Body $body
$response

$trace = Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/v1/diet/debug/traces/$($response.traceId)" `
  -Headers $headers
($trace.traceJson | ConvertFrom-Json).events |
  Where-Object eventType -eq "RECIPE_QUERY_REWRITE" |
  Format-List
```

这里显式传 `context.recipeQuery.query` 是为了绕过意图识别，单独观察 RAG 和改写。做端到端验收时再把 `context` 改为空对象，测试“宫保鸡丁咋做啊”等自然语言能否先进入菜谱链路。

Trace 中应看到：

- `originalQuery`：用户原话。
- `effectiveQuery`：真正交给 BM25 和 Dense 的文本。
- `status`：`REWRITTEN`、`UNCHANGED` 或 `FALLBACK`。
- `model`、`latencyMs`；失败只记录异常类型，不暴露 Key。

## 6. 启动视频 MCP

先确认 Node 与 npx：

```powershell
node --version
npx --version
where.exe npx
```

同时启动 RAG 与视频：

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag,video-mcp --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook --diet.recipe-rag.query-rewrite.enabled=true --diet.recipe-rag.query-rewrite.timeout=10s"
```

等日志出现 `Bilibili MCP ready; search tool verified` 后再做首次视频验收。询问一道菜时应同时出现带引用的文字回答和视频卡片。停止 MCP 或断开 npm/Bilibili 网络后，文字 RAG 仍应返回；让 RAG 无证据但 MCP 可用时，只返回视频和“暂无可信文字菜谱”的说明。

离线契约测试：

```powershell
mvn "-Dtest=AgentScopeBilibiliMcpClientTest,BilibiliMcpWarmupTest,BilibiliMcpCookingVideoSearchProviderTest,CookingVideoSearchServiceTest" test
```

显式访问真实公开视频搜索：

```powershell
mvn "-Dtest=BilibiliMcpLiveTest" "-Ddiet.video-mcp.live=true" test
```

## 7. 跑三层评测

### 7.1 检索指标：回答“有没有把正确菜谱排到前面”

```powershell
mvn "-Dtest=RecipeRetrievalEvaluationTest" `
  "-Ddiet.recipe-rag.live=true" `
  "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" test
```

输出：`target/evaluation/recipe/report.json`。关注 Recall@5/10、MRR@10、nDCG@10、near-OOD false-positive rate、P50/P95 和 degradedQueries。

这条命令复现历史七组标签，其中 rewrite/rerank 仍是 identity 口径，不应用它证明真实改写收益。

### 7.2 Query Rewrite 对照：回答“改写到底有没有帮助”

只在 dev split 上比较原查询与真实改写，避免用锁定 test 调参：

```powershell
mvn "-Dtest=RecipeQueryRewriteEvaluationTest" `
  "-Ddiet.recipe-rewrite.live=true" `
  "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" `
  "-Ddiet.recipe-rewrite-evaluation.limit=12" test
```

输出：`target/evaluation/recipe/rewrite-dev-report.json`。报告保留每条 original/effective query、两套 Top10、Gold、改写耗时，以及 baseline/rewrite 的聚合指标。第一次先跑 12 条跨 slice 样本；确认费用和结果后，可以把 limit 调大到 dev 集规模。

阅读报告时不要只看均值：

- 改写后 Gold 从 Top10 外进入 Top5，是正向 case。
- 本来正确的具体菜名被改成更宽泛词，是 query drift。
- 逐条 `rewriteStatus` 区分 `REWRITTEN`、`UNCHANGED` 和 `FALLBACK`，`errorType` 给出回退原因。聚合 `fallbackCount` 表示失败次数；全部回退时报告状态为 `failed`，命令返回失败。
- 只有在 dev 上确定策略后，才允许一次性运行锁定 test 做最终验证；不能根据 test 结果修改 qrels。

### 7.3 Ragas：回答“有证据以后，最终回答是否忠实、相关、用到了上下文”

先设置 Judge 和 embedding 的 OpenAI-compatible 配置：

```powershell
$env:RAGAS_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:RAGAS_LLM_API_KEY = "<仅在本机填写>"
$env:RAGAS_LLM_MODEL = "deepseek-chat"
$env:RAGAS_EMBEDDING_BASE_URL = "http://localhost:11434/v1"
$env:RAGAS_EMBEDDING_API_KEY = "ollama"
$env:RAGAS_EMBEDDING_MODEL = "bge-m3:567m"

./scripts/evaluate-recipe-ragas.ps1 -CorpusPath E:/Mycode/HowToCook
```

输出：

- `target/evaluation/recipe/ragas-input.jsonl`：Java 生产链路导出的冻结样本。
- `ragas-report.json`：Faithfulness、Answer Relevancy、Context Utilization 的均值、有效数和失败数。
- `ragas-cases.csv`：逐条得分，适合找 bad case。

Ragas 是 LLM Judge，不是绝对真值。生成模型与 Judge 同名时要明确说明同模型偏差；每个均值必须连同 `valid_count` 和 `failure_count` 阅读。OOD 和不支持事实的拒答由 Java 确定性规则单独统计，不混入语义均值。

## 8. 推荐的四次练习

1. 只读链路：运行离线测试，画出 query 从路由到回答的调用图。
2. 检索实验：关闭改写，分别跑 BM25、Dense、Parallel Hybrid，手工解释一个好 case 和一个坏 case。
3. 改写实验：启用真实 query rewrite，对 alias、错别字、长口语和冲突约束逐条看 Trace，再跑 dev 对照报告。
4. 完整系统：启用视频 MCP，模拟 RAG 成功/MCP 失败、RAG 无证据/MCP 成功、两支都失败，说明降级行为；最后跑 Ragas 并抽查逐 case 结果。

面试时最有价值的不是背组件名，而是能解释三个问题：为什么推荐仍用 MySQL；为什么混合检索并不保证每个排序指标都更好；为什么 query 改写和 Ragas 必须有独立评测，不能凭“用了大模型”判断有效。

## 9. 常见问题

多轮验收时复用同一 `sessionId`：先推荐菜品，再问“怎么做”。只有一道菜时会自动补齐菜名，多道菜时先追问；“第二道怎么做”按最近展示顺序解析。追问后可回复菜名或序号，之后“需要什么食材”沿用选中菜品。Trace 的 `RECIPE_REFERENCE_RESOLVED` 可查看实际问题和解析来源。会话 JSON 保存这些信息，旧会话更新后需重新推荐一次。

- `Recipe sparse index has not been built`：先执行索引命令，确认 Lucene 目录存在活动版本。
- Qdrant 维度不一致：换一个与 `bge-m3:567m` 1024 维匹配的 collection 并重建。
- 改写一直 `FALLBACK`：检查轻量模型 Key、base URL、模型名、超时和 Trace 中的 `errorType`。
- 自然语言没走菜谱链路：先用结构化 `context.recipeQuery.query` 隔离测试 RAG，再修意图路由；query rewrite 位于路由之后，不能挽救一个已经被路由成 OTHER 的请求。
- 视频第一次超时：等待后台预热日志；请求的 3 秒预算不会等待完整的 npx 冷启动。
- Ragas 没有分数：检查环境变量、输入 JSONL、Java report，以及报告里的 failure reason；不要把缺失值按 0 分解释。
