# 意图识别评测说明

## 当前基线

评测时间：2026-08-23（Asia/Shanghai）

历史基线模型：`deepseek-chat`

数据集：`src/test/resources/evaluation/intent-eval.jsonl`

| 指标 | 结果 |
| --- | ---: |
| 样本数 | 72 |
| 原始 IntentAgent Accuracy | 97.22%（70/72） |
| 原始 IntentAgent Macro-F1 | 97.09% |
| 最终路由 Accuracy | 97.22%（70/72） |
| 最终路由 Macro-F1 | 97.22% |
| 最终路由 Accuracy 95% Wilson CI | [90.43%, 99.23%] |
| 疑似 fallback | 0 |
| 单条延迟 P50 / P95 | 892 ms / 1227 ms |

最终路由的分标签结果：

| 意图 | 样本数 | Precision | Recall | F1 |
| --- | ---: | ---: | ---: | ---: |
| `MEAL_RECOMMENDATION` | 12 | 91.67% | 91.67% | 91.67% |
| `CLARIFY_NEEDED` | 12 | 92.31% | 100.00% | 96.00% |
| `MEAL_ADJUST` | 12 | 100.00% | 100.00% | 100.00% |
| `MEAL_PLAN` | 12 | 100.00% | 91.67% | 95.65% |
| `HEALTH_RISK` | 12 | 100.00% | 100.00% | 100.00% |
| `OTHER` | 12 | 100.00% | 100.00% | 100.00% |

两条错误分别是：

- `午间休息该整点啥？`：期望 `MEAL_RECOMMENDATION`，实际 `CLARIFY_NEEDED`。
- `早餐和午餐一起推荐，不要只给一顿`：期望 `MEAL_PLAN`，实际 `MEAL_RECOMMENDATION`。

这说明当前主要风险不是健康风险或上下文调整，而是 `MEAL_RECOMMENDATION`、`CLARIFY_NEEDED`、`MEAL_PLAN` 三个相邻意图的边界。

> 该结果是均衡、人工构造离线集上的单次点估计，不等于线上真实准确率。数据量较小，且尚未经过双人独立标注；上线口径应补充匿名真实请求、按真实流量分布加权，并至少重复运行三次。

## 评测集的设计考量

### 1. 标签以最终业务路由为准

标签集合直接来自 `Intent` 枚举，共 6 类。评测同时保存两套金标：

- `expectedAgentIntent`：LLM 意图识别层应该输出什么。
- `expectedFinalIntent`：经过 `IntentReviseService` 后，状态机最终应该走哪条路。

例如首轮用户说“换个清淡点的，我是第一次问”，原始语义形态是 `MEAL_ADJUST`，但没有历史推荐可调整，最终路由应降级为 `MEAL_RECOMMENDATION`。将两层分开后，才能判断错误来自模型还是后处理规则。

### 2. 标签均衡，但切片不只看简单样本

最终标签每类各 12 条，避免多数类掩盖少数类。样本覆盖：

- 直接表达和口语改写；
- 极短输入、噪声和提示注入；
- 否定表达与相邻意图边界；
- 饮食相关但超出系统能力边界的问题；
- 健康风险、特殊人群和极端节食；
- 带最近对话及“已有推荐结果”状态的多轮调整。

除了总体 Accuracy，还必须看 Macro-F1、每类 Precision/Recall/F1、切片准确率和混淆样本。安全类尤其看 Recall，不能只看总准确率。

### 3. 避免 prompt 泄漏和调参污染

金标集不直接复用 `intent.txt` 中的示例原句，而使用语义等价的新表达。发现错误后，应先判断是模型错误还是金标歧义；不能为了提高测试集分数直接修改标签。若需要调 prompt，应在独立 dev 集上调优，冻结后只在 test 集上验收。

### 4. 多轮意图必须携带显式状态

`MEAL_ADJUST` 依赖最近对话和 `lastRecommendations`，不能只用单句分类集。每条上下文样本都显式保存 history 与是否已有推荐结果，评测时按生产代码构造 `ConversationTurn` 和 `SessionState`。

### 5. 固定可复现条件

报告要记录数据集版本、prompt 版本、模型名、运行时间、fallback 数和延迟。LLM 存在非确定性，正式回归建议同一版本运行 3 次，报告均值、标准差及最差一次，而不是挑最好的一次。

## 如何运行

普通测试会校验评测集能否解析、ID 是否唯一、标签是否齐全且均衡，不会调用远程模型：

```powershell
mvn test
```

真实模型评测始终需要配置 `DEEPSEEK_API_KEY`，因为它也是 Jev 低置信度和故障时的回退模型。要评测 Jev 主链路，再配置 `JEV_API_KEY`。PowerShell 中执行：

```powershell
$env:JEV_API_KEY = "你的 Jev API Key"
mvn "-Dintent.eval.live=true" "-Dtest=IntentAgentLiveEvaluationTest" test
```

Bash 中执行：

```bash
export JEV_API_KEY="你的 Jev API Key"
mvn -Dintent.eval.live=true -Dtest=IntentAgentLiveEvaluationTest test
```

若要复跑原 DeepSeek 基线作对照，可临时设置 `JEV_INTENT_ENABLED=false`。报告的 `model` 字段会记录实际使用的 Jev 模型和 DeepSeek 回退模型。

评测复用生产的 `IntentAgentService` 和 `IntentReviseService`，但用固定槽位字典替代数据库查询，避免数据库状态导致同一数据集不可复现。输出文件位于：

- `target/evaluation/intent-evaluation-report.json`：逐样本预测、置信度、延迟、混淆矩阵和切片指标。
- `target/evaluation/intent-evaluation-report.md`：便于阅读的摘要和错误样本。

真实模型测试默认要求最终 Accuracy 和 `HEALTH_RISK` Recall 都不低于 90%。如需调整回归门槛，可传入 `-Dintent.eval.min-final-accuracy=...` 和 `-Dintent.eval.min-health-risk-recall=...`；门槛应在看本轮结果之前预先确定。

## 标准评测步骤

1. 冻结标签定义、prompt 版本、模型和槽位字典。
2. 从匿名真实 Trace 分层抽样，另补充边界、风险和对抗样本；对文本去重并排除 prompt 示例。
3. 两名标注者独立标注，使用标签手册解决分歧，并记录一致性（如 Cohen's kappa）。
4. 按用户或会话切分 dev/test，避免同一对话或近义改写跨集合泄漏。
5. 运行静态数据校验，再执行真实模型评测；正式基线重复 3 次。
6. 查看总体 Accuracy、Macro-F1、每类指标、混淆矩阵、关键切片、安全类 Recall、fallback 和延迟。
7. 在 dev 集修改 prompt 或规则，在冻结 test 集验收；保存报告并与上一基线比较。
8. 上线后监控按真实分布加权的指标和漂移，定期将高置信错误、低置信样本及用户纠正回流标注。

## 还需要建立哪些评测集

| 优先级 | 模块 | 为什么需要独立评测 | 建议核心指标 |
| --- | --- | --- | --- |
| P0 | 健康风险意图 + `RiskGuardService` | 错放比普通分类错误严重，且关键词规则容易漏掉隐晦表达、误拦正常减脂请求 | 风险 Recall、正常请求误杀率、分风险类型 Recall |
| P0 | 槽位抽取、归一化与多轮合并 | 意图正确但餐次/口味/健康目标错，推荐仍会跑偏 | 每槽位 Precision/Recall/F1、整句 Exact Match、越界标签率、跨轮保留/覆盖准确率 |
| P0 | 检索与重排 | 当前按 7 个维度等权平均，离线需要验证 Top-K 是否真的相关 | Recall@K、MRR、NDCG@K、重复率、`excludeMealIds` 命中率、PERSONAL 数据隔离正确率 |
| P1 | 澄清决策与问题质量 | 规则决定 ASK/READY，LLM 决定问法；两者错误类型不同 | ASK/READY Accuracy、missingSlots Exact Match、一次澄清解决率、无效重复追问率 |
| P1 | 多轮状态机端到端 | 单模块正确不代表整段会话成功，尤其是调整、槽位继承和三餐规划 | Task Success、平均澄清轮数、槽位保持率、调整后不重复率、状态跳转正确率 |
| P1 | 推荐回复 grounding 与质量 | 回复必须只引用候选餐食，理由要与槽位和候选属性一致 | 候选外餐食率、理由事实一致率、免责声明覆盖率、人工偏好胜率 |
| P2 | 三餐/多日规划 | 当前测试只覆盖基本分餐检索，尚未评估多样性和重复 | 餐次覆盖率、跨餐重复率、目标满足率、计划完整率 |
| P2 | LLM-as-Judge 校准 | 项目已有 Judge，但 Judge 自身也可能漂移或偏置 | 与人工评分相关性、同样本重复一致率、版本间稳定性 |

推荐下一步先做“健康风险 + 槽位抽取”两套金标集；前者控制严重错误，后者直接决定检索质量。
