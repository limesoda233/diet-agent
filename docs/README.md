# 项目文档导航

先运行应用，再跟随一条 Trace 读代码，最后进入评测与设计细节。

| 目的 | 入口 |
| --- | --- |
| 启动 RAG、MCP、改写与 Ragas | [学习与运行手册](guides/recipe-rag-mcp-evaluation.md) |
| 菜谱重排、相关性筛选与阈值评测 | [相关性筛选指南](guides/recipe-relevance.md)，默认关闭，真实模型效果待验证 |
| React 前端、IDEA 单服务与热更新 | [前端运行指南](guides/react-frontend.md) |
| 微信小程序体验环境与朋友邀请 | [云开发部署清单](guides/wechat-cloudbase.md) · [小程序部署指南](guides/wechat-mini-program.md) |
| 不知道从哪个 Java 文件开始 | [源码阅读路线](guides/code-reading.md) |
| 一句话整理个人餐食与确认保存 | [个人餐食录入指南](guides/meal-intake.md) |
| 跨会话偏好、过敏确认与记忆管理 | [长期记忆指南](guides/user-memory.md)，先迁移 MySQL 再显式启用 |
| 跨日选餐、Planner/Menu/Review 与有限修订 | [多 Agent 规划指南](guides/meal-planning.md)，先执行 006 迁移，默认关闭 |
| 菜谱 RAG/MCP 面试复盘 | [面试逐字稿](interview/recipe-rag-mcp.md) |
| Jev 意图识别与评测面试 | [当前逐字稿](interview/intent-comparison.md) · [历史接入资料](interview/jev-intent-evaluation.md) |
| 意图评测配置与基线 | [意图评测说明](evaluation/intent-evaluation.md) |
| 原 IntentAgent 与 Jev 真实对照 | [对照运行与报告指南](guides/intent-comparison.md) |
| 2026-10-11 对照结果与问题复盘 | [实验记录](evaluation/intent-comparison-2026-10-11.md) |
| 需求、约束与历史验收 | [SDD 索引](specs/README.md) |
| 原餐食推荐 Draw.io 源文件 | [架构图](architecture/diet-agent.drawio) |

`specs` 中保留需求、标注依据和验收，历史指标使用当时的口径。学习指南是操作入口；面试稿面向讲解。旧食材替换 MCP 已退役，已完成的任务清单和逐步代码实施计划已经清理。

项目运行产生的数据放在 `target/evaluation`；本地 Lucene 索引放在 `data`；临时实验放在 `tmp`。这些目录已忽略，固定 query/qrels 位于 `src/test/resources/evaluation`，不能随运行结果修改。`uv.lock` 是可复现依赖文件，保留在 Git 中。

这次整理核对了目录、代码职责、配置、文档链接和测试；没有修改外部服务、数据库数据或 Agent 记忆。架构图主要是原餐食推荐流程，完整菜谱/视频链路请看指南与源码。
