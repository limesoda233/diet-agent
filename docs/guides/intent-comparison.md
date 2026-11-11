# 原 IntentAgent 与 Jev 对照评测

## 测什么

这次直接评测意图识别和 `IntentReviseService`，不执行入口菜谱/视频规则、MySQL 推荐、RAG、视频 MCP，也不往数据库写 Trace。这样才能观察识别器自身的差异，不能把这里的准确率称为完整聊天路由准确率。

| 路径 | 实际行为 | 用途 |
| --- | --- | --- |
| `BASELINE` | Jev 关闭，调用生产 IntentAgentService；失败时仍保留其 Java 兜底 | 原链路基线，兜底来源可见 |
| `JEV_RAW` | 从同一次 `JEV_CHAIN` 调用中截取接受阈值判断前的 Jev 结果 | 观察 Jev 本身，低分不会被丢掉，失败为无预测 |
| `JEV_CHAIN` | Jev 达到阈值则采纳，否则实际调用原 Agent 回退 | 衡量接入后的效果与额外耗时 |

同一对输入使用相同 sessionId、用户输入、固定槽位字典、空 knownSlots、显式 history 和“是否有上次推荐”的状态。两条实际链路按样本交替先后顺序；不会拿 BASELINE 的回答模拟回退。RAW 和 CHAIN 共享 Jev 调用，三列的 Token/调用数不能相加。

比较对象是当前接入方案：普通 Agent 的 prompt 与 Jev criteria 各自冻结，但表达形式不同。因此不能把所有差异都归因于模型本身；报告不宣称同提示下的纯模型能力排名。

## 数据与标注口径

- 主集：`src/test/resources/evaluation/intent-eval.jsonl`，当前 **84 条、7 类，每类 12 条**。旧文档的 72 条成绩只描述历史版本。本次不改主集标签。
- 补充诊断集：`src/test/resources/evaluation/intent-challenge-v1.jsonl`，16 条，重点覆盖库存口语、非烹饪关键词、多轮指代、用户纠正。含已知 bad trace，属于开发诊断集，**不是未知测试集**；由本次单人初稿标注，尚未做双人复核。
- `expectedAgentIntent` 检查识别结果，`expectedFinalIntent` 检查意图修正后的结果。后处理可能提升成绩，所以两层分别报告。
- 主集/补充集分别计分，保留各 slice。字段 `knownSlots` 在本批固定为空；不据此宣称跨轮槽位合并效果。
- 固定数据、prompt、Jev criteria、修正规则和槽位字典的 SHA-256 都保存在报告。`deepseek-chat` / `jev-latest` 是请求模型名，供应商可能更新别名，跨时间重跑仍可能变化。

## 配置与密钥

只有显式启用的真实评测读取 `src/.env`，普通 `mvn test` 不读取该文件。可用 `-Dintent.compare.env-file=绝对路径` 指定其他位置。文件内容不会执行命令或展开变量，不打印、不保存 Key；只导入如下白名单，进程环境变量优先：

```dotenv
DEEPSEEK_API_KEY=本地填写
JEV_API_KEY=本地填写
# 可选
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_LIGHT_MODEL=deepseek-chat
JEV_ENDPOINT=https://api.typesafe.ai/v1/systemone
JEV_MODEL=jev-latest
JEV_TIMEOUT=3s
JEV_INTENT_CONFIDENCE_THRESHOLD=0.70
JEV_SLOT_PROBABILITY_THRESHOLD=0.70
```

DB_PASSWORD 等配置不会导入。`.env` 已被 Git 忽略；不要把真实 Key 放进命令行或文档。

## 运行

如果电脑有系统代理，需要在 JVM **启动时**启用 `-Djava.net.useSystemProxies=true`。Java 默认关闭该选项，而且只在启动时读取，见 [Java 21 网络配置](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/doc-files/net-properties.html)。本机已验证：Java 默认连接得到 HTTP 451；启用系统代理后，同一 Java 评测成功获得 Jev 决策。不能据此断言服务商具体地区政策。

以下 Maven 命令均通过 Surefire `argLine` 给测试 JVM 传入这个选项。它不会永久修改系统代理；报告记录该开关，两条路径在同一 JVM 下比较。

在项目根目录执行。先做一条连通性检查，不据此下准确率结论：

```powershell
mvn "-DargLine=-Djava.net.useSystemProxies=true" "-Dintent.compare.live=true" "-Dintent.compare.limit=1" "-Dintent.compare.challenge=false" "-Dtest=IntentComparisonLiveEvaluationTest" test
```

首轮完整对照，默认 84 主集 + 16 诊断集，各一轮：

```powershell
mvn "-DargLine=-Djava.net.useSystemProxies=true" "-Dintent.compare.live=true" "-Dtest=IntentComparisonLiveEvaluationTest" test
```

冻结配置后重复三轮，不挑最好的一轮：

```powershell
mvn "-DargLine=-Djava.net.useSystemProxies=true" "-Dintent.compare.live=true" "-Dintent.compare.repeats=3" "-Dtest=IntentComparisonLiveEvaluationTest" test
```

如果 Jev 无法访问，可以先跑原链路；这不是完整对照：

```powershell
mvn "-DargLine=-Djava.net.useSystemProxies=true" "-Dintent.compare.live=true" "-Dintent.compare.baseline-only=true" "-Dtest=IntentComparisonLiveEvaluationTest" test
```

在 IDEA 可直接运行 `IntentComparisonLiveEvaluationTest`，为 **JUnit 配置**加 VM options `-Dintent.compare.live=true -Djava.net.useSystemProxies=true`，Working directory 设为项目根目录。由于工具自行读取 `src/.env`，不需要复制应用启动配置的 Key。想只测连通性，加 `-Dintent.compare.limit=1 -Dintent.compare.challenge=false`。

应用的 Spring Boot 启动配置也需要在 **VM options** 加 `-Djava.net.useSystemProxies=true` 后重启；不是 Program arguments，也不是 yml 中的 Spring 属性。不把该开关写死在代码里，以免没有代理的环境改变网络路径。没有系统代理时，需要另行配置受信任的网络出口。

评测对原 Agent 加了 30 秒观测上限（`intent.compare.agent-timeout-seconds` 可改），Jev 使用现有 3 秒默认值；两者写入报告。这是实验条件，不改变生产超时。阈值和超时必须在看结果前确定。

## 报告如何读

每次运行单独创建 `target/evaluation/intent-comparison/<时间戳与唯一ID>/report.json` 和 `report.md`，每完成一个样本保存一次检查点。`mode` 区分完整对照和 baseline_only；`limitedRun` 标识抽样；`complete` 标识是否完成本次计划；`comparisonReady` 为 false 时不能称为完成对照。

报告包括原始意图/修正后 Accuracy、修正后 Macro-F1、每类 Precision/Recall/F1、混淆矩阵、切片、配对赢/输、P50/P95、回退原因与决策来源、已知 usage 和高置信错误/ECE 分箱。重复运行附每轮结果、均值、总体标准差、最差一次。

- 调用失败保留在分母，RAW 无预测不会伪装成 OTHER。BASELINE/CHAIN 的 Java 兜底可有最终预测，但原始模型预测为空，状态为 RULE_FALLBACK。
- `successOnlyAccuracy` 是辅助字段，不能替代包含失败的主指标。Macro-F1 使用本分组有 Gold 支持的类别；有限抽样不能和全量宏平均直接比较。
- 看 CHAIN 的 `sources.JEV / attempts` 得接受率；`fallbackReasons` 给出低分/超时/异常回退次数，除以 attempts 是相应回退率。
- Token 仅在相关模型调用都有 usage 时记录，缺失为 null，不当成零。Jev 客户端现在仅接受完整、非负整数的 input/output usage；缺失/部分/异常 usage 为 null，完整的零值仍是零。此修改只修正用量元数据，不改变意图选择或回退。没有人民币费用估算。
- RAW 耗时是 Jev HTTP 调用和响应解析，排除构造问题与意图修正；BASELINE/CHAIN 耗时包含完整识别与修正。RAW 只用于观察 Jev 分支，实际接入延迟收益比较 BASELINE 与 CHAIN。
- `modelCalls` 观测客户端 transport 发起的 HTTP 尝试，不是 Agent 调用数，也不把同一个 publisher 的重新订阅算成新请求。保持 SDK 默认重试行为，配置写入 metadata；配置 maxAttempts=3 不代表所有 5xx 都实际重试。多个 DeepSeek HTTP 尝试只有最终 usage 时，总用量保守为 null，不推算失败请求成本。
- confidence 来源不同，ECE 只作分箱诊断，不代表已完成校准。模型缺失分数时生产逻辑默认 0.5；Java 固定兜底分数不参与置信度诊断。
- 小样本、单人标注和重复采样存在局限；配对赢/输不是统计显著性证明。
- 三轮主集是84条不同查询的252次观测，不是252条独立查询；置信度与稳定性不能通过扩大重复次数伪装成扩大标注规模。
- 本次评测HTTP建连上限使用Jev timeout（默认3秒），生产JevConfiguration建连上限为2秒；报告反映本次实验条件。网络、连接复用及供应商缓存也会影响延迟，交替顺序只能减小顺序偏差。

401/403/404/451 立即停止本次实验；其他外部失败连续三条停止，保存报告、返回失败，避免将不可用服务的回退结果误写成 Jev 提升。不根据失败修改 Gold、prompt 或放宽阈值。

## 本次真实运行状态

2026-10-11（Asia/Shanghai）首次检查：原 Agent 成功，Jev 返回 `jev_http_451`；非沙箱环境复测仍为 451。用户确认此前应用也一直回退。继续对照发现 Windows HTTP 客户端返回200，Java启用系统代理后也成功。该问题来自当前 Java 网络配置，不能把这两次失败当作 Jev 分类能力差。

已有无系统代理条件下的两次 BASELINE 诊断：主集分别 83/84 和81/84，补充集均16/16；它们不与代理条件下的 Jev 成绩拼成配对实验。完整对照按新的同一代理条件运行，结果以对应 report.json 为准。不要仅看聊天能回答就认为 Jev 已成功启用，需检查 JEV_INTENT_DECISION 与实际采纳来源。
