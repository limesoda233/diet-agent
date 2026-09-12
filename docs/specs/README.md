# Diet Agent 规格驱动开发

日期：2026-09-10。

## 已确认决策

- 采用 Spec-Driven Development：规格 → 验收标准 → 实现任务 → 代码与测试 → 验收记录。
- 顺序：餐食检索评测 → 混合 RAG → 食谱与食材替换 MCP。
- 保留 Java、Spring Boot、AgentScope、MyBatis、MySQL；增加本地 Qdrant 和可配置的云 embedding API。用户尚未选择 embedding 服务商。
- 评测从仓库的 45 道种子餐食建立独立快照，不依赖或改写当前用户数据库。
- MCP 服务在家做饭场景：按已有食材找食谱、查询做法、寻找替代食材。营养 MCP 与 agentic RAG 不在本轮范围。

## 阶段交付

| 阶段 | 交付 | 验收依据 |
| --- | --- | --- |
| 001 | 固定语料、可复核标注、真实 MySQL 检索基线、分切片报告 | 可复现、指标正确、限制与标注状态明确 |
| 002 | 原始查询与约束、结构化与语义召回、融合重排、索引同步、回退、Trace | 同版本语料对比；权限和明确约束不能被放宽 |
| 003 | 食谱/替换工具、MCP Server、AgentScope 客户端、调用记录 | 协议联调、来源可见、禁用食材校验、失败回退 |

第一阶段已完成工程验收，见 [001 验收记录](001-meal-retrieval-evaluation/acceptance.md)。第二阶段已实现混合检索工程版本，见 [002 验收记录](002-hybrid-meal-rag/acceptance.md) 与 [运行说明](002-hybrid-meal-rag/runbook.md)；真实云 embedding 效果仍待 RAG-10，最终 Qdrant 复测受 Docker 启动故障影响。第三阶段已实现独立食谱/替换 MCP、本地示例数据和固定任务评测，见 [003 规格](003-recipe-substitution-mcp/spec.md)、[运行说明](003-recipe-substitution-mcp/runbook.md) 和 [验收记录](003-recipe-substitution-mcp/acceptance.md)。

## 开发约束

- 用验收条目 ID 关联任务和验证证据；规格变化记录原因与影响。
- 相关性标注独立于检索结果。助手草拟的标签在用户复核前不能称为人工金标。
- 开发集调参；参数冻结后运行测试集。相同需求的近义改写不能跨集合。
- 演示数据、假设配方、数据库标签与验证过的外部事实明确区分。
- 工作区已有 README、application.yml、意图评测和 tmp 的未提交变更，保留，不计为本次交付。
