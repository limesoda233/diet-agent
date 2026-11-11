# Task 1 抽取器实施与验证

日期：2026-10-20。

已实现统一 `MemoryExtractor.extract(String)` 和七字段 `MemoryCandidate`。生产代码只提出与校验候选，不读写个人档案、不访问公共索引、不依赖embedding。

## 调用与校验

DeepSeek基线使用现有 `DietLightChatModel`，每次创建独立SYSTEM/USER消息，无会话Agent缓存、无工具；temperature=0、maxTokens=4096、maxAttempts=1，模型执行和整个抽取流程均有时限。模型失败、超时、空响应与无效JSON均抛出 `MemoryExtractionException`，只有明确的JSON数组 `[]` 表示没有事实。

JSON只接受数组，每项七个字符串字段；拒绝额外字段、缺失字段、重复键、尾随数据、Markdown包裹及非法枚举。最多32项、输入16000字符、输出100000字符、value128字符、evidence2000字符。标签归一化大小写，value去除两端空白，evidence保持原文不改写。证据必须是本轮输入的连续片段，且包含value。MAX_MINUTES限定1至1440的阿拉伯数字并要求原文支持。

所有相关类型可同时返回，包括临时、他人、未知主体候选和过敏。窄范围的服务端保护会降低明确假设、否定过敏、他人或临时原文的确定性；不会用整条消息共用的SELF/范围标记，也不会凭一个否定词推断LIKE。完整语义仍由模型判断，通用语言识别准确率尚未经远程评测。

Jev仅增强固定词表内的TASTE/CUISINE ADD候选，开放食材、过敏、厨具、时长和REMOVE保留基线。每项分别询问subject/stance/scope Choice，state包含完整原消息、候选value及对应evidence，支持跨子句指代。所有回答必须齐全、类型合法、置信度为有限数且达到独立阈值；NONE降为UNKNOWN/NEUTRAL。失败、缺失、非法答案、低置信度或整个分类阶段超时，回退到已校验的DeepSeek基线；分类不能修改type/value/operation/evidence，结果重新经过原文校验。

## 配置

`MemoryExtractionConfiguration`仅在`diet.memory.enabled=true`时生效（该配置类的missing默认true，项目application.yml默认false，须先应用SQL迁移再显式启用）。

| 属性 | 默认值 |
| --- | --- |
| `diet.memory.extraction-timeout` | `8s` |
| `diet.memory.jev-enabled` | `false` |
| `diet.memory.jev-confidence-threshold` | `0.85` |
| `diet.memory.jev-timeout` | `3s` |

Jev增强还需要现有JevClient和已配置的JevProperties。没有配置时基线仍实际调用DeepSeek，不把未配置当成没有记忆。Jev阈值不复用意图识别阈值。

## 红灯与绿灯证据

1. 先写解析与分类测试、接口及抛出UnsupportedOperationException的最小空实现。初次项目测试受并行开发尚未创建的MemoryStore/MemoryValidation阻挡，未把编译错误当成有效红灯。
2. 在`target/`生成仅限定抽取器源码与测试的临时Maven POM，无仓库配置变更。19项测试实际执行后失败（10个断言失败、9个缺失实现错误），确认缺失抽取行为。
3. 实现基线与Jev后，为错误标注的假设/临时证据追加回归测试。22项中2项断言失败：假如/今天被错误保留为长期本人事实；加入逐候选保护后22项通过。
4. 配置测试先执行，3项中1项失败、1项缺少Bean错误，确认空配置未注册提取器。实现配置与无状态模型调用后25项通过。
5. 最后回到正常项目Maven，执行以下命令：

```powershell
mvn '-Dtest=DeepSeekMemoryExtractorTest,JevMemoryExtractorTest,MemoryExtractionConfigurationTest,MemoryExtractionDatasetTest,MemoryExtractionLiveEvaluationTest' test
```

结果：**27项，26通过，1项真实API评测默认跳过，0失败、0错误，BUILD SUCCESS**。测试完成于2026-10-20 21:27:55 +08:00。`git diff --check`未发现抽取器路径的空白错误。未执行真实API，未声明真实DeepSeek/Jev召回率或质量提升。项目全量测试与打包交由阶段总体交付统一运行。

## 文件

- `src/main/java/com/diet/service/memory/extraction/`：MemoryCandidate、MemoryExtractor、MemoryExtractionException、DeepSeekMemoryExtractor、CandidateValidation、JevMemoryExtractor、MemoryExtractionConfiguration。
- `src/test/java/com/diet/service/memory/extraction/`：DeepSeekMemoryExtractorTest、JevMemoryExtractorTest、MemoryExtractionConfigurationTest、MemoryExtractionDatasetTest、MemoryExtractionLiveEvaluationTest。
- `src/test/resources/evaluation/memory/dev-v1.json`：14条人工指定开发用例，包含六种类型、多事实、临时、他人、假设、否定、指代和未知食材。
- `src/test/resources/evaluation/memory/README.md`：运行方式、评分口径和显式真实API开关。

真实评测通过`-Ddiet.memory.live-eval=true`显式启用，要求DEEPSEEK_API_KEY，产出本地target/memory-extraction-live-report.json，报告候选召回率、危险误写率、字段准确率、失败数与P50/P95时延。当前仅验证其默认禁用及数据集契约，未运行远程评测。词表之外的口味/菜系保留DeepSeek结果；词表分类增强也未完成远程开发集质量验证，因此默认保持关闭。
