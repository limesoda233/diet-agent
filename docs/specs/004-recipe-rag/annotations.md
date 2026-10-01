# 菜谱 RAG 评测标注说明

## 固定对象

- 语料：HowToCook `1.6.0`
- commit：`a0b14bd388ee996943173679c3a7c9db7da04a6c`
- 查询数：64
- 数据集哈希：`69ef43df658bd73154eb90daefed967a213aae67bfae20d8cfed0abf5e94bc4f`
- 开发集：48 条；锁定测试集：16 条。每个切片前 6 条属于开发集，后 2 条属于测试集。

`queries.jsonl` 保存用户查询、切片、集合划分和结构化约束。`answers.jsonl` 独立保存相关 `recipe_id`、等级、必要事实、禁止事实、固定源文件和逐条标注依据。开始比较方案后，不根据锁定测试集结果修改 qrels；如果固定语料或解析规则变化，应建立新数据集版本并更新哈希。

## 标注规则

1. 只依据固定 commit 中的 Markdown 原文标注，不依据 BM25、向量检索或模型输出反推相关性。
2. 菜名、常见别称、明确食材组合、烹饪技法、厨具和复合约束可以标为相关；仅共享宽泛词语不算相关。
3. 当前基线每条语料内查询标一个主要相关菜谱，等级为 3。后续增加次相关菜谱时可标 1 或 2，但不得删除原主要相关项来迎合排序。
4. `out_of_corpus` 查询的相关集合为空，期望拒答。它们不进入 Recall、MRR 和 nDCG 的分母，单独计算拒答准确率与空结果率。
5. `requiredFacts` 用于引用覆盖和忠实度检查；`forbiddenFacts` 用于检测热量、营养功效、替换方案和来源编造。

## 查询切片与依据

| 查询 ID | 切片 | 标注依据 |
| --- | --- | --- |
| rq-001–rq-008 | exact_name | 查询直接包含固定文件中的菜名。对应西红柿炒鸡蛋、宫保鸡丁、鱼香肉丝、可乐鸡翅、麻婆豆腐、酸辣土豆丝、皮蛋瘦肉粥、微波炉鸡蛋羹。 |
| rq-009–rq-016 | alias | 查询使用常见简称或表达变化，如“番茄炒蛋”“花生鸡丁”“微波炉蒸蛋”；相关文件仍由菜名和原料共同确认。 |
| rq-017–rq-024 | ingredient_reverse | 查询以原料组合反查菜谱；组合分别能在固定文件的原料段中定位。 |
| rq-025–rq-032 | technique | 查询强调焖、蒸、烤、拍拌、炒、煎、微波炉蒸和红烧；对应文件的操作段直接描述该技法。 |
| rq-033–rq-040 | constraints | 查询包含必须或排除食材；相关文件满足必须食材且不含被排除食材。 |
| rq-041–rq-048 | tools_time | 查询强调微波炉、空气炸锅、电饭煲、烤箱、蒸锅或时间；菜名、工具段或操作段提供依据。时间条件只用于查询切片，不在缺少明确耗时时推断完成时间。 |
| rq-049–rq-056 | multi_condition | 长查询组合菜名隐含意图、食材、技法、工具或否定限制；所有硬约束均须满足。 |
| rq-057–rq-064 | out_of_corpus | 量子计算机、Python 爬虫、木工、发动机、App、简历、混凝土和卫星不属于菜谱语料，必须拒答。 |

## 主要来源文件

标注使用的固定文件包括：

- `dishes/vegetable_dish/西红柿炒鸡蛋.md`
- `dishes/meat_dish/宫保鸡丁/宫保鸡丁.md`
- `dishes/meat_dish/鱼香肉丝.md`
- `dishes/meat_dish/可乐鸡翅.md`
- `dishes/meat_dish/麻婆豆腐/麻婆豆腐.md`
- `dishes/vegetable_dish/酸辣土豆丝.md`
- `dishes/soup/皮蛋瘦肉粥.md`
- `dishes/vegetable_dish/鸡蛋羹/微波炉鸡蛋羹.md`
- `dishes/vegetable_dish/烤茄子/烤茄子.md`
- `dishes/vegetable_dish/凉拌黄瓜.md`
- `dishes/aquatic/清蒸鲈鱼/清蒸鲈鱼.md`
- `dishes/semi-finished/空气炸锅鸡翅中/空气炸锅鸡翅中.md`
- `dishes/staple/电饭煲三文鱼炊饭/电饭煲三文鱼炊饭.md`
- `dishes/soup/玉米排骨汤/玉米排骨汤.md`
- `dishes/vegetable_dish/蒜蓉西兰花.md`
- `dishes/aquatic/黄油煎虾/黄油煎虾.md`
- `dishes/meat_dish/红烧肉/简易红烧肉.md`
- `dishes/staple/米饭/电饭煲蒸米饭.md`
- `dishes/breakfast/空气炸锅面包片.md`

每条查询的准确 `recipe_id`、必要事实和标注说明以 `answers.jsonl` 为准；ID 由固定 commit、相对路径和内容哈希按生产导入器相同的 SHA-256 规则计算。

## 本次验证状态

- 离线指标、数据集完整性、哈希锁定和并行延迟测试已通过。
- 使用本地固定 commit checkout 执行了 qrels 路径与稳定 ID 对照，56 条语料内查询全部匹配。
- 2026-10-01 探测 `localhost:11434` 与 `localhost:6333` 均在 3 秒内无响应，因此未生成真实 Ollama/Qdrant 分数。真实评测入口保持为显式 opt-in，不能用离线假分数替代。
