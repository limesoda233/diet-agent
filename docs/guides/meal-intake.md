# 一句话整理个人餐食

个人餐食管理页与聊天使用同一个 `MealDraftService`。轻量模型负责提取，代码核对启用词表和原文依据，用户确认后才保存到 MySQL。

## 管理页

进入个人餐食页，在新增区域输入例如“宫保鸡丁，中午吃，微辣，食材有鸡肉和花生”，点击“AI 整理”。核对回填的菜名、餐次、标签、食材与做法，补齐菜名和餐次，再点击保存。整理接口本身不会创建餐食。编辑已有餐食继续使用人工表单。

## 聊天

明确说“帮我录入番茄鸡蛋面”才开始录入。系统返回草稿，缺餐次时可以回复“午餐”；也可以明确修改草稿。完整后使用草稿卡的确认保存按钮或明确回复“确认保存”。普通“是”“好”和“中午吃了宫保鸡丁”不会自动保存。

草稿卡绑定当前用户、会话与草稿 ID。取消后重新开始；旧卡片不能用来保存新草稿。聊天保存将个人餐食与保存回执放在同一事务中，同一草稿重复确认返回原餐食 ID。当前保证限定为单实例。

## 配置与代码入口

沿用现有 `DietLightChatModel` 的 API 配置。`application.yml` 中 `diet.meal-intake.timeout` 默认 `10s`，`max-input-chars` 默认 `4000`；模型单次尝试，不带历史 Agent 记忆或数据库工具。

- `service/meal/intake/MealDraftService`：严格 JSON、标签、原文依据和稀疏更新。
- `AgentScopeMealDraftModel`：一次无工具轻量模型调用。
- `controller/meal/MealDraftController`：`POST /api/v1/diet/meals/personal/draft`，请求为 `{ "text": "..." }`。
- `MealIntakeWorkflow`：聊天路由、补字段、确认及取消。
- `MealDraftSaveService`：事务保存。
- `SessionStateService`：在会话 `slots._meta.mealDraft` 保存草稿与回执。

原始描述在草稿中最多保留 4000 字符；现有知识描述上限 2000 字符，超过会截断并给出提示。未提到的菜系、口味和做法留空；不根据菜名推测营养，也不把个人餐食写入公共菜谱索引。

## 验证

运行 `mvn test` 与 `mvn -DskipTests package`。前端交互使用 `node --test src/test/js/*.test.js`。这些验证使用受控模型与独立测试数据库，真实模型提取质量需在本地配置完成后另行抽查。
