# 013 菜谱相关性筛选验收

日期：2026-10-19。

## 交付状态

已完成可配置的 Cross-Encoder HTTP 适配、生成前证据准入、结构化故障状态和共享候选的三组评测入口。默认关闭，保留 identity。用户尚无 Rerank Key，本次只完成本地 HTTP fixture 与离线统计/回放验证；真实模型相关性效果、供应商连通性和阈值校准均未验证。

## 验收证据

| ID | 验证 |
| --- | --- |
| REL-01 | `RecipeRagConfigurationTest`：默认无外部客户端；显式开启缺 Key、过滤缺阈值时失败；只重排可不配置阈值；客户端构建不连接网络 |
| REL-02 | `HttpRecipeRerankerTest`：本地临时 HTTP fixture 验证请求、索引映射、稳定排序、阈值等于边界、全部筛除、原 RRF 分数保留、候选/文本预算 |
| REL-03 | 同上：401/429/500、超时、重复/越界/漏项索引、非法或非有限评分、重复 JSON 字段及尾随 JSON 均不放行；错误正文中的模拟密钥不进入输出 |
| REL-04 | `RecipeQueryServiceTest`：FILTERED/UNAVAILABLE 不调用生成器，原始完整问题用于评分；`RecipeVideoCoordinatorTest`：评分故障保留成功视频分支 |
| REL-05 | `RecipeRerankEvaluationReportTest`、`RecipeRerankReplayTest`：共享候选派生三组；分母为空返回 null；回放写新报告并保留来源哈希；真实入口默认跳过 |
| REL-06 | 测试集要求显式冻结、阈值与 dev 校准标识；test 回放不能改变记录的阈值；数据集指纹和 schema 检查；检索失败、单支降级、评分失败分别报告并排除出正常质量分母；未修改固定 query/qrels |
| REL-07 | `mvn test`：413 项，0 failures，0 errors，13 skipped；`mvn -DskipTests package`：BUILD SUCCESS；`git diff --check`：通过 |

本次开发前基线为 377 项测试、0 失败、11 跳过；新增的两个跳过项为真实评分采集和显式文件回放入口。普通验证中，单元测试直接调用离线回放逻辑，不触发真实依赖。测试不证明生产服务已经开启。

## 审查与修复

独立代码审查发现：原评测只记录双分支全失败，单支失败但另一支为空可能误算 OOD 拒答成功。已增加实际检索模式与受控降级原因，独立统计 `retrievalDegradations`，从 paired quality 中排除，阻止报告在此情况下成为 `comparisonReady`；新增回归测试并通过复核。

## 限制与后续

- 模拟分数只验证接入正确性；尚不能宣称原 4 条空预期查询已被模型过滤，也不能承诺零误拒。
- 获得 Key 后，先用 dev 采集与回放校准，再冻结模型、候选预算、文本预算和阈值；test 不用于调参。历史 test 已被查看，新无偏结论建议补充独立保留集。
- 保留完整的模型与文本指纹不代表自动验证所有参数冻结；执行者仍须记录 dev 决策。Git revision 不包含未提交改动。
- 本阶段不新增口语食材硬约束解析，不把相关性评分当事实核验，也不改 MySQL 推荐或个人做法。
- 使用指南为 [菜谱相关性筛选](../../guides/recipe-relevance.md)，实施计划仅留作开发记录。
