# Phase 5 最小闭环场景矩阵

## 1. 任务说明

- 当前阶段：`Task 5.9.a`
- 当前执行步骤：`整理最小闭环场景矩阵与联调配置`
- 已完成步骤占比：`2/4`
- 剩余步骤：`补齐后端集成测试、补齐前端关键路径测试`
- 步骤执行状态：`成功`

## 2. 场景矩阵

| 场景类型 | 场景名称 | 目标链路 | 后端测试入口 | 前端测试入口 | 联调配置 |
| --- | --- | --- | --- | --- | --- |
| 主链路 | 组织知识驱动正式交付闭环 | 组织资料接入 -> 跨层召回 -> 记忆复用 -> 完整对话动作预览 / 确认 -> 回放与恢复 -> 正式导出 | `Phase5EnterpriseDeliveryIntegrationTest` | `TaskDetailPage.test.tsx`、`ReportPage.test.tsx`、`DeliveryExportPanel.test.tsx` | `application-phase5-integration.yml` |
| 治理链路 | 配额阻断与恢复闭环 | 组织级配额阻断 / 连接器忙碌 -> 用户可读提示 -> 占位释放后恢复执行或重试导出 | `Phase5ConversationRoutingIntegrationTest` | `ConversationPage.test.tsx`、`task-event-stream-reconnect.test.ts` | `application-phase5-integration.yml` |

## 3. 依赖与约束

- 前置依赖：`Task 5.1` 至 `Task 5.8` 已提供组织知识、模型治理、回放恢复与正式导出基础能力。
- 环境依赖：联调测试统一使用 `application-phase5-integration.yml`，保证 `H2`、`Redis` 与 `RocketMQ` 测试参数可复现。
- 入口约束：后续 `Task 5.9.b` 与 `Task 5.9.c` 只能围绕本矩阵补自动化验证，不得新增未登记的“隐式主路径”。
