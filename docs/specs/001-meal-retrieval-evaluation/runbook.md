# 运行与复核餐食检索评测

## 评测的含义

这是一套固定槽位轨道的工程基线。检索器接收查询和约束，不接收相关性标签、标注理由或集合名称。当前策略直接调用生产 MySQL Mapper 和检索/排序服务，未替换为 Java 模拟查询，也不调用 LLM。

语料为仓库 45 道种子餐食的独立快照，24 道公共餐食、21 道用户 1 的个人餐食。用户 2、3 的空库用于检查隔离。64 条查询来自 32 个需求组，每组两种表达；dev 48 条、test 16 条。8 个切片各 8 条。

当前标签均为助手草拟的 `draft`，不是已复核金标。主要食材和做法是评测用假设；原健康标签也没有数值核验。原始槽位、明确食材条件和相关性理由一起存档，请结合 [标注审阅表](annotations.md) 复核。不要根据基线返回结果反向修改标签。

## 默认离线测试

```powershell
mvn test
```

默认跳过真实 MySQL 评测及真实 LLM 意图评测，运行数据契约、指标、报告和既有普通测试。依赖已缓存时可使用 `mvn -o test`。

## 隔离的 MySQL 基线

需要 Java 21、Maven、Docker Desktop。第一阶段无需 embedding Key、Qdrant 或食谱 API。

在项目根目录执行：

```powershell
docker compose -f compose.retrieval-eval.yml up -d --wait
$env:RETRIEVAL_EVAL_DB_URL = 'jdbc:mysql://127.0.0.1:33077/diet_retrieval_eval?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:RETRIEVAL_EVAL_DB_USERNAME = 'root'
$env:RETRIEVAL_EVAL_DB_PASSWORD = ''
mvn '-Dretrieval.eval.mysql=true' '-Dtest=MySqlRetrievalBaselineTest,RetrievalBaselineEvaluationTest' test
```

Compose 创建专用 MySQL 8.4 容器，只绑定本机 33077 端口，使用 tmpfs 存储；空密码仅用于这个临时本机评测实例。不要复用为线上数据库。

执行器要求数据库名 `diet_retrieval_eval`，主机为 localhost 或 127.0.0.1。它从专用环境变量取连接，不读取 `application.yml`。同一连接内创建临时 `meal_item` 表并写入快照，加载现有 `mapper/MealMapper.xml` 查询，连接关闭后临时表消失。不会执行仓库中带 DROP TABLE 的初始化脚本。

`MySqlRetrievalBaselineTest` 验证真实多维 AND、数据域隔离、换一批，以及单独 56 行场景中旧记录被 50 条上限截断。56 行场景不进入质量统计。

`RetrievalBaselineEvaluationTest` 对每条查询预热一次、测量三次。MyBatis 一级缓存使用 STATEMENT，防止重复采样只测缓存。用 `-Dretrieval.eval.repetitions=5` 可调整重复次数，范围 1～20。

完成后可停止专用实例：

```powershell
docker compose -f compose.retrieval-eval.yml stop
Remove-Item Env:RETRIEVAL_EVAL_DB_URL -ErrorAction SilentlyContinue
Remove-Item Env:RETRIEVAL_EVAL_DB_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:RETRIEVAL_EVAL_DB_PASSWORD -ErrorAction SilentlyContinue
```

## 输出与指标

- `target/evaluation/retrieval/report.md`：汇总、切片、违规计数和失败样例。
- `target/evaluation/retrieval/report.json`：完整查询、标签、候选、Top3、分数、重复排名、延迟样本和版本信息。

质量汇总使用每条查询首次测量结果；延迟分位数使用全部测量，重复排名保留用于查看并列项波动。质量分母以“查询”为单位，两种改写属于同一需求组，不能据此声称 64 个独立用户需求。延迟仅为本机检索和排序，不代表完整对话延迟。

Recall@50 衡量召回，nDCG@3 衡量前三名的分级相关性。P@3 始终以 3 为分母，相关项只有一条时不能达到 1；应结合 nDCG、Recall 和相关项数量阅读。无相关项样本的 Recall/nDCG 为 null，另算无匹配正确率。约束违规在原始返回上计数，不能先清洗结果再评分；报告同时给出返回结果分母和全部查询分母。

当前策略不消费原话，也不支持必需/禁用食材；报告中的 capabilityGaps 明确记录这些限制。它们不是评测执行错误，也不被剔除。后续应增加“相同信息下的算法对比”和“补充食材描述后的整体能力对比”，避免把新增数据的收益全归因于向量检索。

## 数据修订与冻结

数据定义在：

- `scripts/evaluation/build_retrieval_dataset.py`：种子提取与具名假设配方。
- `scripts/evaluation/query-groups.json`：独立撰写的相关性组级标注。
- `src/test/resources/evaluation/retrieval/`：检索器与评测器使用的固定 JSON。

仅在确实修订语料或标注时重建：

```powershell
python scripts/evaluation/build_retrieval_dataset.py
mvn '-Dtest=RetrievalDatasetTest,RetrievalMetricsTest,RetrievalReportTest' test
```

生成器不调用模型或检索器。种子名称或顺序发生变化时会停止，要求显式修订固定 ID 和关联标签。用户复核应在组定义中记录状态和理由，不直接手改生成文件；版本变化后重新生成 SQL 基线，不能与旧哈希成绩直接比较。

组级 `annotationStatus` 可为 `draft` 或 `reviewed`，重建会保留；不要仅为获得 reviewed 状态而修改它。`ingredientVocabulary` 是显式词表，未知食材、前后空格、小数 ID/等级、字符串等级和重复 JSON 属性都会使校验失败。新增合法食材时先修订词表，再补充查询标注。

`emptyRate`、`insufficientRate` 只统计有相关项的查询。辅助项 `corpusLimitedQueries` 表示相关项本来只有 1～2 道的查询数；`shortReturnQueries` 表示所有查询中实际返回少于三条的数量，包含无匹配查询，不能直接当作漏召回率。

进入混合 RAG 阶段后，只在 dev 调参；冻结模型、融合权重、过滤规则后再跑 test。测试集已用于建立旧算法基线，不应继续根据其表现挑参数。
