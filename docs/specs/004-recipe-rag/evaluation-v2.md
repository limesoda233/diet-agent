# 菜谱 RAG 评测集 v2 规格

## 目标

v2 修复 v1 单一 Gold、测试菜谱重复和拒答语义混合的问题。v1 的查询、qrels 与 fingerprint 保持不变，只用于历史结果复现；新的标注与测试结果写入 v2。

固定语料仍为 HowToCook `1.6.0` commit `a0b14bd388ee996943173679c3a7c9db7da04a6c`。菜谱 ID、相对路径和语料统计均以生产 `RecipeCorpusImporter` 的实际输出为准。

## 评测语义

每条查询分别声明检索期望和回答期望：

- `retrievalExpectation=RELEVANT`：语料中存在可用于回答的菜谱，qrels 至少包含一个相关父菜谱。
- `retrievalExpectation=EMPTY`：固定语料中没有可回答该问题的菜谱，qrels 必须为空。
- `answerExpectation=ANSWER`：证据足够，应给出带引用回答。
- `answerExpectation=REFUSE_OUT_OF_CORPUS`：问题不在语料覆盖范围内，应拒答。
- `answerExpectation=REFUSE_UNSUPPORTED`：菜谱可以召回，但用户要求的具体事实没有原文证据，应明确说明证据不足。

v1 的 `mustRefuse` 只在兼容加载时映射到上述语义，新版本不再使用这个混合字段。

## 相关性分级

- grade 3：直接回答查询并满足全部硬约束。
- grade 2：满足全部硬约束，是可用替代菜谱，但标题、做法或软意图匹配弱于 grade 3。
- v2 暂不使用 grade 1，避免二值 Recall/MRR 将弱相关结果当成完全相关。

宽泛查询必须审核整个语料候选池。多个菜谱同样完整满足查询时全部标为 grade 3，不为制造分级而任意降低相关性。

## 数据规模与覆盖

v2 至少满足：

- 90 条查询；
- 至少 30 个不同的正向 Gold 菜谱；
- 至少 8 条多 Gold 查询；
- 至少 4 条 near-OOD；
- 至少 4 条 `REFUSE_UNSUPPORTED`；
- test 中至少 24 条正向检索查询、至少 16 个不同 Gold 菜谱；
- dev 与 test 按 Gold 菜谱实体分组切分，任一相关菜谱不得跨集合出现。

查询文案中的硬约束必须能由结构化字段等价表达。当前 schema 不能表达“所有肉类”“不开火”“只有这些食材”时，应改写文案，不得用更窄的字段冒充。

## 难例

新增查询覆盖错别字、口语别名、长查询、多条件、相似词干扰和此前未作为 Gold 的菜谱。新菜谱按肉类、水产、蔬菜、汤粥、主食、早餐和饮品/甜品等类别分层选择，不按检索结果挑选。

near-OOD 必须看起来像真实菜谱问题，但固定语料中不存在对应菜谱。`REFUSE_UNSUPPORTED` 必须能正常检索到目标菜谱，同时原文确实不包含用户要求的精确事实或替代方案。

## 数据冻结

1. 完成查询、qrels、来源路径和标注说明。
2. 用生产 importer 校验所有路径与稳定 recipe ID。
3. 校验 dev/test 实体隔离、计数和枚举组合。
4. 计算并锁定 v2 fingerprint。
5. fingerprint 锁定后才能运行 v2 test。
6. 后续只使用 dev 调整参数；不得根据 v2 test 结果修改 qrels。

## 报告

报告记录数据集版本、fingerprint、语料 commit/hash、有效文档数、查询数、正向查询数、唯一 Gold 数、多 Gold 数和 test 单条样本的最大百分点影响。

检索报告继续提供 Recall@1/5/10、MRR@10、nDCG@10、切片指标、空结果率、重复率和延迟，并增加 near-OOD 误召回率。回答报告分别统计 `REFUSE_OUT_OF_CORPUS` 与 `REFUSE_UNSUPPORTED`，不得合并为一个拒答准确率。
