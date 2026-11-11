# 记忆抽取开发集 v1

`dev-v1.json` 是人工指定的14条开发用例，覆盖六种候选类型、他人、多事实、指代、假设、否定、临时、撤销与未知食材。它不是锁定测试集，也不构成远程模型质量报告。

离线解析、证据保护、Jev降级与配置测试不调用真实API：

```powershell
mvn '-Dtest=DeepSeekMemoryExtractorTest,JevMemoryExtractorTest,MemoryExtractionConfigurationTest,MemoryExtractionDatasetTest' test
```

真实DeepSeek评测必须显式设置`-Ddiet.memory.live-eval=true`，并在环境中提供`DEEPSEEK_API_KEY`。可设置`DEEPSEEK_BASE_URL`与`DEEPSEEK_LIGHT_MODEL`；密钥不会写入报告。

```powershell
mvn '-Dtest=MemoryExtractionLiveEvaluationTest' '-Ddiet.memory.live-eval=true' test
```

结果保存在本地`target/memory-extraction-live-report.json`，分别报告候选召回率、危险误写率、字段准确率、失败数、P50/P95时延和逐例结果。证据可选择不同的原文连续片段，因此字段评分不比较evidence的字面完全相等，但每项仍经过生产证据校验。

危险误写指模型输出了`SELF + LONG_TERM + ADD`，但开发集没有相同`type/value/stance`的可保存事实；分母为所有模型输出中可保存的候选。候选召回率以六个语义字段全部匹配计算。字段准确率仅统计已匹配`type/value`的候选的subject/stance/scope/operation。没有预测时误写率为0，并同时报告预测数和召回率，避免把全部拒答写成质量提升。

真实评测失败不会冒充空候选；使用单独错误字段和失败计数。默认测试仅校验数据集结构及生产校验契约，不声明真实模型的识别准确率。Jev分类增强默认关闭，尚无远程对比结论。
