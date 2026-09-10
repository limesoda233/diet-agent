# 第一阶段验收记录

日期：2026-09-10。结论：EVAL-01～07 已完成工程验收。未改变线上检索行为；未接入 Qdrant、embedding 或 MCP。相关性标签仍是助手初稿，配方仍是评测假设，工程验收不等于用户效果验收。

## 交付与证据

| 条目 | 实现与验证 |
| --- | --- |
| EVAL-01 | 固定 45 道餐食（公共 24、个人 21），64 条查询、32 个需求组、8 个切片；dev 48 / test 16。RetrievalDatasetTest 的 5 项测试通过，覆盖完整标注、组隔离、配方回归、食材词表、严格 JSON。可读标注见 annotations.md。 |
| EVAL-02 | RetrievalMetricsTest 的 4 项测试通过：手算 nDCG≈0.688529、P@3 固定分母、无相关集 null、短列表、重复/未知 ID 和最终项必须来自候选。 |
| EVAL-03 | MySQL 8.4.11 隔离实例中直接加载生产 MealMapper.xml，复用 MealService、MealSearchService、MealRankService。2 项集成测试验证多维 AND、数据域隔离、排除历史 ID，以及独立 56 行场景的 50 条截断。 |
| EVAL-04 | 原始结果分别统计候选/最终数据域、已排除 ID、禁用食材和缺少必需食材；固定违规样例通过。报告保留当前算法不消费原话、不支持食材限制的 capabilityGaps。 |
| EVAL-05 | Markdown / JSON 已生成，64 条明细均标为 draft；包含集合、切片、分母、完整候选、Top3、重复排名、延迟、版本和哈希。报告代码仅写预先列出的元信息，不写连接配置。 |
| EVAL-06 | 显式 MySQL 全量测试通过；停止评测容器且无专用 DB 环境变量后默认离线测试通过。指向未监听的 33078 端口时，执行器以连接失败退出，report.json / report.md 均不残留。 |
| EVAL-07 | 本阶段新增测试、数据、脚本、Compose 与文档；生产 Java / Mapper / Prompt 无修改。既有测试通过。原有 README、application.yml、意图评测等工作区改动保留；README 仅追加检索评测入口。 |

## 执行记录

运行环境：Windows、Java 21.0.12、Maven 3.8.8、MySQL 8.4.11。Maven 依赖使用本机缓存；未调用真实 LLM。

| 命令 / 场景 | 实际结果 |
| --- | --- |
| `mvn -o "-Dtest=RetrievalDatasetTest,RetrievalReportTest,MySqlEvaluationConfigTest" test` | 7 项通过；复核新增词表、严格类型和 DB URL 配置测试。 |
| `mvn -o "-Dretrieval.eval.mysql=true" "-Dtest=RetrievalBaselineEvaluationTest" test`，DB 指向本机未监听的 33078 | 按预期失败：CommunicationsException / Connection refused，进程退出码 1。旧报告已清除；这是故障验证，不计为质量运行。 |
| `mvn -o "-Dretrieval.eval.mysql=true" test`，DB 为专用 33077 实例 | 19:50:22 完成：17 项，16 通过、1 跳过、0 失败/错误。跳过项为真实模型意图评测。 |
| `docker compose -f compose.retrieval-eval.yml stop` | 本次创建的评测容器已停止。 |
| `mvn -o test`，评测数据库已停止 | 19:51:19 完成：17 项，13 通过、4 跳过、0 失败/错误。跳过真实 LLM 1 项、真实 MySQL 3 项。 |

详细复现命令见 [runbook.md](runbook.md)。测试期间按先失败、再修复、再通过的顺序验证了指标、数据加载、错误配方、非法食材、辅助计数及严格 JSON 校验。

## 固定基线结果

报告运行时间：2026-09-10T19:50:09+08:00；策略 `mysql-json-overlaps-production-v1`。每条查询预热一次、测量三次；质量取首次测量，延迟使用全部 192 次测量。64 条查询中 52 条有相关餐食、12 条无相关餐食。

| 集合 | Recall@50 | Recall@3 | P@3 | nDCG@3 | MRR@3 | 无匹配正确率 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| overall | 0.7995 | 0.5114 | 0.5513 | 0.5996 | 0.7372 | 0.5000 |
| dev | 0.7769 | 0.4973 | 0.5500 | 0.5955 | 0.7333 | 0.5000 |
| test | 0.8750 | 0.5583 | 0.5556 | 0.6132 | 0.7500 | 0.5000 |

共召回 324 个候选、返回 146 个最终结果。候选/最终数据域违规均为 0，已排除 ID 违规为 0；禁用食材违规 10 个结果、涉及 10/64 条查询；缺少必需食材 44 个结果、涉及 16/64 条查询。后两项基于假设配方，不代表真实过敏原检测。

52 条有相关项查询中，空结果率为 0，不足率为 8/52；18/52 条查询的相关餐食本来只有 1～2 道。辅助计数 shortReturnQueries 为 24/64，含无匹配查询；它与上述不足率有不同分母和含义。本机检索加排序 P50=2.5547 ms、P95=3.4573 ms，不含 LLM/embedding。本次重复排名波动为 0，不表示所有 MySQL 环境的并列排序都稳定。

主要问题：已有食材切片的最终 Recall@3 为 0；原话改写无法影响固定槽位基线；多个软偏好被 SQL AND 同时限制，存在漏召回。第二阶段应分别验证新增食材信息、约束过滤、向量检索带来的收益。

## 可追溯信息

- Git HEAD：`810e7986118bb5073c1e25b99105c11252733e17`；运行时工作区 dirty=true。
- corpus SHA-256：`c989c4c49a803f758486a00a780c701f3ba4e601faaef27bfb13dee88cffbfff`。
- queries SHA-256：`e0980dd07fcb7fd296e236e165e6d0b5daa25e190b01e23bc0787c129c587dd9`。
- Mapper SHA-256：`4bad8d026e6da0e5d8f0295fba8037e428873aedea051c680a4a8cecb2742173`。
- 完整报告：`target/evaluation/retrieval/report.md`、`report.json`；该目录是构建产物，`mvn clean` 会删除，可按运行说明重建。生产服务的其他哈希也保存在 JSON 元信息中。

## 审查与限制

独立代码审查提出的词表缺失、JSON 数值隐式转换、语料不足诊断缺失均已修复并测试。对审查后提出的短结果分母疑问，保留规格中明确的两个口径：正式 insufficientRate 仅统计有相关项的查询；shortReturnQueries 是所有查询的辅助计数，Markdown 明示包含无匹配。并未改变正式指标的分母。

配方审阅发现意面与荞麦面曾被误写成米饭，已修正生成源、相关标注并增加回归测试。所有标注仍为 draft；不能将这份小型合成基线写成“真实用户准确率”。同一需求有两个表达，共 32 个独立需求组；不能将 64 条表达视为 64 个独立需求。

下一步形成第二阶段详细规格：保留 MySQL，加入本地 Qdrant、可配置云 embedding、明确约束和混合检索。供应商尚未确定，真实语义质量需要配置实际模型后测量。
