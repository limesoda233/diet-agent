# 项目文档导航

先运行应用，再跟随一条 Trace 读代码，最后进入评测与设计细节。

| 目的 | 入口 |
| --- | --- |
| 启动 RAG、MCP、改写与 Ragas | [学习与运行手册](guides/recipe-rag-mcp-evaluation.md) |
| 不知道从哪个 Java 文件开始 | [源码阅读路线](guides/code-reading.md) |
| 菜谱 RAG/MCP 面试复盘 | [面试逐字稿](interview/recipe-rag-mcp.md) |
| Jev 意图识别与评测面试 | [Jev 资料](interview/jev-intent-evaluation.md) |
| 意图评测配置与基线 | [意图评测说明](evaluation/intent-evaluation.md) |
| 需求、约束与历史验收 | [SDD 索引](specs/README.md) |
| 原餐食推荐 Draw.io 源文件 | [架构图](architecture/diet-agent.drawio) |

`specs` 中保留需求、标注依据和验收，历史指标使用当时的口径。学习指南是操作入口；面试稿面向讲解。旧食材替换 MCP 已退役，已完成的任务清单和逐步代码实施计划已经清理。

项目运行产生的数据放在 `target/evaluation`；本地 Lucene 索引放在 `data`；临时实验放在 `tmp`。这些目录已忽略，固定 query/qrels 位于 `src/test/resources/evaluation`，不能随运行结果修改。`uv.lock` 是可复现依赖文件，保留在 Git 中。

这次整理核对了目录、代码职责、配置、文档链接和测试；没有修改外部服务、数据库数据或 Agent 记忆。架构图主要是原餐食推荐流程，完整菜谱/视频链路请看指南与源码。
