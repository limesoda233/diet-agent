# Diet Agent 规格驱动开发

更新日期：2026-11-12。

## 已确认决策

- 采用 Spec-Driven Development：规格 → 验收标准 → 实现任务 → 代码与测试 → 验收记录。
- 阶段 004 的当前方向覆盖阶段 002/003 的产品方向：餐食推荐只走 MySQL，RAG 只检索公共菜谱，MCP 调整为后续的视频教学搜索能力。
- 保留 Java、Spring Boot、AgentScope、MyBatis、MySQL；菜谱 RAG 使用 Lucene BM25、本地 Qdrant 和可配置的 AgentScope embedding 适配器，开发环境优先本地 Ollama。
- 评测从仓库的 45 道种子餐食建立独立快照，不依赖或改写当前用户数据库。
- 用户个人餐食做法保存在 MySQL `meal_knowledge`，不进入公共向量库。视频搜索通过阶段 005 的固定版本 Bilibili MCP 显式启用；查询改写通过阶段 007 显式启用，二者默认都不会改变餐食推荐链路。

## 阶段交付

| 阶段 | 交付 | 验收依据 |
| --- | --- | --- |
| 001 | 固定语料、可复核标注、真实 MySQL 检索基线、分切片报告 | 可复现、指标正确、限制与标注状态明确 |
| 003（历史） | 已退役的本地食谱/替换 MCP 实验 | [历史定位](003-recipe-substitution-mcp/README.md)，旧实施文档可从 Git 恢复 |
| 004（当前） | 移除餐食 RAG；HowToCook 菜谱 RAG、并行混合检索、评测与视频 MCP 边界 | [规格](004-recipe-rag/spec.md) · [运行手册](004-recipe-rag/runbook.md) · [验收](004-recipe-rag/acceptance.md) |
| 005（当前） | AgentScope stdio 接入固定版本 Bilibili MCP，只读搜索做饭教学视频 | [规格](005-bilibili-video-mcp/spec.md) · [运行手册](005-bilibili-video-mcp/runbook.md) · [验收](005-bilibili-video-mcp/acceptance.md) |
| 006（当前） | 菜谱 RAG 与视频并行编排、Ragas 离线回答评测 | [规格](006-parallel-recipe-video-ragas/spec.md) · [验收](006-parallel-recipe-video-ragas/acceptance.md) |
| 007（当前） | 可选的 AgentScope 轻量查询改写与 dev-only 对照评测 | [规格](007-recipe-query-rewrite/spec.md) · [验收](007-recipe-query-rewrite/acceptance.md) |
| 008（当前） | 最近展示菜品、选中菜品与多轮菜谱指代 | [规格](008-recipe-conversation/spec.md) |
| 009（当前） | 按食材找菜的推荐说明、原料核对与证据回答 | [规格](009-ingredient-recipe-suggestions/spec.md) · [验收](009-ingredient-recipe-suggestions/acceptance.md) |
| 010（当前） | 管理页与聊天共用口语餐食草稿，确认后录入个人 MySQL 库 | [规格](010-conversational-meal-intake/spec.md) · [验收](010-conversational-meal-intake/acceptance.md) |
| 011（当前） | React 前端重写、固定聊天输入区、键盘发送和页面交互 | [规格](011-react-frontend/spec.md) · [验收](011-react-frontend/acceptance.md) |
| 012（当前） | 原 IntentAgent、Jev 原始决策与真实回退链路的离线对照 | [规格](012-intent-comparison/spec.md) · [运行指南](../guides/intent-comparison.md) · [验收](012-intent-comparison/acceptance.md) |
| 013（当前） | 可选 Cross-Encoder 重排、显式阈值准入和共享候选对照；默认关闭 | [规格](013-recipe-relevance/spec.md) · [运行指南](../guides/recipe-relevance.md) · [验收](013-recipe-relevance/acceptance.md) |
| 014（当前） | MySQL 用户长期记忆、消息任务、过敏确认和偏好归纳；默认关闭 | [规格](014-user-memory/spec.md) · [运行指南](../guides/user-memory.md) · [验收](014-user-memory/acceptance.md) |
| 015（当前） | Planner/Menu/Review 受控协作、跨日餐食规划、确定性校验和有限修订；默认关闭 | [技术方案](015-multi-agent-meal-planning/spec.md) · [运行指南](../guides/meal-planning.md) · [验收](015-multi-agent-meal-planning/acceptance.md) |
| 016（待平台验收） | 微信小程序认证、邀请制用户隔离、隐私 Trace 与体验客户端 | [规格](016-wechat-mini-program/spec.md) · [云开发部署](../guides/wechat-cloudbase.md) · [本地验收](016-wechat-mini-program/acceptance.md) |
| 017（待云端验收） | 餐食搭配版本、份量问卷与粗估营养区间 | [规格与验证](017-nutrition-variants.md) · [云库迁移](../guides/wechat-cloudbase.md) |

第一阶段已完成工程验收，见 [001 验收记录](001-meal-retrieval-evaluation/acceptance.md)。阶段 002 的餐食 RAG 文档与代码已删除，阶段 003 只保留带退役标记的历史文档。阶段 004 定义菜谱 RAG，阶段 005 定义真实视频 MCP，阶段 006 定义并行编排和 Ragas，阶段 007 定义可选查询改写；实现和运行状态以最新对应阶段文档为准。

## 开发约束

- 用验收条目 ID 关联任务和验证证据；规格变化记录原因与影响。
- 相关性标注独立于检索结果。助手草拟的标签在用户复核前不能称为人工金标。
- 开发集调参；参数冻结后运行测试集。相同需求的近义改写不能跨集合。
- 演示数据、假设配方、数据库标签与验证过的外部事实明确区分。
- 工作区已有 README、application.yml、意图评测和 tmp 的未提交变更，保留，不计为本次交付。
