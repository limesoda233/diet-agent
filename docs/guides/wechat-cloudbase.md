# 小狗劝吃：微信云开发部署清单

> **2026-11-11 平台变更：此清单的云开发 MySQL 开通步骤暂不可执行。** 腾讯云[当前计费文档](https://cloud.tencent.com/document/product/876/140498)说明自 2026-10-20 起不再支持新开通云开发 MySQL。现有 `diet` 微信体验版环境仅显示文档型数据库，不能把集合当作本项目的 MySQL 表。当前代码仍使用 MySQL；确定替代数据库方案前，不要导入下列 SQL 或创建云托管正式服务。小程序云托管调用代码可以保留。

本项目使用同一个云开发环境中的**云托管**运行 Java 21 / Spring Boot，使用**云开发 MySQL** 保存用户和餐食。小程序通过 `wx.cloud.callContainer` 访问服务，不需要购买 API 域名。云函数不能直接替代当前的常驻 Spring Boot 服务。

## 你先在控制台完成

1. 用 AppID `wx15b95618032f7270` 登录微信开发者工具，开通并关联一个云开发环境。记下**环境 ID**；它不是密钥，可以发给协助开发的人。确认该环境可使用云托管和 MySQL，并阅读控制台显示的套餐、按量计费与预算告警。
2. 在该环境中启用 MySQL，创建空库 `diet_experience` 和专用数据库账号。开启数据库直连，记录**内网**主机与端口；云托管若要求私有网络配置，也在服务中开启。不要把数据库密码发到聊天或写进仓库。
3. 按顺序向空库执行 `experience_schema.sql`、`007_wechat_access.sql`、`public_slot_seed.sql`、`public_meal_seed.sql`，文件都在 `src/main/resources/db/`。只导入公共数据，不导入 `diet_db.sql` 或本地用户餐食。执行后按[体验库检查](wechat-mini-program.md#1-准备独立体验数据库)核对个人数据为空。
4. 在云托管创建服务，名称**精确填 `diet-agent`**。从仓库根目录源码部署，使用根目录 `Dockerfile`，服务端口 `8080`。服务与 MySQL 位于同一云开发环境，数据库连接使用内网地址。仅小程序访问时可关闭服务公网入口。
5. 在云托管服务版本的环境变量中配置下表。不要将变量值写进 Dockerfile、`miniapp/config.ts` 或 Git。

| 变量 | 填写内容 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `experience` |
| `DB_URL` | `jdbc:mysql://<内网主机>:<端口>/diet_experience?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai` |
| `DB_USERNAME`、`DB_PASSWORD` | 云开发 MySQL 专用账号及密码 |
| `WECHAT_MINI_APP_SECRET` | 微信小程序后台的 AppSecret |
| `DIET_BOOTSTRAP_INVITE_CODE` | 自行生成的高熵邀请码，仅第一次管理员登录使用 |
| `DEEPSEEK_API_KEY` | 项目现有模型服务密钥 |
| `JEV_ENABLED` | 未配置 JEV 服务时设为 `false` |

`WECHAT_MINI_APP_ID` 已有默认值。`experience` 不能与 `local-auth` 同时启用。建议为服务设置至少 **1 个常驻实例**：Trace 到期清理是 Java 进程内的每小时定时任务，缩容到 0 后不会准时运行。这会产生持续资源费用，开通时查看预计费用并设置预算告警。以后可把清理迁移到云端定时任务，再评估缩容到 0。

## 小程序连接配置

`miniapp/config.ts` 已填入环境 ID `diet-d1grvabedcea653ab`，服务名固定为 `diet-agent`。小程序启动时初始化 `wx.cloud`，登录和业务请求都走 `wx.cloud.callContainer`。环境 ID 和服务名是路由信息，不是服务端密钥。

## 真机试用与体验版

1. 在微信开发者工具导入 `miniapp/`，检查 AppID、云开发环境和服务名。先预览到自己的手机，确认微信登录与云托管请求成功。
2. 首次登录输入云端的 `DIET_BOOTSTRAP_INVITE_CODE`，创建管理员。进入“设置 → 邀请朋友”生成一次性邀请码，私下发给一位朋友。重复生成可邀请下一位；朋友的个人餐食库各自从空库开始。
3. 用两个微信账号检查个人餐食、聊天会话互不串联，公共餐食都可见；再检查退出、重新登录和删除账号。
4. 在微信公众平台按当前界面添加体验成员、上传开发版本并设置体验版；配置适用的隐私保护指引。体验版只供获授权的成员试用；若将来需要正式公开，再按平台流程提交审核发布。

参考：[云托管 Java 部署](https://docs.cloudbase.net/run/quick-start/dockerize-java)、[小程序访问云托管](https://docs.cloudbase.net/run/develop/access/mini)、[MySQL 内网直连](https://docs.cloudbase.net/database/configuration/db/tdsql/direct-connection)、[云托管计费](https://docs.cloudbase.net/run/faq/fee)。控制台入口和计费规则可能更新，以开通时显示的内容为准。
