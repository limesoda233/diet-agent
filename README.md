# Diet Agent

一个基于 Spring Boot 和 AgentScope Java 的饮食助手练习项目：用 MySQL 决定“今天吃什么”，用公共菜谱 RAG 回答“这道菜怎么做”，通过 Bilibili MCP 提供教学视频。

项目围绕 Agent 编排、RAG 检索、工具调用和评测展开。内置聊天、餐食管理、Trace 查询和评估页面，可以在一次对话中完成推荐、追问做法和查看视频。

## 功能

| 场景 | 实现 |
| --- | --- |
| 推荐单餐或三餐 | 多轮补全槽位，MySQL 筛选、规则排序和推荐理由 |
| “有鸡肉和花生，能做什么菜” | 公共菜谱 RAG，先给有引用的选菜说明，再展示原文做法 |
| 一句话录入个人餐食 | 管理页与聊天共用结构化草稿，核对并明确确认后写入个人库 |
| “宫保鸡丁咋做啊” | 规则路由或模型意图识别，检索 HowToCook，返回带引用的 Markdown |
| 推荐后问“怎么做”“第二道怎么做” | 根据最近展示顺序与当前选中菜品解析指代；目标不明时先追问 |
| 别名、错别字、长口语 | 可选轻量模型查询改写，保留结构化硬约束，失败回退原查询 |
| 查教学视频 | 固定版本 Bilibili MCP，返回可点击的视频卡片 |
| 排查不理想的回答 | 查看路由、指代解析、改写、Agent 调用及分支终态的 Trace |
| 比较检索与回答质量 | Java 固定评测集和检索指标，Python Ragas 离线回答评分 |

餐食推荐与公共菜谱是两个数据域。个人已保存做法位于 MySQL meal_knowledge，不进入公共菜谱索引。

个人餐食录入的操作与代码入口见[录入指南](docs/guides/meal-intake.md)。聊天需明确说“帮我录入”或“保存到我的餐食库”；普通吃饭描述不会自动保存。

## 核心链路

~~~mermaid
flowchart TD
    U[用户消息与会话状态] --> R[显式操作 / 规则路由 / 模型兜底]
    R --> M[餐食推荐：槽位澄清 → MySQL → 规则排序 → 推荐回答]
    R --> C[菜谱：解析菜名与多轮指代]
    C --> Q[可选查询改写]
    Q --> B[Lucene BM25]
    Q --> D[Embedding + Qdrant]
    B --> F[RRF 融合 → 父菜谱恢复与约束过滤]
    D --> F
    F --> A[证据裁剪 → Answer Agent → 引用校验]
    C --> V[Bilibili MCP 视频搜索]
    A --> O[文字菜谱与视频卡片]
    V --> O
    M --> T[Trace / 反馈 / 评测]
    O --> T
~~~

有两层并行：BM25 与 Dense 并行召回；文字菜谱与视频独立并行。查询不需要先完成 MySQL 推荐。单分支故障保留另一支结果，检索两支都失败时不正常生成答案。

多轮指代和 query rewrite 分开处理：先确定“问哪道菜”，再归一化检索文本。改写不带完整聊天历史；回答生成仍使用准备后的完整问题，避免丢失用户要求。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.3.13、MyBatis、MySQL 8 |
| 模型与 Agent | AgentScope Java 1.0.11、DeepSeek OpenAI-compatible API |
| 可选结构化决策 | Jev 意图识别与评估 Judge，失败回退原模型 |
| 菜谱检索 | Lucene 9.12.3 BM25、Qdrant、RRF、父子分块 |
| 本地 embedding | Ollama bge-m3:567m，1024 维 |
| 并行与降级 | Reactor |
| 视频工具 | AgentScope stdio MCP，@wangshunnn/bilibili-mcp-server@0.0.7 |
| 评测 | JUnit 5、Mockito、Ragas 0.4.3、Python、uv |
| 前端 | React、TypeScript、Vite，固定聊天输入区、Markdown 与视频卡片 |

运行环境使用 JDK 21；当前 Maven 有效编译目标为 release 17，没有宣称全部源码使用 Java 21 语法。

## 快速开始

### 1. 环境与数据库

基础应用需要 JDK 21、Maven、MySQL 8 和聊天模型 API Key。RAG 另需 Docker/Qdrant、Ollama；视频另需 Node.js 20+ 与 npx；Ragas 另需 Python 3.11–3.13 和 uv。

~~~powershell
git clone https://github.com/limesoda233/diet-agent.git
Set-Location diet-agent
~~~

在 MySQL 命令行执行，路径按本机位置修改：

~~~sql
CREATE DATABASE IF NOT EXISTS diet_db
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE diet_db;
SOURCE E:/Mycode/diet-agent/src/main/resources/db/diet_db.sql;
SOURCE E:/Mycode/diet-agent/src/main/resources/db/add_40_meal_items.sql;
SOURCE E:/Mycode/diet-agent/src/main/resources/db/meal_knowledge.sql;
~~~

diet_db.sql 是初始化脚本，已有业务数据时不要重复执行。SOURCE 是 MySQL 命令行指令；在 IDEA/DataGrip 中请打开 SQL 文件执行其内容。

在本机终端或 IDEA 环境变量配置 DB_PASSWORD、DEEPSEEK_API_KEY。数据库默认地址 localhost:3306/diet_db，用户名默认 root。项目不会自动加载 .env。

### 2. 启动基础推荐应用

首次先构建 React 前端（需要 Node.js 22+）：

~~~powershell
npm --prefix frontend ci
npm --prefix frontend run build
~~~

再启动 Java。前端开发热更新与打包说明见[React 运行指南](docs/guides/react-frontend.md)。

~~~powershell
mvn spring-boot:run
~~~

打开 [http://localhost:8080](http://localhost:8080)。基础推荐只使用 MySQL 与聊天模型，默认不连接公共菜谱检索和真实视频服务。

### 3. 准备公共语料与检索依赖

正文来自 HowToCook 1.6.0，固定 commit：a0b14bd388ee996943173679c3a7c9db7da04a6c。只读取 Markdown，不需要图片 LFS 数据。

~~~powershell
$env:GIT_LFS_SKIP_SMUDGE = "1"
git clone --depth 1 --branch 1.6.0 -c core.hooksPath=NUL https://github.com/Anduin2017/HowToCook.git E:/Mycode/HowToCook
git -C E:/Mycode/HowToCook rev-parse HEAD
ollama pull bge-m3:567m
docker compose -f compose.retrieval-dev.yml up -d qdrant
~~~

Ollama 未运行时在单独终端启动 ollama serve。确认服务：

~~~powershell
Invoke-RestMethod http://localhost:11434/api/tags
Invoke-RestMethod http://localhost:16333/collections
~~~

### 4. 构建索引，再启动 RAG 与视频

~~~powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook --recipe-rag-index=E:/Mycode/HowToCook"
~~~

看到 Recipe index activated 表示构建完成；应用仍运行，可按 Ctrl+C 结束。语料、解析器、embedding 模型或维度变化后再重建。

完整功能启动示例：

~~~powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag,video-mcp --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook --diet.recipe-rag.query-rewrite.enabled=true --diet.recipe-rag.query-rewrite.timeout=10s"
~~~

10 秒改写预算用于本地学习，默认是 2 秒。看到 Bilibili MCP ready; search tool verified 后再验收首次视频搜索。仅使用 RAG 时移除 video-mcp profile；关闭模型改写时移除改写参数。

IDEA 可直接启动 DietApplication：Active profiles 填 recipe-rag,video-mcp；Program arguments 填上述 --diet 参数；Environment variables 配置密码和 Key。找不到 npx 时，用 --diet.video-mcp.command=本机/npx.cmd 指定路径。

### 常用配置

| 配置 | 用途 |
| --- | --- |
| DB_URL / DB_USERNAME / DB_PASSWORD | MySQL 连接 |
| DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL | 聊天模型接口 |
| DEEPSEEK_MAIN_MODEL / DEEPSEEK_LIGHT_MODEL | 主模型、意图与改写模型 |
| JEV_API_KEY / JEV_INTENT_ENABLED / JEV_EVALUATION_ENABLED | 可选 Jev；无 Key 时使用原模型 |
| --diet.jev.intent-enabled=false | 暂停 Jev 意图识别，规则未命中时使用 DeepSeek |
| --diet.recipe-rag.enabled=true | 开启公共菜谱 RAG |
| --diet.recipe-rag.query-rewrite.enabled=true | 开启单轮模型改写 |
| video-mcp profile | 开启真实 Bilibili MCP |

凭据请在本机安全设置，真实值不要写入源码或文档。本地环境文件和覆盖配置已加入 .gitignore。

## 对话与 Trace

创建会话，之后复用同一 sessionId：

~~~powershell
$headers = @{ "X-User-Id" = "1" }
$sessionId = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/diet/sessions -Headers $headers).sessionId
$body = @{ sessionId = $sessionId; message = "宫保鸡丁怎么做"; sourceMode = "PUBLIC"; context = @{} } | ConvertTo-Json -Depth 6
$response = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/diet/chat -Headers $headers -ContentType "application/json" -Body $body
$response
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/diet/debug/traces/$($response.traceId)" -Headers $headers
~~~

多轮例子：推荐菜品 → “怎么做” → 多菜时回复“第二道” → “需要什么食材”。最新展示与选中对象写入会话 JSON，独立于“换一批”的累计排除 ID。没有明确目标时先追问。

Trace 包含 ROUTE_SELECTED、RECIPE_REFERENCE_RESOLVED、RECIPE_QUERY_REWRITE、Agent 调用和 RAG/MCP 终态。SUCCESS 只表示请求处理成功，不能替代质量评测。

## 测试与评测

基础验证不会自动调用真实云 Judge 或启动 Bilibili 服务：

~~~powershell
mvn test
mvn -DskipTests package
uv run --frozen --project evals/recipe-ragas pytest evals/recipe-ragas/tests -q
~~~

菜谱评测默认使用 v2：90 条查询，60 dev / 30 锁定 test，覆盖多 Gold、相关等级、口语、约束、near-OOD 和不支持事实。检索期望与回答期望分开，锁定 test 不用于修改 qrels。

~~~powershell
mvn "-Dtest=RecipeRetrievalEvaluationTest" "-Ddiet.recipe-rag.live=true" "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" test
mvn "-Dtest=RecipeQueryRewriteEvaluationTest" "-Ddiet.recipe-rewrite.live=true" "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" "-Ddiet.recipe-rewrite-evaluation.limit=12" test
mvn "-Dtest=BilibiliMcpLiveTest" "-Ddiet.video-mcp.live=true" test
~~~

三条命令分别执行真实检索对照、dev-only 改写对照和视频搜索契约。报告位于 target/evaluation/recipe，改写报告保留逐条状态、错误类型和回退次数。

Ragas 另外配置 RAGAS_LLM_* 与 RAGAS_EMBEDDING_*，然后执行：

~~~powershell
./scripts/evaluate-recipe-ragas.ps1 -CorpusPath E:/Mycode/HowToCook
~~~

Java 导出真实回答与实际裁剪后的证据，Python Judge 评分，输出 ragas-report.json 与 ragas-cases.csv。关注均值、有效样本数和失败数；拒答另行统计。配置、费用和报告解读见 [运行手册](docs/guides/recipe-rag-mcp-evaluation.md)。

### 已有结果与限制

历史 v2 锁定 test 上，Hybrid Recall@10 为 91.35%；同样的排序逻辑由串行改为并行后，本地单轮检索 P95 从 42 ms 降到 31 ms。这是小样本检索指标，不是回答准确率、全链路延迟或线上 SLA。Dense 的 MRR/nDCG 更好，near-OOD 暴露了强制 top-k 召回问题。口径见 [v2 验收记录](docs/specs/004-recipe-rag/evaluation-v2-acceptance.md)。

历史七组实验的 rewrite/rerank 标签仍按 identity 基线解释。模型改写已有独立 dev 对照入口，尚无已确认的聚合收益可报告；Ragas 工具链和离线测试已完成，真实 Judge 质量基线仍待运行。

## 项目结构与阅读入口

~~~text
src/main/java/com/diet/
  agent/                  Agent 构建与 Prompt
  controller/             HTTP API
  service/orchestrator/   会话、路由与响应编排
  service/meal/           MySQL 推荐与个人做法
  service/recipe/         多轮指代、菜谱业务
  service/recipe/rag/     corpus / index / retrieval / answer / model
  service/video/          视频业务与 MCP client
  service/jev/            可选结构化决策接口
  service/trace/          全链路事件
frontend/src/             React 页面、组件、类型化 API 与测试
src/main/resources/       配置、SQL、MyBatis、生成的前端资源
src/test/                 Java 测试与固定数据集
evals/recipe-ragas/        Python Judge 与冻结依赖
scripts/                  评测执行入口
docs/                     guides / evaluation / interview / architecture / specs
~~~

- [文档导航](docs/README.md)：找当前功能对应的文档。
- [源码阅读路线](docs/guides/code-reading.md)：从 Controller 跟随一轮请求。
- [学习与运行手册](docs/guides/recipe-rag-mcp-evaluation.md)：启动、Trace、检索评测、MCP、Ragas。
- [面试逐字稿](docs/interview/recipe-rag-mcp.md)：实现、评测和修复复盘。
- [意图评测](docs/evaluation/intent-evaluation.md)、[Jev 面试资料](docs/interview/jev-intent-evaluation.md)。
- [SDD 规格索引](docs/specs/README.md)：需求、标注依据和验收记录。

data、tmp、target 和依赖缓存不提交。固定评测数据与 uv.lock 保留。原餐食 RAG 与食材替换 MCP 已退役，当前推荐不依赖 embedding。

## 能力边界与来源

- 当前为确定性业务编排加单轮可选改写，尚未实现 Agent 自主多轮检索的 Agentic RAG。
- 重排默认 identity；时长约束未实现可靠的全文时长过滤；复杂菜品指代和食材实体归一化仍待完善。
- 视频使用第三方 Bilibili MCP，只返回链接与元数据；抖音、字幕提取和视频理解未实现。
- 未接入 MinerU，也未新增个人菜谱文档上传流程。
- 公共正文来自 [HowToCook 固定版本](https://github.com/Anduin2017/HowToCook/tree/a0b14bd388ee996943173679c3a7c9db7da04a6c)，该版本采用 Unlicense；[All-in-RAG](https://github.com/datawhalechina/all-in-rag/tree/583a61b09869bc3afc4552289171f6ec188f2c76) 是方法参考，按该版本 README 的 CC BY-NC-SA 4.0 声明归属。Java 实现没有复制其 Python 代码。
