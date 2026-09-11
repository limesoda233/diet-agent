# 混合餐食 RAG 实现任务

> 执行方式：使用 executing-plans 在当前会话逐项实现，先写失败测试，再实现并验证。用户已要求继续实施，不重复询问执行方式。

**Goal:** 让原话参与双路检索，使明确限制贯穿检索、降级及推荐生成。
**Spec:** [spec.md](spec.md)
**Architecture:** MySQL 为事实源；新 retrieval 包提供规范化请求、数据存储、HTTP 适配器、融合协调和索引同步。现有单餐/三餐改为调用同一协调服务，legacy 路径保留；代码与测试不依赖实际云 Key。
**Tech Stack:** Java 21 运行时、Spring Boot、MyBatis/JDBC、JUnit 5、JDK HttpClient/HttpServer、MySQL 8、Qdrant REST。

## 全局约束

- 不读取或覆盖用户已有密钥配置；新配置使用环境变量和独立配置类。
- 固定第一阶段相关性标签；评测假设配方不写入业务数据库。
- 权限及明确约束贯穿所有通路，MySQL 失败时不返回向量缓存。
- 测试向量只用于协议测试；真实 embedding 质量单独验收。
- 生产文件的改动保持向后兼容，已有构造调用与意图测试继续有效。

## 任务 1：请求、知识与约束（RAG-01、02）

文件：新增 `service/retrieval/MealConstraints.java`、`MealRetrievalRequest.java`、`MealKnowledge.java`、`MealDocument.java`、`IngredientCatalog.java`、`ConstraintParser.java`；新增独立规范词表资源；修改 MealRequest / MealItem / MealResponse / SessionState / SessionStateService。

接口：约束 record 包含食材与标签的包含/排除；`ConstraintParser.resolve(String, MealConstraints, Map<String,Object>)` 返回生效约束；`resolveContext` 另返回需求、来源与待澄清状态；`MealDocument` 包含餐食、知识、规范文本和哈希。

- [x] 先编写 `ConstraintParserTest`，用明确排除、别名、同时使用、取消、结构化替换、未知/冲突/歧义样例驱动。

```java
assertThat(parser.resolve("不要牛肉", MealConstraints.empty(), null).excludedIngredients()).containsExactly("牛肉");
assertThat(parser.resolve("同时用西红柿和鸡蛋", MealConstraints.empty(), null).requiredIngredients()).containsExactly("番茄", "鸡蛋");
assertThatThrownBy(() -> parser.resolve("不要不放牛肉", MealConstraints.empty(), null));
```

- [x] 运行 `mvn -o "-Dtest=ConstraintParserTest" test`，确认缺少实现导致失败。
- [x] 实现不可变约束、词表、有限规则及严格字段校验；保留旧构造器；session 元数据增加 retrieval 状态。
- [x] 添加老会话读取、新限制往返及知识字段缺失/显式清除测试，运行相关测试。

## 任务 2：MySQL 存储与云服务适配（RAG-02、03、04）

文件：新增 `db/migrations/002_meal_retrieval.sql`、`MealRetrievalStore.java`、`JdbcMealRetrievalStore.java`、`EmbeddingClient.java`、`HttpEmbeddingClient.java`、`VectorStore.java`、`QdrantVectorStore.java`、`RetrievalProperties.java`、`RetrievalConfiguration.java`；修改 MealService 的知识保存和任务登记，新增本机 Qdrant Compose。

接口：store 支持 `structured(request,limit)`、`find(ids)`、`saveKnowledge` 和索引任务；embedding 支持 `embedDocuments(List<String>)` / `embedQuery(String)`；vector 支持 collection 校验、upsert、delete、search。

- [x] 写本地 HTTP 契约测试，返回乱序 embedding 索引时必须重排，缺项/零向量/错误维度必须失败；429 最多一次重试，401 不重试。

```java
assertThat(client.embedDocuments(List.of("甲", "乙"))).containsExactly(new float[]{1, 0}, new float[]{0, 1});
assertThatThrownBy(() -> client.embedQuery("缺少向量"));
```

- [x] 运行契约测试确认失败后实现 JDK HTTP 客户端、严格响应校验及有界重试，不新增云 SDK。
- [x] 写隔离 MySQL 测试：迁移运行两次成功，软偏好不 AND 清空结果，硬食材条件先于 LIMIT，旧餐食没有知识时按未知处理。
- [x] 实现参数化 SQL、知识表和事务内任务登记；不依赖业务 DB 验证。
- [x] 用独立 Qdrant + 固定测试向量验证 payload 过滤、维度校验、写入和删除；明确不产生语义成绩。

## 任务 3：融合、索引同步与故障降级（RAG-05～08）

文件：新增 `ReciprocalRankFusion.java`、`MealRetrievalService.java`、`MealIndexService.java`、`MealIndexCommand.java`；对应单元测试和故障集成测试。

接口：`MealRetrievalService.retrieve(request)` 返回候选、排名、证据与诊断；`MealIndexService.drain()` 处理持久任务，`rebuild()` 重建并追平更新，命令仅本地显式开启。

- [x] 测试融合手算及稳定排序：两个第一名相同得 `2/61`；单通路第一个得 `1/61`，不叠加 SQL/cosine 原始分数。

```java
assertThat(ReciprocalRankFusion.fuse(List.of(1L, 2L), List.of(1L, 3L)).get(1L)).isCloseTo(2.0 / 61, within(1e-10));
```

- [x] 测试向量结果中跨域、删除、陈旧和违规项被回查拒绝，结构化与向量均不可放宽硬条件。
- [x] 实现协调服务、按 hash 校验的回查和独立降级状态；MySQL 错误继续上抛。
- [x] 测试重复任务、任务处理期间再次修改、删除任务、超时失败五次及重建追平。
- [x] 实现数据库持久任务及数据库锁串行的跨进程后台处理、本地状态/重试/重建命令；不提供匿名管理 API。

## 任务 4：业务接入、证据与对照（RAG-09、10）

文件：修改 DietOrchestratorService / MealPlanService / RecommendResponseAgentService；新增协调接入回归测试；扩展 test 下独立策略对照执行器，保留旧基线；新增运行与验收文档。

- [x] 编写单餐/三餐排序不被旧 rank 覆盖的测试，以及多轮换一批保留约束、三餐互相排除的测试。
- [x] 在进入推荐分支前解析限制并保存元数据，替换 search + rank 为一次协调调用；不影响意图测试输出契约。
- [x] 把回查后的 Top3 知识传给生成 Agent，保留 ID 白名单和模板兜底；增加检索 Trace，过滤密钥与向量。
- [x] 提供 legacy / structured / hybrid 同版数据对照，固定约束轨道与规则解析轨道单独标记；真实 embedding 未配置时明确跳过语义效果。
- [x] 运行默认离线测试、隔离 MySQL/Qdrant 集成及故障测试，审查改动，更新验收表。
- [ ] 真实云模型配置后冻结参数，执行 RAG-10；此前保持默认 legacy/structured，不能宣称混合语义效果已验证。

## 验收补充（2026-09-12）

- 索引任务改为全局版本日志 + 按 collection 完成进度，数据库锁防止跨进程向量写入相互覆盖。
- 已覆盖初始化失败计数、连续五次失败、读取补偿不重置预算、处理期间再次修改和重建追平。
- 新增待澄清限制持久化、完整重述后解除澄清、纯换一批保留语义调整测试。
- RAG-04 已在 Qdrant v1.18.2 完成协议验证；最终工作树复测仍受本机 Docker 启动故障影响，见 acceptance.md，不把旧结果冒充本轮运行。
- RAG-10 未运行；所有效果标签仍为 draft。详细命令、证据与解析缺口见 runbook.md、acceptance.md。
