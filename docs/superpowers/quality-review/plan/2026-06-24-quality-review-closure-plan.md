# Quality Review Closure Plan

> 日期：2026-06-24
> 目标：把质量审查链路从“已有代码能力”收口为“可解释、可测试、可演示、可被 Orchestrator 消费”的正式链路。
> 前提：3.4 P1 已完成自动化收口，Reviewer 与 Orchestrator 的职责边界已经在代码和 prompt 中落地。

---

## 1. 当前阶段

当前阶段：质量审查链路诊断与收口文档已完成，P1 代码边界已有自动化支撑，live dev 外部依赖证据仍需分项补齐。

- [x] 诊断文档：`docs/superpowers/quality-review/problem/QualityReview.md`
- [x] 收口计划：`docs/superpowers/quality-review/plan/2026-06-24-quality-review-closure-plan.md`
- [x] P1 代码边界：Reviewer 只输出质量事实和兼容修订建议。
- [x] P1 smoke：`OrchestrationRuntimeFeedbackSmokeTest` 覆盖终审失败后的 Orchestrator 接管。
- [ ] live dev - PostgreSQL：验证质量状态写回和终审通过后的 DOMAIN 记忆写回。
- [ ] live dev - Redis：验证真实任务锁、恢复或并发保护不影响 Reviewer 节点推进。
- [ ] live dev - RocketMQ：验证真实工作流事件发布后 replay 可看到 Orchestrator 决策。
- [ ] live dev - 外部 LLM：验证质量 JSON 稳定性、慢响应、坏 JSON 重试和失败输出。

---

## 2. 收口目标

1. 总蓝图中质量审查链路从“待诊断”更新为“诊断 + 方案已完成，实施已有 P1 支撑”。
2. 质量审查链路的演示口径固定为：
   ```text
   Reviewer 判断质量事实 -> 输出 diagnoses / revisionPlan -> Orchestrator 决策是否补证或人工介入
   ```
3. 测试口径固定为：
   ```text
   PromptTemplateServiceTest + QualityReviewAgentTest + OrchestrationRuntimeFeedbackSmokeTest
   ```
4. live 验收不只看任务是否 `SUCCESS`，还要看诊断、来源、回放和记忆写回。

---

## 3. 任务拆解

### Task 1：固定质量审查职责边界

**状态：** 代码与测试已存在；本 Task 在收尾时执行验证命令，不再创建新文件。

**文件：**

- 代码已存在：`backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- 代码已存在：`backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- 测试已存在：`backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`
- 测试已存在：`backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`

**验收步骤：**

```powershell
mvn -pl backend "-Dtest=PromptTemplateServiceTest,QualityReviewAgentTest" test
```

期望：

- Reviewer prompt 不包含 `orchestrationAction`。
- Reviewer prompt 不包含 `CREATE_SUPPLEMENT_BRANCH`。
- `QualityReviewAgentTest` 覆盖 JSON 重试、诊断输出、终审边界和人工介入判断。

### Task 2：固定 Orchestrator 消费 Reviewer 输出的路径

**状态：** P1 代码链路已存在；本 Task 在收尾时执行验证命令，不再新增编排类。

**文件：**

- 代码已存在：`backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java`
- 代码已存在：`backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- 代码已存在：`backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapter.java`
- 测试已存在：`backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- 测试已存在：`backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapterTest.java`
- smoke 已存在：`backend/src/test/java/cn/bugstack/competitoragent/integration/OrchestrationRuntimeFeedbackSmokeTest.java`

**验收步骤：**

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest,OrchestrationDecisionAdapterTest,OrchestrationRuntimeFeedbackSmokeTest" test
```

期望：

- `quality_check_final` 失败才触发运行期 Orchestrator。
- `passed=true` 时输出 `NO_ACTION`。
- `requiresHumanIntervention=true` 时输出 `WAIT_FOR_HUMAN`。
- 有 `revisionDirectives` 时转为正式 `OrchestrationDecision`。

### Task 3：验证质量审查文档闭环

**状态：** 文档已存在；本 Task 只验证回链和差异，不再把文档标记为待新增。

**文件：**

- 已完成：`docs/superpowers/quality-review/problem/QualityReview.md`
- 已完成：`docs/superpowers/quality-review/plan/2026-06-24-quality-review-closure-plan.md`
- 已回链：`docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- 已回链：`docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`

**验收步骤：**

```powershell
git diff -- docs/superpowers/quality-review docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md
```

期望：

- 总蓝图质量审查行能指向诊断和方案。
- 稳定演示计划 Day 6 能标记质量审查文档闭环。

---

## 4. live 验收脚本

1. 使用固定任务输入创建任务。
2. 执行到 `write_report -> quality_check`。
3. 查看 `Report.qualityScore / qualityPassed / qualityIssues`。
4. 如果进入改写，再执行到 `quality_check_final`。
5. 终审失败时检查：
   - `diagnoses` 是否说明问题；
   - `revisionPlan` 是否给出修订项；
   - `revisionDirectives` 是否仅作为兼容建议；
   - replay 是否能看到 Orchestrator 决策；
   - 决策缺来源时是否标记 `MISSING_SOURCE`。
6. 终审通过时检查 DOMAIN 记忆写回是否包含 `sourceUrls`。

---

## 5. 风险评估

| 风险 | 影响 | 当前处理 |
| --- | --- | --- |
| CI 上 H2 与 PostgreSQL 行为差异 | 本地 H2 smoke 通过，但真实库写回或 JSON 字段行为仍可能不同 | live dev PostgreSQL 验收单独列项，不用 H2 结果替代真实证据 |
| 外部 LLM JSON 不稳定或慢响应 | Reviewer 可能触发最多 3 次 JSON 修复重试，导致节点耗时变长或失败 | 自动化已覆盖坏 JSON 重试；live dev 需记录慢响应、超时失败和失败输出 |
| Redis / RocketMQ 与本地同步测试差异 | 本地同步 smoke 不能证明真实任务锁和事件发布链路稳定 | live dev Redis、RocketMQ 验收单独列项 |
| 上游蓝图或稳定演示计划被并行修改 | 文档回链可能产生冲突或状态漂移 | 收尾时用 scoped `git diff --check` 和链接扫描确认回链 |

---

## 6. 不纳入本轮

1. 不新增 Citation Agent。
2. 不重写 `QualityReviewAgent` 为多类拆分。
3. 不把质量审查链路扩成对话动作执行入口。
4. 不把 live dev 外部依赖不稳定问题混入当前文档闭环。

---

## 7. 完成判定

质量审查链路本轮文档收口满足以下条件时关闭；live dev 证据包作为后续独立验证项推进：

- [x] 诊断文档存在。
- [x] 方案文档存在。
- [x] 总蓝图指向诊断和方案。
- [x] Reviewer / Orchestrator 边界有代码注释、prompt 和测试保护。
- [x] P1 smoke 能证明终审失败后由 Orchestrator 接管。
- [x] Task 1-3 已统一为验证型任务，不再把已存在文件误标为待新增。
- [ ] live dev PostgreSQL 证据包补齐。
- [ ] live dev Redis 证据包补齐。
- [ ] live dev RocketMQ 证据包补齐。
- [ ] live dev 外部 LLM 证据包补齐。
