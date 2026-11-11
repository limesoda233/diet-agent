# 小狗劝吃：微信云开发部署清单

本项目在云开发环境 `diet-d1grvabedcea653ab` 中用**云托管**运行 Java 21 / Spring Boot，使用独立购买的 **TDSQL-C MySQL** 集群保存用户和餐食。小程序通过 `wx.cloud.callContainer` 访问服务，不需要购买 API 域名。云函数不能直接替代当前的常驻 Spring Boot 服务。

## 你先在控制台完成

1. 用 AppID `wx15b95618032f7270` 登录微信开发者工具，并关联云开发环境 `diet-d1grvabedcea653ab`。环境 ID 不是密钥。确认该环境可使用云托管，并阅读套餐、按量计费与预算告警。
2. 在独立的 TDSQL-C MySQL 集群中创建库 `diet_experience` 和仅有该库 DML 权限的运行账号 `diet_app`。从集群详情复制**外网地址和端口**；不要把数据库密码发到聊天或写进仓库。
3. 按顺序向空库执行 `experience_schema.sql`、`007_wechat_access.sql`、`public_slot_seed.sql`、`public_meal_seed.sql`，文件都在 `src/main/resources/db/`。只导入公共数据，不导入 `diet_db.sql` 或本地用户餐食。执行后按[体验库检查](wechat-mini-program.md#1-准备独立体验数据库)核对个人数据为空。
4. 在云托管创建服务，名称**精确填 `diet-agent`**。从仓库根目录源码部署，使用根目录 `Dockerfile`，服务端口 `8080`。开启云托管的固定公网出口 IP，并在 TDSQL-C 安全组中仅放通该 IP 到数据库的**内网访问端口**（实例详情中的 `3306`）；`DB_URL` 则使用实例详情中的**外网端口**。仅小程序访问时可关闭服务公网入口。
5. 在云托管服务版本的环境变量中配置下表。不要将变量值写进 Dockerfile、`miniapp/config.ts` 或 Git。

| 变量 | 填写内容 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `experience` |
| `DB_URL` | `jdbc:mysql://<外网域名>:<外网端口>/diet_experience?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=REQUIRED`；以 TDSQL-C 控制台的连接信息为准，外网端口未必是 `3306` |
| `DB_USERNAME`、`DB_PASSWORD` | `diet_app` 及其密码；该账号仅用于运行，不用于建表和导入 SQL |
| `WECHAT_MINI_APP_SECRET` | 微信小程序后台的 AppSecret |
| `DIET_BOOTSTRAP_INVITE_CODE` | 自行生成的高熵邀请码，仅第一次管理员登录使用 |
| `DEEPSEEK_API_KEY` | 项目现有模型服务密钥 |
| `JEV_ENABLED` | 未配置 JEV 服务时设为 `false` |

`WECHAT_MINI_APP_ID` 已有默认值。`experience` 不能与 `local-auth` 同时启用。建议为服务设置至少 **1 个常驻实例**：Trace 到期清理是 Java 进程内的每小时定时任务，缩容到 0 后不会准时运行。这会产生持续资源费用，开通时查看预计费用并设置预算告警。以后可把清理迁移到云端定时任务，再评估缩容到 0。

先在 TDSQL-C 的“数据安全 → SSL”开启加密，再使用上表的 `sslMode=REQUIRED`。此模式要求加密连接，但不验证服务器证书；正式使用时可下载腾讯云 CA 证书并为 Java 配置 truststore，再改为 `sslMode=VERIFY_IDENTITY`。不要使用 `useSSL=false`。这些配置均在云托管服务环境变量中设置，不写入 `application.yml` 或 `application-experience.yml`。

## 小程序连接配置

`miniapp/config.ts` 已填入环境 ID `diet-d1grvabedcea653ab`，服务名固定为 `diet-agent`。小程序启动时初始化 `wx.cloud`，登录和业务请求都走 `wx.cloud.callContainer`。环境 ID 和服务名是路由信息，不是服务端密钥。

## 真机试用与体验版

1. 在微信开发者工具导入 `miniapp/`，检查 AppID、云开发环境和服务名。先预览到自己的手机，确认微信登录与云托管请求成功。
2. 首次登录输入云端的 `DIET_BOOTSTRAP_INVITE_CODE`，创建管理员。进入“设置 → 邀请朋友”生成一次性邀请码，私下发给一位朋友。重复生成可邀请下一位；朋友的个人餐食库各自从空库开始。
3. 用两个微信账号检查个人餐食、聊天会话互不串联，公共餐食都可见；再检查退出、重新登录和删除账号。
4. 在微信公众平台按当前界面添加体验成员、上传开发版本并设置体验版；配置适用的隐私保护指引。体验版只供获授权的成员试用；若将来需要正式公开，再按平台流程提交审核发布。

参考：[云托管 Java 部署](https://docs.cloudbase.net/run/quick-start/dockerize-java)、[小程序访问云托管](https://docs.cloudbase.net/run/develop/access/mini)、[固定公网出口 IP](https://docs.cloudbase.net/run/deploy/networking/staticip)、[TDSQL-C 安全组](https://cloud.tencent.com/document/product/1003/80437)、[TDSQL-C SSL 加密](https://cloud.tencent.com/document/product/1003/109673)、[云托管计费](https://docs.cloudbase.net/run/faq/fee)。控制台入口和计费规则可能更新，以开通时显示的内容为准。
