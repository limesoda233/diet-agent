# 阶段 006 验收记录（2026-10-05）

## 本轮结论

PowerShell 两阶段入口、Java 导出契约、Python Ragas CLI 和离线测试已完成验证。真实回答导出与 Judge baseline **未运行**：当前会话没有 `RAGAS_LLM_API_KEY`、`RAGAS_EMBEDDING_API_KEY`，且不存在可复用的 `target/evaluation/recipe/ragas-input.jsonl`。没有 Ragas 指标均值、有效值数或失败值数可报告，不能以此前的 Java 检索分数代替。需要按 [菜谱 RAG 运行手册](../004-recipe-rag/runbook.md) 配好服务与凭据后执行真实命令。

## 固定输入与环境

| 项目 | 固定值或本轮状态 |
| --- | --- |
| HowToCook | `1.6.0` / `a0b14bd388ee996943173679c3a7c9db7da04a6c`；本机目录存在，未在本轮重新生成回答 |
| v2 dataset fingerprint | `f59d67bece51a61cca10f2f70e0424f0ca42f4991003a30d59fbea1fc31de2f6` |
| `v2/queries.jsonl` SHA-256 | `59948cd361b890eba6cbb0e7cd71ba6520bd3593ce8c72f32cb248c68800bb59` |
| `v2/answers.jsonl` SHA-256 | `8d591526f69b6bd4e35f4a42d6ad32b58bf203fdda6b667aa625a7c7e02552ef` |
| `evals/recipe-ragas/uv.lock` SHA-256 | `f5bca0563ea9bf2bf10778cbe8869e63e24b5d1d0017beee772c1e1110a00365` |
| 版本 | Java 21.0.12、Maven 3.8.8、Spring Boot 3.3.13、uv 0.10.11、锁定 Ragas 0.4.3 |
| v2 样本 | 90 条：dev 60、test 30；锁定 test 为 `ANSWER` 24、`REFUSE_OUT_OF_CORPUS` 4、`REFUSE_UNSUPPORTED` 2 |
| 本轮 Ragas 导出 | 未生成；test 语义指标候选上限为 24，实际 `eligible_count` 须以真实生成结果为准 |

v1 数据集及既有 v2 检索 baseline 保持原样；本轮没有修改 qrels，也没有按锁定 test 调参。此前 v2 Java 检索结果见 [evaluation-v2-acceptance.md](../004-recipe-rag/evaluation-v2-acceptance.md)，它们不是 Ragas Judge 分数。

## 已运行的命令与结果

```text
Invoke-Pester -Script <临时 runner 测试文件> -PassThru
4 passed, 0 failed（缺输入、路径冲突、uv sync 失败、正常串行路径）

mvn test
Tests run: 190, Failures: 0, Errors: 0, Skipped: 8; BUILD SUCCESS

mvn -DskipTests package
BUILD SUCCESS

uv lock --check --project evals/recipe-ragas
Resolved 105 packages

uv sync --frozen --project evals/recipe-ragas
Checked 101 packages

uv run --project evals/recipe-ragas pytest evals/recipe-ragas/tests -q
92 passed
```

八项跳过包括显式 opt-in 的真实集成评测。runner 测试用临时 PowerShell 文件和本地进程替身，仅验证编排与失败路径，不调用真实 Judge。Python 测试使用 fake scorer，能够验证 schema、报告聚合、部分失败与全失败行为。

## 在线基线待执行

具备主聊天模型、Ollama、Qdrant、固定语料和 Judge/embedding 配置后运行：

```powershell
./scripts/evaluate-recipe-ragas.ps1 -CorpusPath E:/Mycode/HowToCook
```

执行后在 `target/evaluation/recipe/` 保留 `report.json`、`ragas-input.jsonl`、`ragas-report.json` 和 `ragas-cases.csv`。验收时核对输入 `dataset_hash` 与上表 v2 fingerprint 相同、30 条锁定 test 均在、`eligible_count` 排除 OOD 与 unsupported 拒答、输入与 Java 报告 SHA-256、语料 commit、Judge/embedding 模型名及 `same_model_judge`。三个语义指标须分别记录 `mean`、`valid_count`、`failure_count`；允许 `partial` 并逐项解释错误，零有效值必须失败。报告不得包含密钥。若生成模型和 Judge 相同，该分数仅为同模型自动评测。真实基线运行后填写实际数值，不改动锁定 qrels。

| Ragas 指标 | mean | valid_count | failure_count |
| --- | --- | --- | --- |
| faithfulness | 未运行 | 未运行 | 未运行 |
| answer_relevancy | 未运行 | 未运行 | 未运行 |
| context_utilization | 未运行 | 未运行 | 未运行 |

## 006 验收状态

| 标准 | 状态 |
| --- | --- |
| 1–5：RAG/视频编排、六种状态、前端卡片 | 离线 Java 契约测试通过；真实 `video-mcp` 端到端演示不在本轮验证范围 |
| 6：v2 最终 evidence JSONL | exporter 契约测试通过；真实 Java 导出待运行 |
| 7：fake Judge 与真实 baseline | fake/offline 测试通过；真实 baseline 待凭据与服务 |
| 8：语义、确定性、拒答指标分别报告 | 离线报告结构通过测试；真实数值待运行 |
| 9：Java/Python 验证和锁文件 | 本轮通过，命令及计数如上 |
