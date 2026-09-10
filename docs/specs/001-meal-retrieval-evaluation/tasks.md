# 第一阶段实现任务

**Goal:** 建立真实 MySQL 餐食检索基线。
**Spec:** [spec.md](spec.md)
**Architecture:** 评测代码位于 src/test/java/com/diet/evaluation/retrieval；固定 JSON 数据位于 src/test/resources/evaluation/retrieval；复用生产检索组件，不改变在线行为。JDBC 临时表隔离数据，默认测试只运行离线逻辑。
**Tech Stack:** Java 21、JUnit 5、Jackson、MyBatis、MySQL 8。

## 全局约束

- 保留当前工作区已有修改，不使用业务数据库、不改生产检索或 Prompt。
- 45 道餐食、至少 64 条查询、完整 0/1/2 标注、组级 dev/test 分离、draft 状态可见。
- 每个任务先运行失败测试，再实现并验证；失败不得回退为假成功。

## 任务 1：固定数据与加载校验（EVAL-01）

- [x] 创建 RetrievalDatasetTest：验证完整等级矩阵、非法食材/ID、重复、跨 split 分组被拒绝。
- [x] 运行 `mvn -o "-Dtest=RetrievalDatasetTest" test`，确认缺少数据/校验时失败。
- [x] 新建 RetrievalDataset.java，定义 Meal、Query、Corpus、Queries 记录及 load()/read()/validate()；新建 corpus.json、queries.json。
- [x] 查询标注逐组独立指定 preferred/acceptable IDs，输出完整等级矩阵；不调用检索器产生标签。
- [x] 增加生成/审计脚本和 annotations.md，标明配方假设及种子 provenance。
- [x] 重跑测试并检查 45/64 数量、所有 slice、组隔离。

## 任务 2：指标与违规检测（EVAL-02、04）

- [x] 创建 RetrievalMetricsTest，用手算例验证：grades={1:2,2:1}, returned=[2,3,1]，P@3=2/3，Recall@3=1，MRR=1，nDCG≈0.688529；空相关集指标为 null。
- [x] 增加违规样例：跨数据域、已排除 ID、禁用食材、必需食材缺失，以及重复/未知 ID。
- [x] 运行 `mvn -o "-Dtest=RetrievalMetricsTest" test` 验证失败。
- [x] 新建 RetrievalMetrics.java：evaluate(Query, List<Meal>, List<Long> candidates, List<Long> top, double latencyMs)，返回质量指标与违规；执行器另记录策略能力缺口。
- [x] 重跑测试，确认错误分母/折扣/去重会被测试捕获。

## 任务 3：真实 MySQL 适配器（EVAL-03、06）

- [x] 新建 MySqlRetrievalBaselineTest，以显式 retrieval.eval.mysql 开关启用。
- [x] 建立 MySqlRetrievalAdapter，只从 RETRIEVAL_EVAL_DB_URL/USERNAME/PASSWORD 连接；同一连接创建临时表并由生产 Mapper 查询。
- [x] 复用 MealService、MealSearchService、MealRankService，返回原始候选和 Top3；清理仅关闭会话。
- [x] 增加独立 MySQL Docker Compose 和集成测试：多维 AND、数据域隔离、换一批、超过 50 条的截断诊断。
- [x] 在隔离 MySQL 运行并验证真实行为，不对质量分设置虚假高阈值。

## 任务 4：报告、基线与验收（EVAL-05、07）

- [x] 创建 RetrievalReportTest，验证分组/有效分母、空相关集、draft、版本信息和 JSON 可读。
- [x] 新建 RetrievalReport.java 和 RetrievalBaselineEvaluationTest；后者预热并重复运行固定案例，记录波动，输出 Markdown/JSON。
- [x] 实现语料/查询/Mapper SHA-256、Git 元信息和运行环境记录，不序列化连接配置。
- [x] 生成 dev/test 基线、可读标注说明与运行文档。
- [x] 运行全量 `mvn -o test`，然后显式 MySQL 测试，审查 diff 并逐条记录验收证据。

## 任务完成记录

2026-09-10：以上任务完成，真实 MySQL 与停止数据库后的默认测试均通过。执行命令、结果、审查处理和限制见 [acceptance.md](acceptance.md)。
