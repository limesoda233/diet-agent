# 阶段 016 本地验收

2026-11-11 本地验证：

- `mvn test`：533 个测试，0 失败，14 个现有可选集成测试跳过。
- `npm --prefix frontend test`：69 个测试通过。
- `npm --prefix frontend run build`：通过。
- `npm --prefix miniapp test`：4 个测试通过，包含云托管请求与管理员邀请码路径。
- `npm --prefix miniapp run typecheck`：通过。
- `mvn -DskipTests package`：通过。
- `mvn -Pfrontend -DskipTests package`：通过，含 React 前端资源。

覆盖了新用户独立编号与空个人库、一次性邀请码、认证边界、反馈与会话归属、账号数据删除、Trace 元数据脱敏、一次性诊断授权并发消费和到期清理。云托管镜像尚未在本机 Docker 引擎中构建，云开发环境尚未开通；真实微信 AppSecret、MySQL 内网连接、微信后台体验成员及真机流程仍需项目持有人完成，见 [云开发部署清单](../../guides/wechat-cloudbase.md)。
