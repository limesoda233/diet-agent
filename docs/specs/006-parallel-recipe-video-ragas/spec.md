# 006 菜谱 RAG 与视频 MCP 并行编排及 Ragas 评测

状态：2026-10-04 设计已由用户确认，等待书面规格复核。

## 背景

公共菜谱问答当前使用固定 HowToCook 语料，通过 BM25 与 dense 并行检索、RRF 融合和父块展开生成带引用的回答。哔哩哔哩视频 MCP 已能返回结构化视频卡片，但当前视频搜索位于菜谱路由之前，两个入口互斥：命中视频关键词时只返回视频，普通菜谱查询不会同时搜索视频。

现有离线评测能够计算 Recall、MRR、nDCG、拒答和延迟指标，但回答质量仍主要依赖规则指标，缺少针对忠实度、回答相关性和上下文利用率的 LLM Judge 评测。

本阶段不引入 MinerU。固定 HowToCook 语料已经是结构化 Markdown，且个人菜谱上传不在当前范围内，文档解析暂时没有自然的产品入口。

## 目标

1. 菜谱查询从同一个标准化查询并行启动公共菜谱 RAG 与哔哩哔哩视频搜索。
2. 任一分支失败、超时、拒答或返回空结果时，另一分支仍能独立完成响应。
3. 菜谱有可信证据时返回文字回答、证据引用和视频卡片；菜谱无可信证据但视频可用时只返回说明文字和视频卡片。
4. 视频元数据不得作为文字菜谱的事实证据，不得据此生成食材、步骤、用量或时间。
5. 引入独立 Python Ragas 离线评测工具，补充 Faithfulness、Response Relevancy 和无参考上下文利用率指标。
6. 保留 Java 现有确定性评测，并能用同一数据集 fingerprint 关联 Java 与 Ragas 报告。

## 非目标

- 不接入 MinerU，不增加 PDF、Word 或个人菜谱上传能力。
- 不下载视频，不解析字幕、评论、弹幕或视频正文。
- 不使用视频搜索结果扩写 RAG 回答。
- 不把 Ragas 放入线上请求链路，也不在 Spring Boot 进程中嵌入 Python。
- 不在本阶段新增完整人工参考答案，不启用依赖完整 reference answer 的 Factual Correctness 或 LLM Context Recall。
- 不根据锁定 test 的 Ragas 或检索结果修改 qrels、查询或预期答案。
- 不在第一版把 LLM Judge 分数设为阻断 CI 的质量门槛。

## 规格优先级

本规格在菜谱查询的视频调用行为上覆盖 `docs/specs/005-bilibili-video-mcp/spec.md` 的第 6 条功能边界。视频 MCP 仍默认关闭；只有启用 `video-mcp` profile 的部署才执行视频分支。未启用、初始化失败或运行超时时，菜谱 RAG 必须正常工作。

餐食推荐仍只使用 MySQL。本规格只改变 `RECIPE_QUERY` 的编排，不得让餐食推荐依赖 Lucene、Qdrant、embedding 或视频 MCP。

## 路由与查询标准化

### 菜谱查询

命中本地菜谱规则或被 Intent Agent 识别为 `RECIPE_QUERY` 后，统一进入菜谱编排器。编排器先构造一次 `NormalizedRecipeQuery`，至少包含：

- 用户原始文本；
- 用于 RAG 的检索文本；
- 用于视频搜索的关键词；
- 可选的平台和结果数量；
- 查询来源，包括本地规则或意图模型兜底。

例如“宫保鸡丁咋做啊”标准化为：

```text
recipeQuery = 宫保鸡丁咋做啊
videoQuery  = 宫保鸡丁 做法 教程
```

视频查询必须直接从标准化结果生成，不能等待 RAG 返回菜名后再生成，保证两个分支没有数据依赖。

### 纯视频请求

明确表达“只要视频、只看视频、不用文字菜谱”的请求继续走纯视频分支。仅包含“找一个宫保鸡丁教学视频”且没有索要文字做法的请求也视为纯视频请求。

同时询问做法和视频的请求属于菜谱查询，执行并行编排。

## 并行执行模型

菜谱编排器同时启动两个独立分支：

1. `RecipeBranch` 调用现有 `RecipeQueryService`，返回有证据回答、拒答或失败。
2. `VideoBranch` 调用现有 `CookingVideoSearchService`，返回视频列表、空结果、超时或失败。

两个分支使用显式的 `BranchOutcome<T>` 表达结果，错误不得通过 `Mono.zip` 取消另一个分支。同步或阻塞调用不得占用 Reactor 非阻塞线程，应在受控的 bounded elastic 调度器上执行；不得使用无边界线程池或 `ForkJoinPool.commonPool()`。

视频分支沿用 `VideoMcpProperties.timeout`，默认 3 秒。视频超时只产生空视频结果和 Trace 降级原因。菜谱分支沿用现有检索和生成超时。组合响应的目标耗时是两分支耗时的最大值，而不是两者之和。

## 组合结果

| 菜谱分支 | 视频分支 | 用户响应 |
| --- | --- | --- |
| 有可信证据 | 有结果 | 文字菜谱、引用和视频卡片 |
| 有可信证据 | 空、失败或超时 | 文字菜谱和引用 |
| 证据不足或语料无结果 | 有结果 | 说明公共语料暂无可信菜谱，只返回视频卡片 |
| 证据不足或语料无结果 | 空、失败或超时 | 说明未找到可信菜谱或教学视频 |
| 运行异常 | 有结果 | 记录 RAG 降级，只返回视频卡片 |
| 运行异常 | 空、失败或超时 | 返回统一的暂不可用说明，不暴露内部异常 |

RAG 有可信证据时沿用普通回答类型，并同时填充 `videoBlocks`。只有视频可用时使用视频响应类型。`speechText` 不得拼接视频 URL，前端继续通过结构化 `videoBlocks` 渲染可点击卡片。

视频标题、UP 主、时长和 URL 只能用于卡片展示。文字回答的每项事实仍必须由本轮 RAG evidence 支持，并保持现有引用格式。

## Trace 与运行指标

每次并行菜谱查询至少记录：

```text
RECIPE_QUERY_NORMALIZED
RECIPE_RAG_STARTED
VIDEO_MCP_STARTED
RECIPE_RAG_COMPLETED | RECIPE_RAG_REFUSED | RECIPE_RAG_FAILED
VIDEO_MCP_COMPLETED | VIDEO_MCP_EMPTY | VIDEO_MCP_TIMEOUT | VIDEO_MCP_FAILED
RECIPE_RESPONSE_COMPOSED
```

组合事件记录两个分支的状态、耗时、结果数量、是否降级以及最终响应类型，不记录 API Key、完整系统提示或不必要的原始外部响应。

运行指标至少包括：

- 组合请求 P50/P95；
- RAG 与视频分支各自 P50/P95；
- 视频结果覆盖率、空结果率、失败率和超时率；
- RAG 拒答但视频成功的降级率；
- 两个分支均不可用的比例；
- 前端具备数据采集能力后再增加视频卡片点击率。

## Ragas 架构

Ragas 使用独立 Python CLI，不作为 HTTP 微服务，也不由线上 Spring Boot 请求调用：

```text
锁定 v2 数据集
    -> Java 运行真实检索与回答生成
    -> 导出 ragas-input.jsonl
    -> Python Ragas CLI 调用 Judge
    -> ragas-report.json + ragas-cases.csv
```

Java 是待评系统，负责生成真实回答和最终 evidence。Python 只负责读取已冻结样本并评分。Maven 默认测试不得安装 Python 包、启动 Judge 或产生外部模型费用。

Python 项目位于：

```text
evals/recipe-ragas/
  pyproject.toml
  uv.lock
  src/recipe_ragas/
  tests/
```

依赖固定 `ragas==0.4.3`，通过 `uv.lock` 锁定完整依赖。升级 Ragas 时必须显式更新版本、锁文件和兼容测试。

## Ragas 输入契约

`target/evaluation/recipe/ragas-input.jsonl` 每行至少包含：

```json
{
  "query_id": "rq-032",
  "dataset_version": "v2",
  "dataset_hash": "sha256",
  "split": "test",
  "slice": "exact_name",
  "user_input": "五花肉怎么红烧",
  "response": "实际生成的回答",
  "retrieved_contexts": ["实际传给回答模型的证据"],
  "retrieved_context_ids": ["recipe-id"],
  "reference_context_ids": ["gold-recipe-id"],
  "answer_expectation": "ANSWER",
  "refused": false,
  "citations": ["S1"],
  "retrieval_latency_ms": 27,
  "generation_latency_ms": 1850
}
```

`retrieved_contexts` 必须是 evidence assembler 处理后真正传给生成模型的文本，顺序与模型输入一致。不得用初始召回列表替代最终 evidence。

每次导出先写临时文件，全部样本成功后再原子替换正式 JSONL，避免 Judge 读取半份数据。失败样本必须带错误状态或使导出命令失败，不能静默丢失。

## 第一版 Ragas 指标

第一版仅对 `answer_expectation=ANSWER` 且成功生成回答的样本计算语义指标：

1. `Faithfulness`：回答中的陈述是否能由 `retrieved_contexts` 支持。
2. `Response Relevancy`：回答是否针对 `user_input`，使用配置的 embedding 模型。
3. `ContextUtilization`：在没有完整参考答案时，根据用户问题、实际回答和 `retrieved_contexts` 判断上下文排序和内容是否对回答有用。

OOD、near-OOD 和 `REFUSE_UNSUPPORTED` 样本不混入上述平均分，继续使用 Java 的拒答准确率、expected-empty accuracy、near-OOD false-positive rate、引用覆盖率和禁止事实检查。报告必须分别展示语义回答指标与拒答指标。

现有 Recall、MRR、nDCG、ID Gold 和延迟指标继续由 Java 计算。Ragas 不重复实现这些确定性指标。

## Judge 与 embedding 配置

Ragas 使用可配置的 OpenAI-compatible 接口：

```text
RAGAS_LLM_BASE_URL
RAGAS_LLM_API_KEY
RAGAS_LLM_MODEL
RAGAS_EMBEDDING_BASE_URL
RAGAS_EMBEDDING_API_KEY
RAGAS_EMBEDDING_MODEL
```

所有配置通过环境变量传入，密钥不得写入仓库、命令输出或报告。CLI 缺少必要配置时应在发起请求前失败，并指出缺少的变量。

可以复用现有 DeepSeek 兼容接口，也可以连接 Ollama。报告必须记录 Judge 提供商、base URL 的脱敏主机、模型名、embedding 模型、Ragas 版本、数据集 fingerprint、语料 commit 和执行时间。同一模型既生成答案又担任 Judge 时，报告必须标记 `same_model_judge=true`，不得把该分数描述为独立人工评审。

## Ragas 输出

`ragas-report.json` 包含：

- 完整运行元数据；
- 每项指标的均值、有效样本数、失败样本数；
- 按 slice 聚合的指标；
- Judge 调用失败和解析失败统计；
- Java 确定性报告的文件路径和 hash；
- 输入 JSONL 的 hash。
- `ragas-cases.csv` 的 SHA-256（`cases_csv_sha256`），以及实际拒答数 `excluded_refusal_count` 与语义评分排除数 `excluded_semantic_count`。

`ragas-cases.csv` 保存每条样本的指标和简短失败原因，便于定位 bad case。报告先替换 CSV，再替换作为完成标记的 JSON；读取报告时必须核对当前 CSV 的 SHA-256 与 JSON 中的值。第二次替换失败会留下旧 JSON 与新 CSV，此时该报告不完整，不能用于汇总或验收。部分 Judge 调用失败时报告状态为 `partial`；全部失败时命令返回非零退出码。

第一版只建立 baseline，不使用锁定 test 分数调参，也不设置 CI 阻断阈值。后续阈值只能先在 dev 集确定，再对锁定 test 运行一次验收。

## 执行方式

提供一个 PowerShell 入口串联两个阶段，但保持进程和依赖隔离：

```text
scripts/evaluate-recipe-ragas.ps1
  1. 调用 opt-in Maven 测试导出 ragas-input.jsonl
  2. 使用 uv --project evals/recipe-ragas 运行 Python CLI
  3. 输出报告路径和摘要
```

脚本不得自动下载 Judge 模型、写入 API Key 或修改锁定评测集。Java 导出阶段和 Python 评分阶段也必须能单独执行，便于复用已有输入而不重复支付生成或 Judge 成本。

## 测试

### Java

- 菜谱问题会同时启动 RAG 和视频分支；
- 两个 fake 分支通过时间断言证明并行执行，不能只断言两者都被调用；
- 覆盖组合结果表中的全部状态；
- 视频超时、失败和空结果不会破坏 RAG 回答；
- RAG 拒答或失败且视频成功时返回视频卡片；
- 纯视频请求不调用 RAG；
- `video-mcp` 未启用时保持纯 RAG 行为；
- Ragas JSONL 字段、最终 evidence 顺序、hash 和原子写入可验证；
- 默认 `mvn test` 不调用真实 MCP、Ollama、Qdrant、Judge 或 Python。

### Python

- JSONL schema 校验和缺失字段错误；
- 只选择 `ANSWER` 且生成成功的样本；
- 使用 fake metric/Judge 验证聚合和按 slice 统计；
- 单条失败产生 `partial` 报告；
- 全部失败返回非零退出码；
- 报告不泄露 API Key；
- 固定样本验证 JSON 和 CSV 输出稳定。

### 可选真实验收

- 启用 `recipe-rag,video-mcp`，验证同一菜谱问题返回引用和视频卡片；
- 关闭或中断 MCP，验证文字菜谱仍能返回；
- 模拟 RAG 无证据，验证只返回视频；
- 使用已配置 Judge 运行一次完整 Ragas baseline，并保存报告元数据；
- 真实运行完成后不得据此修改锁定 test qrels。

## 验收标准

1. “宫保鸡丁咋做啊”在启用视频 MCP 时同时触发 RAG 与视频搜索。
2. 并行测试证明总编排时间接近较慢分支，而不是两个分支耗时之和。
3. 六种组合状态均返回稳定响应，单分支故障不产生整体 500。
4. 没有 RAG 证据时不生成文字做法，视频元数据不进入 evidence。
5. 前端能在同一条菜谱回复下展示视频卡片，并保持安全的新窗口跳转。
6. Java 能导出与 v2 fingerprint 对应、包含最终 evidence 的 Ragas JSONL。
7. Python CLI 能在 fake Judge 下离线测试，并能通过 OpenAI-compatible 配置运行真实 baseline。
8. 报告分别展示 Ragas 语义指标、Java 确定性指标和拒答指标，不混合不可比较样本。
9. `mvn test` 与 `mvn -DskipTests package` 通过；Python 单元测试和锁文件校验通过。

## 风险与约束

- 第三方 Bilibili MCP 使用非官方公开搜索接口，只适合本地练习和演示；其失败必须可降级。
- LLM Judge 具有随机性和模型偏差；模型、版本、数据集 hash 与失败率必须进入报告。
- 同模型自评只能作为自动化 baseline，不能等同于人工或独立模型评审。
- Ragas 版本变化较快，必须固定版本并通过适配层隔离 API 变化。
- 视频点击率需要前端埋点和长期流量，第一版只预留指标定义，不伪造线上数据。
