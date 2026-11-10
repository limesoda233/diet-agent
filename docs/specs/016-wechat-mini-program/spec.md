# 016 微信小程序认证、用户隔离与隐私 Trace 规格

状态：设计已在对话中确认，等待书面规格复核。设计日期：2026-11-10。

## 1. 背景

当前项目把 `X-User-Id` 当作用户身份，并在缺少请求头时使用用户 `1`。这个机制适合本地练习，无法阻止客户端伪造其他用户。个人餐食、会话、反馈、记忆和 Trace 已经包含 `user_id` 或所有权条件，因此首要工作是建立可信身份，再系统性补齐所有资源的所有权检查。

当前 Trace 会记录请求、会话状态、模型输入和模型输出。朋友参与体验后，默认观测必须与聊天正文分离。只有用户主动创建一次性诊断授权并重试请求时，服务端才保存该次请求的完整 Trace。

本阶段交付一个原生 TypeScript 微信小程序和配套 Spring Boot 后端能力，供受邀体验成员小范围使用。小程序 AppID 固定为 `wx15b95618032f7270`。AppSecret 仅通过服务端环境变量注入。

## 2. 目标

- 使用微信 `wx.login` 和服务端 `code2Session` 建立可信的内部用户身份。
- 使用一次性邀请码控制首次加入，已绑定用户后续可直接登录。
- 使用可撤销的服务端会话保护全部业务 API。
- 保留用户 `1` 的现有个人餐食、会话和其他历史数据，并将其绑定为首个管理员账号。
- 让个人餐食、个人做法、会话、反馈、用户记忆、餐食计划和 Trace 严格按当前用户隔离。
- 体验环境默认只保存不含正文的 Trace 元数据。
- 支持用户为下一次请求主动创建十分钟有效的一次性完整诊断授权。
- 提供登录、聊天、个人餐食和设置四类小程序页面。
- 保持餐食推荐只使用 MySQL，公共菜谱 RAG 继续遵守固定语料和本轮证据规则。

## 3. 方案选择

| 方案 | 优点 | 代价 | 结论 |
| --- | --- | --- | --- |
| Spring Security、MySQL 会话、原生小程序 | 身份和权限规则集中，可立即吊销，基础设施数量少 | 新增安全依赖和数据库表 | 采用 |
| 自定义 MVC 拦截器、MySQL 会话 | 依赖少 | 认证异常、方法权限和安全上下文需要自行实现 | 不采用 |
| CloudBase 身份、Java 业务后端 | 微信接入快 | 身份和运维跨两个后端，现有 Java 用户域仍需映射 | 不采用 |

登录凭证使用 32 字节安全随机数并以 Base64 URL 形式返回。数据库只保存凭证的 SHA-256 摘要。高熵凭证无需密码哈希算法，摘要查询也能保持常量结构。

小程序使用原生 TypeScript。首版页面数量有限，直接使用微信组件和 `wx.request` 可以保持构建、调试和发布链路清晰。

## 4. 交付边界

### 4.1 后端

- 微信 code 交换客户端。
- 用户、微信身份、登录会话、邀请码和诊断授权数据模型。
- Spring Security Bearer 认证。
- `local-auth` 开发 profile。
- 管理员邀请与账号管理 API。
- 个人资料、退出登录和账号数据删除 API。
- Trace `FULL`、`METADATA`、`OFF` 三种采集模式。
- 诊断授权、Trace 留存时间和清理任务。
- 所有现有用户资源的所有权校验。
- 统一且不泄露内部信息的 API 错误响应。

### 4.2 小程序

- 登录与首次邀请码绑定。
- 聊天和推荐结果。
- 个人餐食列表及增删改。
- 设置、昵称、退出、数据删除和一次性诊断授权。
- 登录过期、请求过快、模型超时和服务不可用提示。

### 4.3 运行配置

- 本地开发配置。
- 朋友体验环境配置。
- 环境变量清单和部署指南。
- 微信开发者工具导入配置。

## 5. 认证架构

### 5.1 登录流程

1. 小程序调用 `wx.login` 获得一次性 code。
2. 小程序调用 `POST /api/v1/auth/wechat/login`，提交 code 和可选的邀请码。
3. `WechatCodeExchangeClient` 使用服务端 AppID 与 AppSecret 调用微信 `code2Session`。
4. 服务端以 `(provider, app_id, provider_subject)` 查询微信身份。`provider_subject` 保存 OpenID。
5. 已绑定身份对应的账号为 `ACTIVE` 时直接签发新会话。
6. 未绑定身份必须提交有效邀请码。服务端在同一数据库事务中消费邀请码、创建或绑定用户、创建微信身份并签发会话。
7. 小程序只保存服务端返回的 Bearer 凭证、到期时间和最小用户资料。
8. 后续请求通过 `Authorization: Bearer <token>` 认证。

微信 code、session key、OpenID、Bearer 凭证和 AppSecret不得写入应用日志、Trace 或错误响应。服务端不保存微信 session key，因为本阶段不解密微信敏感数据。

### 5.2 会话规则

- 登录凭证默认有效期为 7 天，可通过配置调整。
- 每次成功微信登录签发一条新会话，支持同一用户多个设备会话。
- 退出登录只吊销当前会话。
- 管理员停用账号时吊销该用户全部会话。
- 数据库查询使用令牌摘要，比较摘要时使用固定长度字节。
- 过期或吊销的会话返回 HTTP 401。
- 已登录但角色不满足要求时返回 HTTP 403。

### 5.3 首个管理员与旧数据

数据库迁移创建 `diet_user.id = 1`，角色为 `ADMIN`，初始状态为 `PENDING_BIND`。这与现有餐食、会话、反馈、记忆和规划记录使用的用户 `1` 对齐。

服务端从 `DIET_BOOTSTRAP_INVITE_CODE` 读取首个管理员邀请码，只保存其摘要。该邀请码只能绑定 `diet_user.id = 1`，成功后立即变为已使用。之后的朋友邀请码由管理员 API 创建。

服务端不会在响应或日志中输出 bootstrap 邀请码。环境中未配置 bootstrap 邀请码时，`PENDING_BIND` 管理员无法完成首次绑定，应用仍可启动并在健康信息中报告认证初始化未完成。

### 5.4 本地开发身份

`local-auth` profile 显式开启后，开发认证过滤器才接受 `X-User-Id`。该 profile 不与 `prod` 或 `experience` profile 同时启用。生产和体验配置完全忽略 `X-User-Id`。

现有 React 页面继续使用本地身份切换器进行开发。它不作为小程序或体验环境的认证方式。

## 6. 数据模型

### 6.1 `diet_user`

| 列 | 类型 | 规则 |
| --- | --- | --- |
| `id` | BIGINT | 主键 |
| `display_name` | VARCHAR(64) | 用户手动填写，可空 |
| `status` | VARCHAR(24) | `PENDING_BIND`、`ACTIVE`、`DISABLED`、`DELETION_PENDING` |
| `role` | VARCHAR(16) | `USER` 或 `ADMIN` |
| `created_at` | DATETIME | 创建时间 |
| `updated_at` | DATETIME | 更新时间 |

### 6.2 `user_identity`

| 列 | 类型 | 规则 |
| --- | --- | --- |
| `id` | BIGINT | 主键 |
| `user_id` | BIGINT | 关联 `diet_user` |
| `provider` | VARCHAR(24) | 本阶段固定为 `WECHAT_MINI_PROGRAM` |
| `app_id` | VARCHAR(64) | 本阶段为 `wx15b95618032f7270` |
| `provider_subject` | VARCHAR(128) | OpenID |
| `created_at` | DATETIME | 创建时间 |
| `last_login_at` | DATETIME | 最近成功登录时间 |

唯一约束为 `(provider, app_id, provider_subject)`。同一内部用户在同一 provider 和 AppID 下最多绑定一个身份。

### 6.3 `user_session`

| 列 | 类型 | 规则 |
| --- | --- | --- |
| `id` | CHAR(36) | UUID 主键 |
| `user_id` | BIGINT | 会话所有者 |
| `token_hash` | BINARY(32) | SHA-256 摘要，唯一 |
| `expires_at` | DATETIME | 过期时间 |
| `revoked_at` | DATETIME | 吊销时间，可空 |
| `created_at` | DATETIME | 创建时间 |
| `last_seen_at` | DATETIME | 最近使用时间 |

`last_seen_at` 最多每五分钟更新一次，避免每个 API 请求都写数据库。

### 6.4 `user_invitation`

| 列 | 类型 | 规则 |
| --- | --- | --- |
| `id` | CHAR(36) | UUID 主键 |
| `code_hash` | BINARY(32) | 邀请码摘要，唯一 |
| `status` | VARCHAR(16) | `AVAILABLE`、`CLAIMED`、`REVOKED`、`EXPIRED` |
| `target_role` | VARCHAR(16) | 创建用户时授予的角色 |
| `target_user_id` | BIGINT | bootstrap 绑定用户 `1` 时使用，普通邀请为空 |
| `expires_at` | DATETIME | 过期时间 |
| `claimed_by` | BIGINT | 使用者，可空 |
| `claimed_at` | DATETIME | 使用时间，可空 |
| `created_by` | BIGINT | 管理员用户，bootstrap 为空 |
| `created_at` | DATETIME | 创建时间 |

普通邀请码使用 16 字节安全随机数，只在创建响应中返回一次明文。

### 6.5 `diagnostic_consent`

| 列 | 类型 | 规则 |
| --- | --- | --- |
| `id` | CHAR(36) | UUID 主键 |
| `user_id` | BIGINT | 授权用户 |
| `token_hash` | BINARY(32) | 诊断凭证摘要，唯一 |
| `status` | VARCHAR(16) | `AVAILABLE`、`CONSUMED`、`EXPIRED`、`REVOKED` |
| `expires_at` | DATETIME | 创建后 10 分钟 |
| `consumed_trace_id` | VARCHAR(64) | 被授权的 Trace，可空 |
| `created_at` | DATETIME | 创建时间 |
| `consumed_at` | DATETIME | 使用时间，可空 |

## 7. API 契约

### 7.1 公开认证 API

#### `POST /api/v1/auth/wechat/login`

请求字段为 `code` 和可选的 `invitationCode`。响应包含 Bearer 凭证、到期时间和当前用户资料。

微信返回无效 code、缺少 AppSecret、微信服务不可用和邀请码问题使用不同的稳定错误码。响应不透传微信原始响应内容。

### 7.2 已登录用户 API

- `POST /api/v1/auth/logout` 吊销当前会话。
- `GET /api/v1/me` 返回当前用户资料。
- `PATCH /api/v1/me` 更新手动填写的昵称。
- `DELETE /api/v1/me` 删除当前用户的业务数据并吊销全部会话。
- `POST /api/v1/diagnostics/consents` 创建一次性诊断授权。
- `DELETE /api/v1/diagnostics/consents/{id}` 撤销尚未使用的授权。

管理员账号删除前必须存在另一名 `ACTIVE` 管理员，否则返回冲突错误。

### 7.3 管理员 API

- `POST /api/v1/admin/invitations` 创建一次性朋友邀请码。
- `GET /api/v1/admin/invitations` 查看邀请状态，不返回邀请码明文。
- `DELETE /api/v1/admin/invitations/{id}` 撤销未使用邀请。
- `GET /api/v1/admin/users` 查看最小用户资料和账号状态。
- `PUT /api/v1/admin/users/{id}/status` 启用或停用用户。
- `DELETE /api/v1/admin/users/{id}/sessions` 吊销用户全部会话。

### 7.4 现有业务 API

全部 `/api/v1/diet/**` API 从 Spring Security 主体取得内部 `userId`。Controller 不再声明 `X-User-Id`，业务请求 DTO 也不接受用户 ID。

公共餐食和 slot option 仍要求登录，保持体验应用整体为受邀访问。

## 8. 所有权与删除规则

### 8.1 所有权检查

- `diet_sessions` 通过 `(id, user_id)` 读取和更新。
- `diet_messages` 通过所属 session 间接校验用户。
- `meal_item` 的 `PERSONAL` 数据通过 `(id, owner_user_id, source_type)` 读取和修改。
- `meal_knowledge` 只能通过已校验归属的个人 meal 访问。
- `recommend_feedback`、用户记忆和餐食计划均通过 `user_id` 访问。
- 用户 Trace 通过 `user_id` 写入，只有管理员调试接口可查询。
- 客户端提供其他用户资源 ID 时返回通用资源不存在响应。

会话 `loadOrCreate` 在指定 ID 已属于另一用户时不能尝试插入同一主键。服务端先检查会话 ID 是否存在，再区分创建和当前用户加载，但对客户端统一返回资源不存在。

### 8.2 用户数据删除

用户主动删除账号时，服务在单个事务中按外键顺序处理：

1. 吊销全部登录会话和诊断授权。
2. 删除用户记忆任务、证据、事件、曝光、反馈和 profile。
3. 删除餐食规划版本、反馈和计划。
4. 删除推荐反馈和请求 Trace。
5. 删除会话消息和会话。
6. 删除个人餐食。`meal_knowledge` 随 meal 外键级联删除。
7. 删除微信身份。
8. 清空昵称并将用户状态更新为 `DELETION_PENDING`，保留不可复用的内部 ID。

删除接口成功后返回 HTTP 204。重复调用使用已失效登录态，因此返回 HTTP 401。

## 9. Trace 与诊断授权

### 9.1 采集模式

| 模式 | 使用环境 | 行为 |
| --- | --- | --- |
| `FULL` | 本地开发 | 保存现有完整事件和 payload |
| `METADATA` | 朋友体验 | 保存事件结构和安全元数据，丢弃通用输入输出 payload |
| `OFF` | 紧急关闭 | 不落库 |

`METADATA` 事件允许保存以下字段：

- event type、phase 和 step order
- agent name、model name
- latency 和 token usage
- 标准化错误码
- route、source mode、candidate count、ranked count 和 evidence count 等显式白名单元数据

字符串型输入、输出、用户消息、模型回答、slots、会话快照、个人餐食内容、用户记忆和异常 message 全部丢弃。白名单元数据只接受布尔值、数字和受限枚举字符串。

### 9.2 单次完整诊断

用户创建授权后，小程序在下一次重试请求中发送 `X-Diet-Diagnostic-Consent`。服务端验证授权属于当前用户、尚未使用且未过期，然后在事务中标记为已消费并将本次 Trace 模式提升为 `FULL`。

授权只影响第一条成功打开 TraceScope 的请求。并发提交时只能有一个请求消费成功，其他请求按默认 `METADATA` 记录。

小程序在创建授权前展示采集内容和保存时间。创建操作需要用户主动点击，应用不会自动开启完整诊断。

### 9.3 留存

- 新建 `METADATA` Trace 的 `expires_at` 为创建后 14 天。
- 经诊断授权产生的 `FULL` Trace 的 `expires_at` 为创建后 24 小时。
- 本地开发 `FULL` Trace 的过期时间由配置决定，默认 14 天。
- 迁移前已有 Trace 标记为 `LEGACY_FULL`，不自动删除，由管理员按现有开发数据策略处理。
- 每小时运行一次批量清理，每批最多删除 500 条，循环次数有配置上限。

`diet_request_trace` 增加 `capture_mode`、`expires_at` 和 `diagnostic_consent_id`。调试查询响应包含 capture mode，方便管理员判断正文是否存在。

## 10. 小程序结构

新增 `miniapp/`，使用独立 `package.json`、TypeScript 配置和微信项目配置。

### 10.1 页面

- `pages/login` 执行微信登录并在首次加入时填写邀请码。
- `pages/chat` 创建会话、发送消息并展示推荐、计划、做法证据和错误编号。
- `pages/meals` 查看个人餐食并进入编辑页面。
- `pages/meal-edit` 新增或修改个人餐食。
- `pages/settings` 修改昵称、退出、创建诊断授权和删除账号数据。

### 10.2 客户端模块

- `services/auth` 封装 `wx.login`、登录态保存和退出。
- `services/api` 统一添加 Bearer 凭证、API base URL 和诊断授权 header。
- `services/session` 保存当前聊天 session ID，不跨用户复用。
- `stores/auth-state` 管理登录状态和到期恢复。
- `types/api` 定义与 Java DTO 对齐的接口类型。

收到 HTTP 401 时，客户端清除本地会话并重新执行微信登录。重试仅发生一次，避免无效 code 或服务异常形成循环。

AppID 写入 `project.config.json`。API base URL 按开发和体验环境配置。仓库不包含 AppSecret、Bearer 凭证、上传私钥或朋友邀请码。

## 11. 错误契约

统一响应结构包含 `code`、面向用户的 `message`、`traceId` 和时间戳。内部异常类名、SQL、微信原始响应、模型 payload 和堆栈不进入响应。

| HTTP 状态 | 场景 |
| --- | --- |
| 400 | 请求字段、邀请码格式或业务输入无效 |
| 401 | 缺少、过期或吊销的登录凭证 |
| 403 | 账号停用或角色不足 |
| 404 | 当前用户看不到的资源 |
| 409 | 邀请已使用、诊断授权已消费或最后管理员删除冲突 |
| 429 | 登录或业务请求超过配置频率 |
| 502 | 微信或模型上游暂时不可用 |
| 500 | 其他服务端异常 |

现有 `DietExceptionHandler` 的 advice 包范围改为实际 Controller 包，并扩展为统一错误契约。

## 12. 配置与密钥

| 配置 | 默认或要求 |
| --- | --- |
| `WECHAT_MINI_APP_ID` | 默认 `wx15b95618032f7270` |
| `WECHAT_MINI_APP_SECRET` | 必填环境变量，无默认值 |
| `DIET_BOOTSTRAP_INVITE_CODE` | 首个管理员绑定前必填 |
| `DIET_AUTH_SESSION_TTL` | 默认 7 天 |
| `DIET_TRACE_MODE` | 本地 `FULL`，体验 `METADATA` |
| `DIET_TRACE_METADATA_TTL` | 默认 14 天 |
| `DIET_TRACE_DIAGNOSTIC_TTL` | 默认 24 小时 |
| `DIET_DIAGNOSTIC_CONSENT_TTL` | 默认 10 分钟 |

应用启动时校验 profile 与必要配置。`experience` 或 `prod` profile 缺少 AppSecret 时启动失败。`local-auth` 与二者同时启用时启动失败。

日志配置对 Authorization、微信 code、邀请码和诊断凭证进行整体排除，应用代码不把请求 DTO 直接写入日志。

## 13. 兼容与迁移

- 数据库迁移只新增认证表和 Trace 列，不改变现有餐食与公共菜谱数据域。
- 用户 `1` 的现有记录继续使用原 ID，不批量改写业务表。
- Controller 改用认证主体后，service 和 mapper 的 `userId` 参数继续保留。
- `local-auth` profile 维持现有 React 测试的身份语义。
- 新小程序只调用稳定的 `/api/v1` 路径。
- Trace mapper 能读取迁移前 `capture_mode` 和 `expires_at` 为空的记录，并将它们呈现为 `LEGACY_FULL`。

## 14. 测试与验收

### AUTH

- AUTH-01 有效微信 code 和 bootstrap 邀请绑定用户 `1` 并签发会话。
- AUTH-02 普通邀请只能成功消费一次。
- AUTH-03 已绑定微信身份后续登录无需邀请码。
- AUTH-04 无效、过期和吊销会话均返回 401。
- AUTH-05 停用账号吊销全部会话并阻止重新登录。
- AUTH-06 生产配置中的 `X-User-Id` 不能影响身份。
- AUTH-07 `local-auth` profile 可以继续支持现有本地开发请求。
- AUTH-08 日志、错误响应和 Trace 不包含微信 code、OpenID、AppSecret、邀请码或 Bearer 凭证。

### ISOLATION

- ISO-01 用户 A 无法读取、修改或删除用户 B 的个人餐食和做法。
- ISO-02 用户 A 无法加载或继续用户 B 的会话。
- ISO-03 用户 A 无法提交关联用户 B 会话、计划或 Trace 的反馈。
- ISO-04 用户 A 无法读取或修改用户 B 的记忆。
- ISO-05 用户 A 无法访问用户 B 的餐食计划。
- ISO-06 普通用户不能调用 Trace、评测、邀请和账号管理 API。
- ISO-07 删除账号后相关业务数据按第 8.2 节清理。

### TRACE

- TRACE-01 `METADATA` Trace 不包含测试消息、模型回答、slot 值、个人餐食内容或异常 message。
- TRACE-02 未带诊断授权时不能产生 `FULL` Trace。
- TRACE-03 有效诊断授权只提升一条属于同一用户的请求。
- TRACE-04 并发消费同一诊断授权时只有一条请求成功提升。
- TRACE-05 过期和撤销的诊断授权保持默认采集模式。
- TRACE-06 到期清理不会删除未到期记录，每批删除数量受限。
- TRACE-07 迁移前 Trace 可读且标记为 `LEGACY_FULL`。

### MINIAPP

- MINI-01 新朋友可以通过有效邀请码完成首次登录。
- MINI-02 登录过期后客户端只进行一次静默重登。
- MINI-03 切换用户后不会复用前一用户 session ID 或缓存数据。
- MINI-04 个人餐食增删改与当前用户一致。
- MINI-05 用户主动授权后，下一次重试携带一次性诊断凭证。
- MINI-06 删除账号前展示影响范围并要求明确确认。

### 工程验证

- `mvn test`
- `npm --prefix frontend test`
- `npm --prefix frontend run build`
- `npm --prefix miniapp test`
- `npm --prefix miniapp run typecheck`
- `mvn -DskipTests package`

## 15. 实施拆分

1. 认证数据迁移与 mapper。
2. 微信 code 交换、邀请码和会话服务。
3. Spring Security 认证、开发 profile 和统一错误契约。
4. 现有 Controller 身份迁移和跨用户所有权修复。
5. Trace 模式、诊断授权和留存清理。
6. 管理员、个人资料和账号删除 API。
7. 原生 TypeScript 小程序。
8. 两用户隔离回归、完整验证和运行文档。

