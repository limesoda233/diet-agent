# Diet Agent 项目规则

- 项目定位：Spring Boot + AgentScope 的饮食推荐练习项目；餐食推荐与公共菜谱问答是两个数据域。
- 技术栈：Java 21、Spring Boot 3.3.13、AgentScope Java 1.0.11、MyBatis/MySQL、Lucene 9.12.3、Qdrant、Reactor。
- 默认验证：运行 `mvn test`；交付前再运行 `mvn -DskipTests package`。
- 餐食推荐只使用 MySQL。不得把 meal 数据写入 Lucene/Qdrant，也不得让推荐依赖 embedding 服务。
- 公共菜谱 RAG 位于 `service/recipe/rag`，语料固定为 HowToCook 指定 commit；所有回答必须引用本轮证据，证据不足时拒答。
- 用户个人做法保存在 MySQL `meal_knowledge`，不得写入公共菜谱索引。
- 默认检索为 BM25 与 dense 并行、RRF 融合和父块展开；两分支都失败时不得生成答案。
- 查询改写和重排默认使用 identity；视频搜索默认关闭，启用 `video-mcp` profile 后才连接固定版本的 Bilibili MCP。不得把默认关闭或未验证的能力写成已启用。
- 菜谱评测使用 `src/test/resources/evaluation/recipe` 的开发集和锁定测试集；不要根据锁定测试结果修改 qrels。
- 文档统一入口 `docs/README.md`；运行/阅读指南位于 `docs/guides`，面试稿位于 `docs/interview`。规格和验收保留在 `docs/specs`；已完成的实施计划不再作为运行入口。
- 菜谱 RAG 见阶段 004，视频 MCP 见 005，并行/Ragas 见 006，查询改写见 007，多轮指代见 008。阶段 003 只保留退役说明。
- `data/`、`tmp/`、`target/`、Python 缓存为本地产物，不提交；`src/test/resources/evaluation` 的固定数据和 `evals/recipe-ragas/uv.lock` 必须保留。
