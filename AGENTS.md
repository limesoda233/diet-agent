# Diet Agent 项目规则

- 项目定位：Spring Boot + AgentScope 的饮食推荐练习项目；餐食推荐与公共菜谱问答是两个数据域。
- 技术栈：Java 21、Spring Boot 3.3.13、AgentScope Java 1.0.11、MyBatis/MySQL、Lucene 9.12.3、Qdrant、Reactor。
- 默认验证：运行 `mvn test`；交付前再运行 `mvn -DskipTests package`。
- 餐食推荐只使用 MySQL。不得把 meal 数据写入 Lucene/Qdrant，也不得让推荐依赖 embedding 服务。
- 公共菜谱 RAG 位于 `service/recipe/rag`，语料固定为 HowToCook 指定 commit；所有回答必须引用本轮证据，证据不足时拒答。
- 用户个人做法保存在 MySQL `meal_knowledge`，不得写入公共菜谱索引。
- 默认检索为 BM25 与 dense 并行、RRF 融合和父块展开；两分支都失败时不得生成答案。
- 查询改写、重排和视频 provider 默认关闭或使用 identity/disabled 实现，不能在文档中冒充已接入真实模型或平台。
- 菜谱评测使用 `src/test/resources/evaluation/recipe` 的开发集和锁定测试集；不要根据锁定测试结果修改 qrels。
- 当前规格、运行命令和验收状态见 `docs/specs/004-recipe-rag/`。阶段 003 文档只保留为历史记录。
