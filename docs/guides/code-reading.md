# 沿着一次请求读代码

不要从文件树逐个读。先在前端发“宫保鸡丁怎么做”，拿到 traceId，然后依次走下面的方法。每层只记：输入是什么、为什么进入这个分支、输出交给谁。

## 1. HTTP、会话与路由

1. `DietChatController.dietChat`：透传用户 ID 和请求。
2. `DietOrchestratorService.dietChat`：参数验证、加载会话、同会话锁、Trace 生命周期。
3. `handleTurn`：保存用户消息；纯视频/菜谱规则优先；未命中才调用意图模型。
4. `IntentAgentService.recognize`：可用时调用 Jev，失败或置信度不足回退 DeepSeek，最后关键词兜底。配置在 `application.yml` 的 `diet.jev`。

## 2. 多轮菜谱与外层并行

1. `handleRecipeQuery`：通过 `RecipeConversationResolver` 补齐“怎么做”“第二道”的目标；对象不明确时先澄清。
2. `SessionStateService`：把最新展示菜品和选中菜品写入会话 JSON；累计推荐排除列表是另一个字段。
3. `executeRecipeQuery`：在请求线程准备同一完整查询。
4. `RecipeVideoCoordinator`：RAG 在 boundedElastic 执行，视频独立启动，`Mono.zip` 等待两支终态，再按成功/拒答/失败组合响应。

## 3. 公共菜谱 RAG

1. `RecipeQueryService.answer`：个人已保存做法优先；公共路径改写 → 检索 → 重排 → 回答。
2. `AgentScopeRecipeQueryRewriter`：可选单轮模型调用，仅改变检索文本，硬约束保留；超时与解析失败回退。
3. `HybridRecipeRetriever`：BM25 与 Dense 并行；单支失败可降级，双支失败终止。
4. `RecipeRrf`：同一父菜谱每支只取最好名次，再累加倒数排名分。
5. `RecipeParentResolver`：恢复父文档，核对明确食材/工具条件。
6. `RecipeEvidenceAssembler`：裁剪证据并编号；实际裁剪后的块同时供 Ragas 导出。
7. `AgentScopeRecipeAnswerGenerator`：模型回答、引用校验及确定性回退。

离线构建另一条链：`RecipeIndexCommand` → `RecipeCorpusImporter` / `HowToCookMarkdownParser` → `RecipeIndexCoordinator` → Lucene/Qdrant。读它时关注“索引版本如何生成、失败如何补偿”，无需同时读聊天路由。

## 4. 视频 MCP

`CookingVideoSearchService` 负责参数和业务预算；provider/gateway 负责适配第三方输出；`AgentScopeBilibiliMcpClient` 负责 stdio、共享初始化、工具发现、调用和资源释放。请求超时与共享握手生命周期分离，避免一次请求取消把初始化中断。

## 5. 评测

Java 的 `src/test/java/com/diet/evaluation/recipe` 负责固定数据集和检索指标，也导出真实回答与证据。Python 的 `evals/recipe-ragas/src/recipe_ragas` 负责 Judge；`scripts/evaluate-recipe-ragas.ps1` 串起两个环境。命令、凭据和报告解读统一见 [学习手册](recipe-rag-mcp-evaluation.md)。

在 IDEA 对方法按 Ctrl+B 跳转，Alt+F7 看调用方，Ctrl+Alt+左箭头返回。第一遍略过构造器、Bean 和 getter；第二遍再读配置与异常分支。建议顺序为：单轮明确菜名 → 推荐后的多轮追问 → 检索降级 → MCP 超时 → dev 对照评测。
