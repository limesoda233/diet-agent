# 微信小程序体验部署

> **2026-11-11 更新：下面的云开发 MySQL 开通路径已被平台取消。** 腾讯云[当前计费文档](https://cloud.tencent.com/document/product/876/140498)说明自 2026-10-20 起不再支持新开通云开发 MySQL。项目仍依赖 MySQL，部署需先确定替代数据库；不要在当前环境的文档型数据库中创建集合来代替表。

此阶段的代码已包含微信登录、受邀加入、用户隔离、原生小程序和隐私 Trace。AppID 为 `wx15b95618032f7270`。部署选用微信云开发的云托管和 MySQL；开通与密钥配置由项目持有人在自己的账号中完成。具体操作见[云开发部署清单](wechat-cloudbase.md)。

## 1. 准备独立体验数据库

新建一个**空的** MySQL 数据库，例如 `diet_experience`。按以下顺序执行：

1. `src/main/resources/db/experience_schema.sql`：业务表结构，没有个人数据。
2. `src/main/resources/db/migrations/007_wechat_access.sql`：认证、邀请、诊断表和 Trace 列。
3. `src/main/resources/db/public_slot_seed.sql`：公共选项。
4. `src/main/resources/db/public_meal_seed.sql`：四道公共餐食。

不要向体验库执行 `diet_db.sql`、`add_40_meal_items.sql` 或 `meal_knowledge.sql`；它们包含本地用户 `1` 的个人种子数据。若以后启用用户记忆或餐食规划，再分别执行迁移 `005_user_memory.sql`、`006_meal_planning.sql` 并开启对应功能。个人餐食库由用户自己录入，首次登录时为空。

部署前可检查：

```sql
SELECT source_type, owner_user_id, COUNT(*) FROM meal_item GROUP BY source_type, owner_user_id;
-- 应只有 PUBLIC / NULL
SELECT COUNT(*) FROM diet_user;
-- 首次启动前为 0
```

## 2. 配置云托管服务端

用仓库根目录的 `Dockerfile` 将 Spring Boot 部署到云托管，服务名 `diet-agent`、端口 `8080`。开启私有网络以连接云开发 MySQL 的内网地址。服务端至少设置：

| 环境变量 | 用途 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE=experience` | 启用体验配置，Trace 默认仅元数据 |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | 指向云开发 MySQL 中的独立体验数据库，使用内网地址 |
| `WECHAT_MINI_APP_SECRET` | 微信小程序 AppSecret，仅在服务端保存 |
| `DIET_BOOTSTRAP_INVITE_CODE` | 首个管理员的一次性邀请码；自行生成高熵随机值 |
| `DEEPSEEK_API_KEY` | 项目现有聊天模型所需密钥 |

可选：`WECHAT_MINI_APP_ID`（默认已有 AppID）、`DIET_AUTH_SESSION_TTL`（默认 `7d`）、`DIET_TRACE_MODE`（体验默认 `METADATA`）、`JEV_ENABLED=false`（未配置 JEV 时）。服务端以 `experience` 启动时缺 AppSecret 或管理员邀请码会拒绝启动；不能同时启用 `local-auth`。不要把这些密钥、Bearer 凭证或邀请码写入 Git、公开日志或小程序代码。

本地 React 开发界面沿用 `X-User-Id`，需显式启用 `local-auth` profile；体验环境忽略该请求头。小程序始终用 Bearer 凭证。Trace 到期清理由进程内定时任务执行，云托管目前应保持至少 1 个实例常驻并留意费用。

## 3. 配置小程序

1. 把云开发环境与 AppID `wx15b95618032f7270` 关联；`miniapp/config.ts` 已填入环境 ID `diet-d1grvabedcea653ab`，服务名固定为 `diet-agent`。
2. 小程序通过 `wx.cloud.callContainer` 私有链路调用服务，不需要为此配置 `request` 合法域名或购买 API 域名；后端仍通过微信 `code2Session` 校验登录码。
3. 用微信开发者工具导入 `miniapp/`，核对项目 AppID，预览真机登录、聊天、添加个人餐食、退出与再次登录。
4. 上传代码为开发版本。若只给已添加的体验成员使用，可按微信公众平台当前流程设置体验版并邀请体验成员；若希望更多朋友直接使用，需要按平台当前流程提交审核并发布正式版。服务内的邀请码仍控制首次加入。
5. 在小程序平台配置适用的隐私保护指引，明确说明微信身份标识、用户填写的昵称、餐食和聊天数据的处理方式，以及账号删除入口。检查实际使用的接口是否需要平台隐私声明。

相关平台资料：[小程序访问云托管](https://docs.cloudbase.net/run/develop/access/mini)、[微信登录](https://developers.weixin.qq.com/miniprogram/dev/api/open-api/login/wx.login.html)、[微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html)。平台页面与审核要求可能更新，上架前以微信公众平台实时提示为准。

## 4. 首次加入和邀请朋友

首次用自己的微信登录，在小程序登录页输入 `DIET_BOOTSTRAP_INVITE_CODE`，将创建新的管理员用户，ID 从 `1000000` 开始，个人餐食库为空。旧本地用户 `1` 不会绑定或复制。管理员之后可在小程序“设置 → 邀请朋友”生成邀请码。邀请码只显示一次，离开页面后不再显示；可私下发给一位朋友，使用后失效。服务端仍有管理员 API 用于用户停用和会话吊销，但小程序目前未提供这些管理操作的界面；不要把管理员凭证发给朋友。

## 5. 数据和诊断边界

- 每位用户的 `PERSONAL` 餐食只属于自己；公共餐食使用 `PUBLIC` 且 `owner_user_id IS NULL`。
- 小程序名为“小狗劝吃”，登录与聊天页以“小狗”作为助手称呼，头像位于 `miniapp/assets/dog.jpg`；餐食库与个人做法仍只属于当前用户。
- 体验环境的 Trace 默认只记录事件结构、耗时和安全状态，不保存聊天正文与模型输入输出。用户在设置页主动授权后，下一次请求才可能产生一次完整 Trace；授权 10 分钟有效，成功消费后失效，完整 Trace 保留 24 小时。元数据 Trace 保留 14 天，每小时清理到期记录。
- 用户可在设置页删除账号及个人业务数据。最后一位有效管理员不能删除自己，需要先安排另一位管理员。
- 公共菜谱 RAG 与视频 MCP 按原项目 profile 单独配置；本体验配置没有将它们默认开启。餐食推荐只用 MySQL。小程序聊天框支持用文字口语描述录入个人餐食，先核对草稿再确认保存；目前不提供语音识别输入。

## 6. 验证

仓库内执行 `mvn test`、`mvn -DskipTests package`、`npm --prefix frontend test`、`npm --prefix frontend run build`、`npm --prefix miniapp test`、`npm --prefix miniapp run typecheck`。云端上线后，用两个不同微信身份分别登录，确认互相看不到个人餐食、会话与设置；确认无邀请码不能首次加入、公共餐食两人都可见。云托管私有调用、微信 code 交换和微信平台审核无法由本地单元测试代替。
