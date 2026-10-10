# 菜谱查询改写规格

日期：2026-10-10

## 目标

在公共菜谱 RAG 检索前增加一次轻量、可关闭的查询改写，改善错别字、口语和食材别名造成的召回问题，并让开发者能独立比较改写前后的检索结果。

## 行为

- 默认仍为 `identity`，只有 `diet.recipe-rag.query-rewrite.enabled=true` 时调用 `DietLightChatModel`。
- 每次只做一次无记忆、无工具调用的改写，模型只返回 `{"query":"..."}`。
- 改写只能调整检索文本。`requiredIngredients`、`excludedIngredients`、`requiredTools`、`maxMinutes` 完全以服务端解析出的原始值为准。
- 检索和重排使用改写文本；回答生成仍收到用户原问题，避免改写丢失回答意图。
- 同步异常、异步异常、超时、空响应、非法 JSON、空 query 均回退原查询，不中断菜谱问答。
- Trace 记录原查询、实际查询、状态、模型名、耗时和错误类型，不记录 API Key，也不因回退把整条请求标为失败。
- 视频搜索继续使用请求线程准备出的原始菜名，与公共菜谱 RAG 并行，不等待查询改写。

## 边界

- 不修改餐食推荐 MySQL 链路。
- 不修改个人 `meal_knowledge` 链路。
- 不启用重排，不把历史七组 identity 实验重命名为“已使用查询改写”。
- 锁定测试集 qrels 不因实验结果修改。

## 验收

- 单元测试覆盖约束保持、同步/异步/空结果回退、JSON 解析和问答链路的原始/改写查询分工。
- 提供只跑开发集的改写前后对照入口，报告包含每条 original/effective query、候选结果、指标和改写耗时。
- 提供从 Ollama、Qdrant、索引、应用、MCP 到 Ragas 的本地学习手册。
