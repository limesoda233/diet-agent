# 查询改写验收记录

日期：2026-10-10。

## 工程验证

- `mvn -Dtest=AgentScopeRecipeQueryRewriterTest,RecipeQueryRewriteTest,RecipeRagConfigurationTest,RecipeQueryServiceTest test`：20 项，0 失败。
- `mvn test`：214 项，0 失败，0 错误，10 跳过。
- `mvn -DskipTests package`：成功。

定向用例验证单轮 JSON 改写、非法/重复字段回退、超时回退、硬约束保持、跨线程 Trace、Trace 失败隔离、配置开关，以及原问题用于回答、改写文本用于检索。

代码审查发现的回退统计缺失已修复：开发集报告包含逐 case 状态、错误类型和 fallbackCount；全部回退时报告 failed 且命令失败。锁定 test qrels 未修改。

## 效果验收边界

真实云模型查询改写对照未运行，因此不报告改写的质量收益。真实 Ragas Judge 也未运行。默认查询改写仍为 identity；用户显式启用后才调用轻量模型。学习命令与观察步骤见 [学习手册](../../guides/recipe-rag-mcp-evaluation.md)。

工作区既有 MySQL、Jev、视频 MCP、检索调试及其它未提交变更保留；本次没有创建提交或推送。
