# User Memory Implementation Plan

> Execute in this session with test-driven-development and task-scoped review. The user approved implementation; no additional design approval is needed.

**Goal:** 跨会话可纠正的用户记忆与可恢复的后台抽取。

**Architecture:** 结构化 MySQL 档案、证据、任务。在线条件准备在路由前，后台任务负责长期归档与聚合。模型仅提出候选，服务端验证写入。

**Tech Stack:** Java 21 / Spring Boot 3.3.13 / AgentScope 1.0.11 / MyBatis / MySQL / React TypeScript。

**Spec:** docs/specs/014-user-memory/spec.md

## Global Constraints

餐食仅 MySQL；公共菜谱和个人数据不混索引；保留现有未提交改动；无真实 API 自动运行；不提交构建资源；默认视频关闭。

## Task 1: 提取器

- Create `service/memory/extraction/MemoryCandidate.java`、`MemoryExtractor.java`、Jev/DeepSeek 实现与配置。
- 接口：`List<MemoryCandidate> extract(String message)`。候选字段 type/value/subject/stance/scope/operation/evidence，均 String；额外事实以独立候选返回。
- [x] 写真实解析与校验测试，包含他人、假设、否定、临时、多事实、未知食材、Jev 缺失答案/低分。
- [x] 运行指定测试观察失败。
- [x] 实现受限模型调用和开放事实降级，原文证据必须包含于输入。
- [x] 测试通过，报告限制；不得直接写数据库。

## Task 2: MySQL 档案与任务

- Create `service/memory` 域服务、Mapper SQL、`db/migrations/005_user_memory.sql` 与 H2 集成测试。
- [x] 测试消息与任务原子性、用户隔离、幂等、撤销水位、过敏待确认、失败重试。
- [x] 观察失败后实现 MemoryService、持久任务Worker、归纳逻辑。
- [x] 事务中只做数据库操作，模型调用在外；领取使用租约，旧版本提交拒绝。

## Task 3: 在线约束

- Modify `SessionService`、`DietOrchestratorService`，不改变现有构造器使用方契约。
- [x] 测试所有快捷入口前合并记忆、过敏保守澄清、偏好本轮覆盖、保存通知。
- [x] 在线抽取和长期处理使用同一候选，重试可重用已存候选。
- [x] 保留会话原始槽位，长期默认只用于本轮工作态；过敏数据缺口禁止假安全。

## Task 4: API 和前端

- Create memory Controller / MemoryPage；modify API types/client、导航、聊天提示。
- [x] 测试列表、确认、编辑、撤销、收集开关、切换用户隔离和失败提示。
- [x] 复用现有视觉组件与请求客户端，避免把晚到响应写入另一用户。

## Task 5: 验收与文档

- [x] 固定记忆开发集和离线契约评测；显式远程评测开关。
- [x] `mvn test`、`mvn -DskipTests package`、`npm --prefix frontend test`、`npm --prefix frontend run build`。
- [x] 更新 docs/README.md 与 docs/guides/user-memory.md、验收结果。
- [x] 独立代码审查，修复问题并运行对应检查。SQL 迁移保留为用户可审阅文件，不连接现有真实数据库。

