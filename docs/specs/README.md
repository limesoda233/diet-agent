# Diet Agent 规格驱动开发

日期：2026-09-30。

## 已确认决策

- 采用 Spec-Driven Development：规格 → 验收标准 → 实现任务 → 代码与测试 → 验收记录。
- 阶段 004 的当前方向覆盖阶段 002/003 的产品方向：餐食推荐只走 MySQL，RAG 只检索公共菜谱，MCP 调整为后续的视频教学搜索能力。
- 保留 Java、Spring Boot、AgentScope、MyBatis、MySQL；菜谱 RAG 使用 Lucene BM25、本地 Qdrant 和可配置的 AgentScope embedding 适配器，开发环境优先本地 Ollama。
- 评测从仓库的 45 道种子餐食建立独立快照，不依赖或改写当前用户数据库。
- 用户个人餐食做法保存在 MySQL `meal_knowledge`，不进入公共向量库。视频 MCP 本轮只做 disabled/fake provider 契约。

## 阶段交付

| 阶段 | 交付 | 验收依据 |
| --- | --- | --- |
| 001 | 固定语料、可复核标注、真实 MySQL 检索基线、分切片报告 | 可复现、指标正确、限制与标注状态明确 |
| 003（历史） | 已退役的本地食谱/替换 MCP 实验 | 代码已由阶段 004 删除，文档只保留历史证据 |
| 004（当前） | 移除餐食 RAG；HowToCook 菜谱 RAG、并行混合检索、评测与视频 MCP 边界 | [规格](004-recipe-rag/spec.md) · [运行手册](004-recipe-rag/runbook.md) · [验收](004-recipe-rag/acceptance.md) |

第一阶段已完成工程验收，见 [001 验收记录](001-meal-retrieval-evaluation/acceptance.md)。阶段 002 的餐食 RAG 文档与代码已删除，阶段 003 只保留带退役标记的历史文档。阶段 004 是当前唯一现役设计；实现、运行与未验证项以阶段 004 文档为准。

## 开发约束

- 用验收条目 ID 关联任务和验证证据；规格变化记录原因与影响。
- 相关性标注独立于检索结果。助手草拟的标签在用户复核前不能称为人工金标。
- 开发集调参；参数冻结后运行测试集。相同需求的近义改写不能跨集合。
- 演示数据、假设配方、数据库标签与验证过的外部事实明确区分。
- 工作区已有 README、application.yml、意图评测和 tmp 的未提交变更，保留，不计为本次交付。
