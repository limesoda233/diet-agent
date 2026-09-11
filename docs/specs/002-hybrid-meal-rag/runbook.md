# 混合餐食检索运行说明

## 默认行为

默认不启用新知识库，沿用旧检索。`rag` profile 显式启用知识库，并默认使用 STRUCTURED。HYBRID 必须另行配置；当前没有选定云 embedding 模型，不能宣称已完成语义效果验收。

新文件 `application-rag.yml` 不覆盖原有 `application.yml`。启用新功能前必须执行迁移；启动时会检查三张新表。没有知识的旧餐食仍可用于普通推荐，但无法通过食材硬限制。

## 迁移和启动

以下命令在仓库根目录运行，数据库沿用本机 Spring 配置；先在开发数据库使用。迁移只执行幂等建表，不执行原始初始化文件的 DROP。

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --diet.retrieval.index-command=migrate"
mvn -o spring-boot:run "-Dspring-boot.run.profiles=rag"
```

本地命令必须带 `spring.main.web-application-type=none`，完成后退出，不启动管理 HTTP 接口。若本机尚未缓存 Maven 依赖，首次运行去掉 `-o`。命令只实例化模型配置，不调用对话 LLM；已有应用模型配置仍适用。

## 云 embedding 与 Qdrant

先启动本地向量库：

```powershell
docker compose -f compose.retrieval-dev.yml up -d
```

使用 Qdrant v1.18.2，端口只绑定 `127.0.0.1:6333`，持久卷属于 `diet-retrieval-dev` 项目。

| 环境变量 | 含义 |
| --- | --- |
| DIET_RETRIEVAL_EMBEDDING_ENDPOINT | 完整 embeddings URL；云服务要求 HTTPS |
| DIET_RETRIEVAL_EMBEDDING_MODEL | 服务商模型名称 |
| DIET_RETRIEVAL_EMBEDDING_APIKEY | 本机环境或密钥管理器中的 Key，不写入代码或聊天 |
| DIET_RETRIEVAL_EMBEDDING_DIMENSIONS | 与模型输出一致的维度 |
| DIET_RETRIEVAL_EMBEDDING_DOCUMENTPREFIX / QUERYPREFIX | 模型要求的文档/查询前缀；默认空 |
| DIET_RETRIEVAL_QDRANT_ENDPOINT | 默认 http://127.0.0.1:6333 |
| DIET_RETRIEVAL_QDRANT_COLLECTION | `diet_` 开头，名称包含模型、维度、模板版本，例如 diet_modelname_1024_v1 |
| DIET_RETRIEVAL_STRATEGY | LEGACY / STRUCTURED / HYBRID |
| DIET_RETRIEVAL_INDEXWORKERENABLED | 是否启用每 30 秒同步；默认 false |

第一适配器使用 `model/input` 请求和 `data[index,embedding]` 响应，支持批量及严格校验。不兼容的供应商需要实现 `EmbeddingClient`。云端处理的文本包括餐食名称、标签、已记录食材与做法，以及检索请求；不发送完整聊天记录。

配置模型后先重建，再启用混合策略：

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.profiles=rag" "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --diet.retrieval.index-command=rebuild"
$env:DIET_RETRIEVAL_STRATEGY='HYBRID'
$env:DIET_RETRIEVAL_INDEXWORKERENABLED='true'
mvn -o spring-boot:run "-Dspring-boot.run.profiles=rag"
```

缺少配置、collection 未就绪或依赖异常时，HYBRID 降级为使用相同硬条件的 STRUCTURED，Trace 会记录原因。MySQL 不可用时明确失败。回滚排序可选择 LEGACY；已经识别的明确限制仍执行，不允许靠回滚绕过约束。

模型或维数变化必须使用新 collection 并重新建立索引。改变输入前缀也应使用新 collection，不能把新旧向量混在一起。ready 只表示一次重建已追平，并不代表语义质量合格。

## 知识数据与约束

个人餐食原 CRUD 接口接受可选 `knowledge`：

```json
{"ingredients":["番茄","鸡蛋"],"description":"用户记录的菜品描述","preparation":"用户记录的做法","sourceType":"USER_PROVIDED","sourceReference":"我的菜单"}
```

更新时不传 `knowledge` 会保留原值；显式传 null 清除。客户端不能冒充 IMPORTED 或 SYNTHETIC_EVALUATION 来源。

公共知识从本地 JSON 数组导入，每项为 `{"mealId":已有公共餐食ID,"knowledge":{...}}`，来源必须为 IMPORTED 且带非空来源引用。执行：

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.profiles=rag" "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --diet.retrieval.index-command=import --diet.retrieval.import-file=E:/path/to/verified-knowledge.json"
```

评测中的 45 道假设配方不会自动导入业务库。主要食材信息不等于完整配方或已验证营养数据。

聊天的 `context.retrievalConstraints` 是完整限制对象：

```json
{"retrievalConstraints":{"requiredIngredients":["番茄","鸡蛋"],"excludedIngredients":["牛肉"],"requiredSlots":{},"excludedSlots":{"taste":["辣","微辣","中辣","麻辣"]}}}
```

不提供该字段保留会话限制；提供空对象清除，null 或未知字段报错。原话有限规则支持“不吃牛肉”“同时用番茄和鸡蛋”“鸡蛋必须用上”“取消牛肉限制”。不要把“家里有鸡蛋”自动解释为必须使用。

未知或矛盾限制进入澄清，并持久保存 pendingConstraintText。此时普通追加条件不会解除澄清；用户须明确回复“确认全部限制：必须含番茄，不要牛肉”、明确“取消所有限制”，或提交结构化完整替换。换一批保留已有需求及限制。

## 索引状态与恢复

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.profiles=rag" "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --diet.retrieval.index-command=status"
mvn -o spring-boot:run "-Dspring-boot.run.profiles=rag" "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --diet.retrieval.index-command=retry"
```

`meal_index_task` 是按 mealId 合并的版本日志；`meal_index_progress` 按 collection 保存完成版本、尝试次数和错误。重建、重试、后台处理通过 MySQL GET_LOCK 串行，多个进程不会相互覆盖向量或确认其他 collection 的任务。所有实例必须连接同一权威 MySQL；连接池须容纳锁连接与工作连接（建议至少 2）。

每个版本失败 5 次后保留失败状态；正常修改生成新版本，显式 retry 重置预算。查询触发的陈旧向量补偿不会清零已有待处理/失败任务。重建保留旧 collection；本阶段不自动清理历史日志或旧 collection。

## 可复现评测

默认测试不需要云配置：

```powershell
mvn -o test
docker compose -f compose.retrieval-eval.yml up -d --wait
$env:RETRIEVAL_EVAL_DB_URL='jdbc:mysql://127.0.0.1:33077/diet_retrieval_eval?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:RETRIEVAL_EVAL_DB_USERNAME='root'
$env:RETRIEVAL_EVAL_DB_PASSWORD=''
mvn -o "-Dretrieval.eval.mysql=true" "-Dretrieval.eval.qdrant=true" test
```

DB URL 必须指向本机专用 `diet_retrieval_eval`。测试用同连接临时表，不读写业务餐食。Qdrant 协议测试使用 `diet_eval_` 独立 collection 和固定测试向量；这不测语义质量。

真实模型参数冻结后：

```powershell
mvn -o "-Dtest=HybridRetrievalEvaluationTest" "-Dretrieval.eval.mysql=true" "-Dretrieval.eval.hybrid.live=true" test
```

沿用上述 embedding 环境变量；可用 `RETRIEVAL_EVAL_QDRANT_URL` 指向专用本机 Qdrant。此命令会向实际云服务发送固定评测文本。没有显式 live 标志不会调用云 embedding；显式开启但配置缺失则失败，不能用测试向量补成绩。

报告在 `target/evaluation/hybrid-retrieval/{structured,hybrid}-{fixed,parsed}/report.json` 和 `.md`；原始基线在 `target/evaluation/retrieval/`。运行前清除旧成功报告，成功结束才生成 status.json。metadata 包含语料/查询/实现哈希、模型、维度、模板、collection、参数及环境版本。fixed 测检索；parsed 单独记录异常、实际/预期限制、漏项与多项，检索质量仅以成功解析查询为分母。

所有标签仍为 draft，需人工复核。先调 dev、再冻结配置测 test，不按结果修改标签。HYBRID 默认启用门槛见规格 RAG-10；当前尚未运行真实语义对照。
