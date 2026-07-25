# 阶段二 LLM Orchestrator 验收记录

> 本记录只保存配置摘要、稳定错误码、hash、token 可用性和持久化事实。禁止写入 API key、Authorization header、raw prompt、raw response 或异常原文。

## 1. 当前状态

当前阶段：Task 09 已执行到 Task 5，并在 Task 6 真实任务生命周期 E2E 前停止

- [x] 信息采集：Task 01-08 handoff、Task 09 计划、配置、fixtures、Provider 与 quota 已核对
- [x] 数据分析：A/B/C 分层实证和 Task 6 停止边界已完成
- [x] 报告撰写：Task 0-5 的成功、失败与环境边界已回写
- [x] 质检复核：未创建、未运行 `Stage2OrchestrationRealE2ETest`，未放宽 4 秒门槛

结论：**Task 09 与阶段二尚未完成**。A/B 层通过；C 层真实 Provider primary/shadow 均在 4 秒总预算内超时；D 层按用户要求未启动。

## 2. 冻结输入

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-07-15 |
| Fixture schema | ORCHESTRATION_DECISION_FIXTURE_V1 |
| Fixture SHA-256 | `08AFBBB1761FB241B396863AFA80ADD4DF2F7B8BFE3FE3A229A00AEA09966A89` |
| V2 fixture SHA-256 | `618564176A9ABCBE2FDBC7373F3A6E5BD7C7997843D9D665BBE6D998602D0027` |
| 默认 mode | RULE_ONLY |
| 默认 shadow | false |
| Provider key configured | true |
| Provider | deepseek |
| Model | deepseek-v4-pro |
| Endpoint host | api.deepseek.com |
| Temperature | 0.0 |
| Orchestrator timeout | 4000 ms |
| Parse retry | 1 |

## 3. Task 0-3 确定性结果

| 范围 | 结果 |
| --- | --- |
| Task 0 离线基线 | 116 tests / 0 failures / 0 errors |
| Task 1 核心回归 | 36 tests / 0 failures / 0 errors |
| Task 1 DAG/Recovery/Reviewer 扩展回归 | 113 tests / 0 failures / 0 errors |
| Task 2 fixture contract + 初始受控接缝 | 5 tests / 0 failures / 0 errors |
| Task 3 受控验收类 | 9 tests / 0 failures / 0 errors |
| Task 3 规定联合回归 | 21 tests / 0 failures / 0 errors |

已知环境警告：Maven `settings.xml:168` 存在既有 `Unrecognised tag: mirrors`，未影响上述构建。

Task 3 的 runtime batch 均来自真实 `OrchestrationRuntimeDecisionService`，不是手构 batch。合法成功、Policy fallback、parse fallback、shadow quota skip 均经过生产 TraceService、H2 outbox、report、Markdown/HTML/JSON export 和 replay；只读调用前后受控 Provider 调用数、AI audit 行数、quota used/reserved 增量均为 0。

受控矩阵已覆盖：合法 JSON、Policy rejection 同周期 Rule fallback、malformed JSON 双响应、非法 pair、timeout、prompt injection、invented URL、maxAuto 未达/达到上限、maxDecisionsPerCycle、section limit、confirmation、shadow quota exhausted。invented URL 只进入 `decision.inputRefs.discardedSourceUrls`，未进入任何可信 `sourceUrls`。

## 4. Provider Readiness

| 字段 | 结果 |
| --- | --- |
| 状态 | READY_FOR_MINIMAL_REQUEST |
| nodeName | stage2_provider_preflight |
| Provider/model | 生产 ModelGateway 最小 JSON 请求成功 |
| Deadline | 4000 ms |
| AI audit | 可按 41 字符 traceId 查询成功记录 |
| Token | actual/estimated 字段断言通过；具体值未写入本记录 |
| 实际预检提交 | 2 次 |

第一次预检已经得到 Provider 成功响应，但测试 traceId 为 53 字符，超过既有数据库 `VARCHAR(50)`，因此失败发生在测试审计写库而非 Provider。修为 `s2pf-UUID` 后执行唯一补跑并通过；两次均计入调用预算。

最小 preflight READY **不等于**真实 Orchestrator prompt 能在同一 4 秒 deadline 内完成。Task 4 的结果已经证明两者必须分开表述。

## 5. Task 1 缺口关闭

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| STAGE2-RULE-001 | CLOSED: HUMAN_INTERVENTION_WINS | `passed x requiresHumanIntervention` 四象限及 DynamicPlanAppender/DAG 回归通过 |
| decision -> token audit | CLOSED_CONTRACT | 每个 LLM cycle 生成不超过 50 字符的 `aiAuditTraceId`，success/fallback/shadow/V2/report/export/replay 投影回归通过；真实 primary token 关联因 Task 4 timeout 尚未形成成功样本 |

`passed=true && requiresHumanIntervention=true` 现在进入 WAIT_FOR_HUMAN / MARK_WAITING_INTERVENTION；`passed=true && requiresHumanIntervention=false` 保持原 NO_ACTION 短路语义。

## 6. Task 4 真实 Provider Fixtures

执行口径：单个 JUnit 方法按冻结 JSON 顺序执行全部 9 条；不因前一条失败跳过后续 fixture；不自动补跑；不修改 fixture 标签、Parser、Policy 或 4 秒 timeout。

| caseId | accepted | 稳定结果码 | pair/origin/policy | sourceUrls |
| --- | --- | --- | --- | --- |
| extractor-source-backed-gap | false | LLM_TIMEOUT | 无 LLM candidate | `https://example.com/extractor-pricing` |
| extractor-missing-source-gap | false | LLM_TIMEOUT | 无 LLM candidate | `[]` |
| analyzer-source-backed-gap | false | LLM_TIMEOUT | 无 LLM candidate | `https://example.com/analyzer-positioning` |
| writer-source-backed-citation-gap | false | LLM_TIMEOUT | 无 LLM candidate | `https://example.com/writer-pricing` |
| citation-source-backed-repair | false | LLM_TIMEOUT | 无 LLM candidate | `https://example.com/citation-repair` |
| citation-limit-reached | false | LLM_TIMEOUT | 无 LLM candidate | `https://example.com/citation-limit` |
| citation-missing-source | false | LLM_TIMEOUT | 无 LLM candidate | `[]` |
| final-review-passed | false | LLM_TIMEOUT | 无 LLM candidate | `https://example.com/final-review` |
| prompt-injection-source-backed-gap | false | LLM_TIMEOUT | 无 LLM candidate | `https://example.com/injection-pricing` |

首次执行结果：0/9 accepted。每条均形成 typed `LLM_TIMEOUT` 并安全回退 Rule；没有 Parser/Policy 契约失败，也没有成功 LLM_PRIMARY candidate。因此 primary H2/outbox/report/export/replay 成功链和真实 token 精确关联未能执行。

用户于同日明确要求继续执行，视为计划允许的人工批准唯一补跑。补跑仍为 0/9 accepted，9 条全部在首次调用达到 4 秒 deadline 后得到 `LLM_TIMEOUT`，没有 parse retry，结果与首次执行完全一致。Task 4 最终状态保持 `ENV_TIMEOUT_INCOMPLETE`；本轮不再继续重试。

## 7. Task 5 Shadow 与独立配额

### 7.1 受控 exhausted

通过。active snapshot 设置为 `used + reserved == limit` 后：

- `skippedReason=SHADOW_BUDGET_EXHAUSTED`；
- Provider 零调用；
- Rule 仍是主 attempts/final；
- V2/report/export/replay 可见 skipped fact；
- 只读调用前后 AI audit 与 quota 不变。

### 7.2 真实 active quota

| 字段 | 实测 |
| --- | --- |
| organization/scope/key | default-organization / MODEL / ORCHESTRATOR_SHADOW |
| initial used/reserved/limit | 0 / 0 / 100000 |
| sourceUrls | `https://example.com/ops/stage2-shadow-quota` |
| requested/executed | true / true |
| main origin | RULE_ONLY |
| shadow decision count | 0 |
| failure | LLM_TIMEOUT |
| 首次即时观测 used/reserved | 0 / 1115 |

真实 shadow 在 4 秒 deadline 超时，Rule 主路径正常并写入一条 V2，shadow 未进入 attempts/final；但没有产生 `LLM_SHADOW` decision，因此 Task 5 状态为 `ENV_TIMEOUT_INCOMPLETE`。

首次测试在 caller timeout 后立即读取 snapshot，与 worker `Future.done()` 的 reservation 补偿存在竞态，观测到瞬时 `reserved=1115`。该值不能证明永久泄漏。验收 harness 已改为最多 2 秒有界轮询回零，供人工决定后的唯一补跑使用；本轮遵守“不自动重跑”，所以真实路径的最终 reserved=0 尚未重新实证。测试 teardown 已删除验收 snapshot，没有污染开发数据库。

## 8. 分层结果

| 层级 | 状态 | 证据 |
| --- | --- | --- |
| A 确定性契约 | PASSED | 基线 116；Task 1 扩展回归 113 |
| B 受控 Provider 全接缝 | PASSED | 21 tests；真实 RuntimeDecisionService -> V2 -> 全只读链 |
| C 真实 Provider primary/shadow | INCOMPLETE_ENV_TIMEOUT | primary 0/9；shadow 0 decision；均为 LLM_TIMEOUT |
| D 任务生命周期 E2E | NOT_STARTED_BY_REQUEST | Task 6 测试文件未创建、命令未运行 |

## 9. 真实调用预算

| 用途 | 理论最大提交数 | 本轮实际提交数 |
| --- | ---: | ---: |
| Provider preflight | 1；harness 修复后允许唯一补跑 | 2 |
| 9 fixtures（含 parse retry 上界） | 单轮 18；人工批准补跑后累计两轮 | 18；两轮均为 9 次首次调用 timeout，无 parse retry |
| Primary 全链额外调用 | 1 | 0 |
| Shadow | 1 | 1 |
| Task 6 E2E | 本轮不执行 | 0 |

总计真实模型调用提交 21 次：preflight 2、两轮 fixtures 18、shadow 1。Provider SDK 内部网络重试次数未作为独立事实暴露，不能用该数字冒充 HTTP request 精确计数。

## 10. 安全与停止边界

- 默认 `application.yml` 仍为 `mode: RULE_ONLY`、`shadow.enabled: false`。
- 验收代码受 `RUN_STAGE2_ACCEPTANCE=true` 保护，常规 suite 编译后正常 skip。
- workflow event、report、export、replay 和本文均未保存 raw prompt、raw response、API key 或 Authorization header。
- 未创建、未运行 `backend/src/test/java/cn/bugstack/competitoragent/integration/Stage2OrchestrationRealE2ETest.java`。
- 本轮在 Task 6 真正任务生命周期 E2E 前停止；由于 C 层未通过，不能声明“阶段二已按原设计完成”。

下一步唯一动作：先处理 Provider/model 对完整 Orchestrator prompt 无法满足固定 4 秒 deadline 的环境阻断。Task 4 已用完人工批准补跑，本轮禁止继续重试；在新的外部状态变化与明确验收决策出现前，不得补跑 Task 5 或进入 Task 6。

## 11. Task 4 延迟与语义恢复实证

本节是 2026-07-15 后续恢复记录，只追加新事实，不覆盖前文 0/9 timeout 的历史结果。当前最新结论是：**Task 4 已完成；Task 5 尚未补跑；Task 6 真实任务生命周期 E2E 仍未创建、未执行。**

### 11.1 延迟根因与专用模型

隔离诊断使用相同生产 Prompt、单 Provider、单 HTTP attempt，并只记录安全指标：

| model | elapsedMs | input/output/total | Parser |
| --- | ---: | --- | --- |
| deepseek-v4-pro | 7178 | 1357 / 382 / 1739 | SUCCESS |
| deepseek-chat | 2478 | 1357 / 118 / 1475 | SUCCESS |

原阻断是全局 `deepseek-v4-pro` 无法稳定满足 4000 ms 编排热路径，不是 timeout 传递、Parser 或 Policy 错误。生产配置现为 Orchestrator 请求级 `deepseek-chat`，其他 Agent 继续使用全局模型；temperature=0、timeout=4000 ms、默认 `RULE_ONLY`、shadow=false 均未放宽。模型选择相关契约回归 27 tests / 0 failures / 0 errors，Task 3 受控联合回归 21 tests / 0 failures / 0 errors。

### 11.2 冻结语义恢复过程

专用模型修复后的首轮从 0/9 提升到 5/9；失败为 1 条 timeout 与 3 条合法但不在人工标签中的 pair。PromptBuilder 随后补充不含 caseId 的可信决策优先级，下一轮提升到 7/9：额度上限和注入场景关闭，只剩两条 `MISSING_SOURCE` 仍错误选择自动补证。

最终修复由 PromptBuilder 从已归一化的结构化事实派生 `mandatoryDecisionGuard`。该守卫只收窄人工介入、额度耗尽、终审通过和完全无来源四类安全停止场景，不读取自由文本，不修改 Parser、ActionMatrix、Policy、fixture 或 JSON Schema；正常有来源缺口仍由 LLM 决策。最终受控回归为 94 tests / 0 failures / 0 errors。

### 11.3 最终真实 Provider 结果

| caseId | 最终 pair | Policy |
| --- | --- | --- |
| extractor-source-backed-gap | APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE | allowed |
| extractor-missing-source-gap | WAIT_FOR_HUMAN / MANUAL_REVIEW | allowed |
| analyzer-source-backed-gap | APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE | allowed |
| writer-source-backed-citation-gap | REWRITE_ONLY / REWRITE_SECTION | allowed |
| citation-source-backed-repair | APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE | allowed |
| citation-limit-reached | WAIT_FOR_HUMAN / MANUAL_REVIEW | allowed |
| citation-missing-source | WAIT_FOR_HUMAN / MANUAL_REVIEW | allowed |
| final-review-passed | NO_ACTION / NO_ACTION | allowed |
| prompt-injection-source-backed-gap | APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE | allowed |

最终命令结果：1 test / 0 failures / 0 errors；9/9 均为 `LLM_PRIMARY`、命中冻结 `acceptedPairs` 且通过 Policy，没有 timeout。该轮形成 10 条 AI audit：8 条 fixture 首次解析成功，`extractor-missing-source-gap` 使用了允许的一次内建 parse retry。

首条可执行决策来自真实 `OrchestrationRuntimeDecisionService` batch，并通过生产 TraceService 写入 H2 outbox，再由 report、Markdown/HTML/JSON export 与 replay 读取同一事实。关联审计：taskId=601，input=1621，output=114，total=1735，estimatedInput=1330；只读前后 AI audit 与 quota 指纹不变。

### 11.4 调用预算与停止边界

截至 Task 4 完成，已保存证据能精确确认：原记录 21 次、延迟诊断 2 次、可信选择策略 7/9 轮 11 次、mandatory guard 最终 9/9 轮 10 次。专用模型修复后的 5/9 轮至少提交 9 次、最多 18 次，其 surefire 已被后续轮次覆盖，无法再诚实恢复成功 fixture 是否发生 parse retry。因此累计真实提交应记录为 **53-62 次区间**，不能用下界冒充精确值。上述三轮语义验收均对应明确代码/契约变化，不存在失败后无修改自动重跑。

Task 5 真实 active-quota shadow 未在本次恢复中执行，旧 `LLM_TIMEOUT` 不能被 Task 4 的成功替代。C 层整体仍需 Task 5 通过后才能完成；D 层继续保持 `NOT_STARTED_BY_REQUEST`。

### 11.5 STAGE2-POLICY-001：无来源自动变更仍被确定性层放行

状态：`CLOSED_POLICY_GUARD`。

现有实证：Prompt guard 修复前，真实模型在 `extractor-missing-source-gap` 与 `citation-missing-source` 返回 `APPEND_DYNAMIC_BRANCH/SUPPLEMENT_EVIDENCE`；Parser 成功且 `DecisionPolicyService` 返回 allowed，真实验收只能依靠 Prompt 后续改为 `WAIT_FOR_HUMAN/MANUAL_REVIEW` 才达到 9/9。现有单元契约还明确断言 `MISSING_SOURCE + CREATE_SUPPLEMENT_BRANCH` 可以执行。

该行为无法满足最初设计的确定性护栏要求：当候选 `evidenceState=MISSING_SOURCE` 且可信 `sourceUrls` 为空时，任何会修改计划图的自动动作都必须由 Policy 阻断，不能把安全性建立在模型遵守 Prompt 上。Prompt 的 `mandatoryDecisionGuard` 可以保留为减少 fallback 的引导，但不得是唯一防线。

修复范围预先冻结：只修改 `DecisionPolicyService` 的自动 mutation 来源校验及对应 Policy/Coordinator/Prompt 反例测试；不修改 Parser、ActionMatrix、fixture 标签、4 秒 timeout、V2 schema 或正常有来源候选的行为。

关闭结果：`DecisionPolicyService` 在动作矩阵或 legacy 映射得到 `normalizedAction` 后，对 `CREATE_SUPPLEMENT_BRANCH`、`CREATE_RERUN_BRANCH`、`CREATE_REWRITE_BRANCH` 统一执行来源不变量。当候选 `evidenceState=MISSING_SOURCE` 且有效可信 `sourceUrls=[]` 时返回稳定阻断码 `MISSING_SOURCE_FOR_AUTOMATIC_MUTATION`；`MANUAL_ONLY` 与 `NO_ACTION` 不受影响。Policy 只负责拒绝，不生成业务决策；`OrchestrationRuntimeDecisionService` 沿用既有一次性 Rule fallback，最终形成 `WAIT_FOR_HUMAN/MANUAL_REVIEW`。

反例验证：有来源的 Prompt guard 保持 `mandatory=false`、`reason=NONE`、`allowedPairs=[]`；有来源 LLM `SUPPLEMENT_EVIDENCE` 继续通过 Policy，并由 Executor 生成 `CREATE_SUPPLEMENT_BRANCH`，Tavily routing 保持可用。因此 guard 没有把正常 LLM 决策退化为固定规则。

验证结果：Policy/受控接缝定向 21 tests 通过；Task 09 扩展回归 116 tests 通过；完整 orchestration 包加 DynamicPlanAppender 为 261 tests / 0 failures / 0 errors / 4 skipped。4 条 skipped 均为显式环境开关保护的真实 Provider/诊断测试。本次未重跑真实 Provider 9 fixtures，未执行 shadow，未创建或运行真实生命周期 E2E。

### 11.6 Policy 安全规则下沉独立变更登记

变更标识：`STAGE2-POLICY-001 / MISSING_SOURCE_FOR_AUTOMATIC_MUTATION`；状态：`CLOSED_ROOT_CAUSE`。

发现的契约缺口：Task 4 真实 fixture 首次暴露出 `evidenceState=MISSING_SOURCE`、可信 `sourceUrls=[]` 的 LLM 候选仍可能通过 Parser 和 `DecisionPolicyService`，随后执行补采、重跑或改写等自动计划变更。最初增加的 `mandatoryDecisionGuard` 能在 Prompt 中引导模型改为 `WAIT_FOR_HUMAN/MANUAL_REVIEW`，解决了真实输出 pair 偏差这一表面症状，却没有改变“模型一旦忽略提示，确定性层仍会放行”的底层契约。

下沉到 Policy 的原因：Prompt 只能影响非确定性的候选生成，不能承担最终执行授权；LLM 输出必须被视为不可信输入。`DecisionPolicyService` 才是所有 LLM、legacy adapter 和未来入口进入 Runtime mutation 前的统一执行边界，因此“无来源不得自动修改计划图”必须成为 Policy 不变量，不能只依赖模型服从提示。Policy 只拒绝非法自动 mutation，不替模型生成业务决策；拒绝后的 `WAIT_FOR_HUMAN/MANUAL_REVIEW` 仍由既有 Rule fallback 负责，职责边界保持不变。

根因修复结果：Policy 对 `CREATE_SUPPLEMENT_BRANCH`、`CREATE_RERUN_BRANCH`、`CREATE_REWRITE_BRANCH` 统一检查来源不变量；命中 `MISSING_SOURCE + sourceUrls=[]` 时返回稳定阻断码 `MISSING_SOURCE_FOR_AUTOMATIC_MUTATION`。因此 Task 4 的 Prompt guard 从“唯一安全防线”降为减少错误候选和 fallback 的体验优化，上一轮症状修复被补全为确定性根因修复。反向契约同时证明有可信来源的 `SUPPLEMENT_EVIDENCE` 仍可通过 Policy，Task 6 的 source-backed Primary PASS 未被安全规则误伤。

审计归因：阶段二确实修改过 Prompt 与 Policy。Prompt 变更发生在 Task 4 的候选引导，Policy 变更发生在本条安全规则下沉；后续 Task 6 的公开导出修复没有再次修改这两者。任何“未修改 Prompt/Policy”的后续表述都只能描述对应的局部修复步骤，不能解释为阶段二全程未改动决策护栏。

## 12. Task 5 真实 Shadow 恢复执行

本节追加 Task 4 完成后的真实 Shadow 新事实，不覆盖第 7 节的历史 timeout。

### 12.1 真实结果

| 字段 | 实测 |
| --- | --- |
| organization/scope/key | default-organization / MODEL / ORCHESTRATOR_SHADOW |
| initial used/reserved/limit | 0 / 0 / 100000 |
| requested/executed/failure | true / true / NONE |
| main origin | RULE_ONLY |
| shadow decision | 1 条 LLM_SHADOW |
| 主 attempts/final 隔离 | 通过 |
| worker 完成后 used/reserved | 0 / 1316 |
| 测试结果 | 1 failure：reservation 未回基线 |

真实 Provider 已在固定 4 秒 deadline 内成功返回并形成 `LLM_SHADOW`，因此旧的 `ENV_TIMEOUT_INCOMPLETE` 已不再是当前阻断。测试在 reservation 断言处失败，后续 AI audit 关联以及 report/Markdown/HTML/JSON/replay 断言没有执行，不能用受控测试替代为“真实全链已通过”。

### 12.2 根因与修复

根因是 `ReservationAwareFutureTask` 只释放 worker 启动前的预留，缺少 worker 启动后的成功、异常和超时终态释放。修复后：成功/异常返回 caller 前释放；caller timeout 不提前释放仍存活的请求，worker 真正退出后释放；原子 owner 保证所有竞态最多释放一次；释放失败显式映射为 `SHADOW_RESERVATION_RELEASE_FAILED`。

验证结果：新增 2 条 reservation 生命周期测试先红后绿；配额相关联合回归 28 tests / 0 failures / 0 errors；完整 orchestration 包加 DynamicPlanAppender 为 262 tests / 0 failures / 0 errors / 4 skipped。受控 exhausted 路径继续保持 Provider 零调用、Rule 主路径不受影响。

### 12.3 当前判定

Task 5 状态为 `FIXED_PENDING_REAL_REVERIFY`，不是完成。当前已证明真实 Shadow 能成功决策，尚未用修复后的生产代码重新实证 reservation 回零、AI audit 关联和真实只读全链。本轮遵守单次真实调用边界，没有自动补跑，也没有创建或运行 Task 6 生命周期 E2E。阶段累计真实提交只能安全记录为 54-64 区间。

下一步唯一动作：由用户明确决定是否批准一次修复后的真实 Shadow 复验；通过前不得进入 Task 6。

### 12.4 修复后真实复验

用户明确批准后执行一次相同真实验收方法，结果为 1 test / 0 failures / 0 errors / 0 skipped。

| 字段 | 最终实测 |
| --- | --- |
| requested/executed/failure | true / true / NONE |
| main origin | RULE_ONLY |
| shadow decision | 1 条 LLM_SHADOW |
| 主 attempts/final 隔离 | 通过 |
| final used/reserved/limit | 0 / 0 / 100000 |
| AI audit traceId 关联 | 通过 |
| V2/outbox/report/export/replay | 通过 |
| 只读 AI audit/quota delta | 0 / 0 |

首次真实运行暴露的 reservation 泄漏已经由修复后的真实 Provider worker 关闭，不再只是 mock 或受控测试推断。report、Markdown/HTML/JSON export 与 replay 均显示同一真实 Shadow facts；只读操作没有新增审计或改变配额。验收 snapshot 已清理，默认配置恢复为 `RULE_ONLY`、`shadow.enabled=false`。

Task 5 最终状态：`COMPLETED_REAL_SHADOW_AND_QUOTA`。C 层状态更新为 `PASSED`：真实 primary 9/9 及持久化只读全链通过，真实 Shadow active quota 与受控 exhausted 两条路径通过。D 层任务生命周期 E2E 仍为 `NOT_STARTED_BY_REQUEST`，本轮未创建或运行 Task 6。

阶段累计真实提交只能安全记录为 55-66 区间；本次复验没有自动重跑。下一步按 Task 09 计划进入 Task 6 一次任务生命周期 E2E。

## 13. Task 6 首次真实任务生命周期 E2E

当前状态：`INCOMPLETE_NO_EXECUTABLE_LLM_PRIMARY`。本节只追加首次真实执行事实，不覆盖 Task 4/5 已通过的 C 层证据。

### 13.1 执行前确定性验证

- 新增 `Stage2OrchestrationRealE2ETest`，真实使用随机端口 Spring MVC、H2、DAG、Runtime、Policy、Trace、outbox、report/export/replay 与生产 ModelGateway；搜索、浏览器、RocketMQ 和上游 Agent 输出使用受控替身。
- 测试编译成功；真实开关关闭时 E2E 正常 skip，Provider 提交为 0。
- Rule/API smoke 暴露并关闭一处既有无来源 legacy 指令半闭环：Rule Brain 现在直接生成 `WAIT_FOR_HUMAN`，任务进入 `STOPPED + WAITING_INTERVENTION`，不再因 Policy 拒绝唯一自动候选而落成 `FAILED`。
- `RuleBasedOrchestratorDecisionBrainTest + OrchestrationRuntimeFeedbackSmokeTest`：11 tests / 0 failures / 0 errors。
- Task 3 受控矩阵、Runtime、可见性和 read-path 加 E2E skip：23 tests / 0 failures / 0 errors / 1 skipped。

### 13.2 唯一一次真实执行

```powershell
$env:RUN_STAGE2_ACCEPTANCE='true'
mvn -pl backend "-Dtest=Stage2OrchestrationRealE2ETest" test
```

结果：1 test / 1 failure / 0 errors / 0 skipped。测试通过真实 HTTP API 创建并执行一个有界任务；任务在 30 秒 deadline 内进入任务级终态，真实运行产生 1 条 V2 decision event、1 条 checkpoint，并创建 planVersion=2 的 5 个动态节点，没有无限补图。

真实 Orchestrator 产生 2 条 AI audit，时间顺序与一次内建 parse retry 一致；随后存在可执行决策完成动态补图。但 V2 attempts 中没有形成同时满足 `LLM_PRIMARY + Policy allowed + READY + APPEND_NODES` 的候选，因此硬断言失败。现有安全输出没有保存每条 attempt 的枚举摘要，不能伪造具体 Parser issue、Policy block code 或最终 fallback origin；测试 harness 已补充只输出 origin/type/action/policy/runtime/mutation 的安全诊断，供未来人工批准复验使用。

失败发生在 read path 断言之前，因此本轮不能声明 report、Markdown/HTML/JSON export、replay 一致性或只读 AI audit/quota delta 已由真实生命周期通过。Task 4 的真实 primary 全接缝证据仍有效，但不能替代 D 层生命周期证据。

### 13.3 停止边界

- 本次 Task 6 只创建并执行 1 个真实任务，没有自动重建任务或重跑测试。
- 实际新增 2 条 AI audit；Provider SDK 内部 HTTP retry 未独立暴露，不冒充精确 HTTP 请求数。
- 阶段累计真实提交区间从 55-66 更新为 **57-68**。
- `application.yml` 默认仍为 `RULE_ONLY`、`shadow.enabled=false`；`@AfterEach` 已恢复运行时默认模式。
- 未修改 4 秒 deadline、Parser、ActionMatrix、Policy、fixture 标签或 accepted pair 来追逐通过率。

下一步唯一动作：在不重跑真实 Provider 的前提下，先使用受控响应复现 Task 6 的 final-review legacy context，明确真实候选是 parse 后 pair 偏差还是 Policy rejection；只有形成可复现的通用契约修复并完成受控回归后，才由用户决定是否批准唯一一次 Task 6 复验。

### 13.4 final-review context 离线分类

本轮没有调用真实 Provider。首先核对 surefire 历史文件，确认首次真实运行中不存在 `STAGE2_E2E_ATTEMPT` / `STAGE2_E2E_CYCLE`：该安全摘要是在失败后才加入 harness，不能从旧 stdout 恢复或伪造。

随后使用与 `finalReviewGap()` 等价的生产 `OrchestrationContext`，只替换最外层 ModelGateway 响应，复用真实 PromptBuilder、Parser、Coordinator、Policy、Runtime、Trace 和 H2，形成四组对照：

| 受控响应 | Coordinator/Runtime 结果 | 是否能解释首次真实动态补图 |
| --- | --- | --- |
| 合法 `APPEND_DYNAMIC_BRANCH/SUPPLEMENT_EVIDENCE` | `LLM_PRIMARY`、Policy allowed、`READY`、`APPEND_NODES` | 否；这条路径本应通过硬门 |
| 两次 parse failure | `llmFailure=PARSE_ERROR`、parseRetry=1、最终 `RULE_FALLBACK/APPEND_NODES` | 是 |
| 首次 parse failure，retry 候选被 Policy 拒绝 | LLM attempt=`POLICY_REJECTED`、最终 `RULE_FALLBACK/APPEND_NODES` | 是 |
| 合法且 Policy allowed 的 `WAIT_FOR_HUMAN/MANUAL_REVIEW` | `LLM_PRIMARY`、`MARK_WAITING_INTERVENTION`、无 fallback | 否；不会创建 planVersion=2 的 5 个动态节点 |

专用 Prompt 反例同时证明：该有来源 legacy context 的 `mandatoryDecisionGuard` 为 `mandatory=false`、`reason=NONE`、`allowedPairs=[]`，可信选择策略仍把 source-backed evidence gap 指向 `APPEND_DYNAMIC_BRANCH/SUPPLEMENT_EVIDENCE`。因此没有证据支持“Task 4 mandatory guard 在确定性层误伤有来源场景”。

验证命令：

```powershell
$env:RUN_STAGE2_ACCEPTANCE=''
mvn -pl backend "-Dtest=Stage2OrchestrationControlledAcceptanceTest,OrchestrationDecisionPromptBuilderTest" test
```

结果：23 tests / 0 failures / 0 errors / 0 skipped。首次编写时有 1 条测试误把 `policyFallbackUsed` 当成所有 Rule fallback 的统一标志；生产契约实际只用它表示 Policy rejection fallback，Parser failure 由 `llmFailure` 单点表达。修正测试语义后全绿，未修改生产代码。

当前可证明的最窄分类是：首次真实 retry 要么仍为模型/Parser failure，要么产出可解析但被 Policy 拒绝的候选。由于旧 V2 随测试 JVM 关闭而消失，且安全摘要当时尚未输出，现有证据不能在两者间继续细分。没有可复现的生产契约 bug，因此当前不修改 Prompt、Parser、Policy 或 deadline。

下一步唯一动作更新为：由用户决定是否批准一次带安全枚举摘要的 Task 6 真实复验；若不批准，Task 6 保持 `INCOMPLETE_NO_EXECUTABLE_LLM_PRIMARY`。

### 13.5 Task 6 安全摘要复测与公开导出缺口

用户明确批准后，仅执行一次相同的真实任务生命周期命令，没有自动重跑：

```powershell
$env:RUN_STAGE2_ACCEPTANCE='true'
mvn -pl backend "-Dtest=Stage2OrchestrationRealE2ETest" test
```

结果为 1 test / 1 failure / 0 errors / 0 skipped。与首次失败不同，本轮安全摘要直接证明核心 Primary 硬门已经通过：

```text
STAGE2_E2E_ATTEMPT|index=0|origin=LLM_PRIMARY|decisionType=APPEND_DYNAMIC_BRANCH|actionType=SUPPLEMENT_EVIDENCE|policy=true|runtime=READY|mutation=APPEND_NODES
STAGE2_E2E_CYCLE|mode=LLM_PRIMARY|attempts=1|finalDecisions=1|policyFallback=false|llmFailure=NONE
```

真实任务为 `SUCCESS`，`planVersion=2`，创建 5 个动态节点；decisionId 为 `od-1-quality_check_final-llm-1`，AI audit traceId 为 `orch-06e6d492-83b3-4a07-be2b-1b4932c3184f`。Brain 层 `parseRetryCount=0`，无 fallback、无 `llmFailure`。本轮写入 2 条 Provider 层 AI audit 记录，但不能把它们误记为 Brain parse retry。

结构化 report 与 replay 已核对 decisionId、origin、aiAuditTraceId 和 sourceUrls。失败推进到公开 Markdown 下载 `GET /api/report/{taskId}/export`：返回文件只有正文和写作证据摘要，缺少 decisionId、`LLM_PRIMARY` 与 aiAuditTraceId。正式 `ExportPackageService` 的 Markdown/HTML/JSON 已包含协作决策摘要，公开 Controller 则仍经 `ReportQueryFacade -> ReportService.exportMarkdown/exportHtml` 使用旧轻量渲染，形成两套实现漂移。JSON 富导出已经生成，但测试先在 Markdown 断言失败，后续 HTML/JSON 内容断言不能记为本轮真实通过。

### 13.6 公开导出修复与离线验证

修复保持公开下载接口的无副作用语义：没有接入正式导出的 quota reservation 或 export record，而是让 `ReportService` 的 Markdown/HTML 下载复用正式渲染器中同一份协作决策摘要格式化逻辑。公开文件现在投影 decisionId、origin、Policy、runtime、mutation、aiAuditTraceId 与 sourceUrls。

新增两条 `ReportServiceTest` 回归，使用冻结 V2 fixture 覆盖公开 Markdown/HTML。测试先以 2 failures 复现缺口，修复后定向 2/2 通过；扩大离线回归结果为 44 tests / 0 failures / 0 errors / 0 skipped，其中受控 Task 6 验收 14 条、报告主路径 27 条、正式导出 2 条、Facade 1 条。该公开导出修复步骤没有调用真实 Provider，也没有再次修改 4 秒 deadline、Prompt、Parser、Policy、ActionMatrix 或 fixture；这不是对阶段二全程变更范围的描述，Task 4 已发生的 Prompt 引导与 Policy 安全规则下沉见 §11.5-11.6。

Task 6 当前状态更新为 `FIXED_PENDING_REAL_REVERIFY_EXPORT`。真实 Primary、任务终态、动态补图、结构化 report 与 replay 已由本轮实证，但整条 D 层仍因公开 Markdown 断言失败而不能标记通过；离线修复不能替代真实 API 复验。本轮新增 2 条 AI audit，阶段累计真实提交安全区间更新为 **59-70**。下一次真实 E2E 必须由用户再次明确批准，在此之前不重跑、不进入 Task 7。

### 13.7 公开导出修复后真实复验通过

用户再次明确批准后，仅执行一次相同真实生命周期命令，没有失败后自动重跑：

```powershell
$env:RUN_STAGE2_ACCEPTANCE='true'
mvn -pl backend "-Dtest=Stage2OrchestrationRealE2ETest" test
```

最终结果：1 test / 0 failures / 0 errors / 0 skipped，耗时 29.61 秒。安全证据为：

```text
STAGE2_E2E_ATTEMPT|index=0|origin=LLM_PRIMARY|decisionType=APPEND_DYNAMIC_BRANCH|actionType=SUPPLEMENT_EVIDENCE|policy=true|runtime=READY|mutation=APPEND_NODES
STAGE2_E2E_CYCLE|mode=LLM_PRIMARY|attempts=1|finalDecisions=1|policyFallback=false|llmFailure=NONE
STAGE2_E2E|taskId=1|terminal=SUCCESS|nodes=14|plans=2|cycles=1|audits=2|traceId=orch-7bbfd548-4a12-491d-ba7a-4ef293dadece|input=2953|output=308|total=3261|estimatedInput=2499
```

本轮完整执行并通过了此前被 Markdown 断言截断的所有后半段硬门：结构化 report、replay、公开 Markdown、公开 HTML 和正式 JSON 均包含同一真实 decisionId、`LLM_PRIMARY`、aiAuditTraceId 与 sourceUrls；公开内容不包含 raw prompt、raw response、Authorization 或 API key；读取前后 AI audit count 与 quota fingerprint 不变。任务进入 `SUCCESS`，14 个节点全部满足终态约束，无孤儿 `RUNNING`；形成 2 个计划版本、1 个决策周期和真实动态分支，均未超过 Policy 上限。

本次关联 2 条 AI audit，聚合 token 为 input=2953、output=308、total=3261、estimatedInput=2499。Provider 内部请求重试没有独立事实，不将 audit 数量解释为 Brain parse retry 次数。默认配置在 teardown 后恢复为 `RULE_ONLY`、`shadow.enabled=false`。

Task 6 最终状态：`COMPLETED_REAL_LIFECYCLE_E2E`。D 层更新为 `PASSED`；A/B/C/D 四层当前均已有通过实证。阶段累计真实提交安全区间从 59-70 更新为 **61-72**。下一步进入 Task 7 最终全回归、clean package、安全扫描和零遗留收口；本轮不继续运行任何真实 Provider 测试。

## 14. Task 7 最终回归与 E 层 Live E2E 收口

### 14.1 Step 1 定向总回归启动记录

当前阶段：Task 7 Step 1 Task 09 定向总回归执行中

- [x] 信息采集：已冻结时间、Git 工作区、Java/Maven、真实测试开关和默认 Orchestrator 配置
- [ ] 数据分析：等待定向测试结果
- [ ] 报告撰写：等待记录 tests/failures/errors/skipped 与耗时
- [ ] 质检复核：Step 1 通过前不进入兼容回归或 E 层 Live E2E

执行基线：

```text
startTime=2026-07-15 19:15:47 +08:00
java=17.0.3.1
maven=3.9.9
RUN_STAGE2_ACCEPTANCE=false
RUN_STAGE2_DIAGNOSTIC=false
orchestration.decision.mode=RULE_ONLY
orchestration.decision.shadow.enabled=false
```

本步骤只运行 §24 Step 1 的离线定向测试，不启动 `9093`、不连接真实 Provider、不创建 live task。工作区中的 Task 09 未提交改动作为验收对象保留，不执行 reset/checkout/clean。

- 当前执行步骤：Task 7 Step 1 定向总回归
- 已完成步骤占比：Task 7 0/8（0%）
- 剩余步骤：Step 1 结果判定；Step 2-8 待执行
- 步骤执行状态：Step 1 执行中；Step 2-8 待执行
- 真实 Provider/Tavily 调用增量：0
- 下一步唯一动作：执行并记录 Task 09 定向总回归原始命令

### 14.2 Step 1 定向总回归完成记录

当前阶段：Task 7 Step 1 已完成，在 Step 2 Task 01-08 兼容回归前停止

- [x] 信息采集：14 个目标测试类的本轮 Surefire XML 均已找到并核对更新时间
- [x] 数据分析：定向回归无 failure/error/skip，退出码与逐类 XML 汇总一致
- [x] 报告撰写：命令、测试总数、耗时、环境告警和真实调用边界已记录
- [x] 质检复核：未启动 `9093`、未启用真实测试开关、未创建 live task、未进入 Step 2

执行命令：

```powershell
mvn -pl backend "-Dtest=RuleBasedOrchestratorDecisionBrainTest,DynamicPlanAppenderTest,OrchestratorDecisionMetadataTest,OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,OrchestrationDecisionFixtureContractTest,Stage2OrchestrationControlledAcceptanceTest,OrchestrationRuntimeDecisionServiceTest,OrchestrationTraceV2FixtureContractTest,OrchestrationDecisionAuditPayloadSizeTest,OrchestrationDecisionSummaryProjectorTest,ReportExportRendererOrchestrationDecisionTest,OrchestrationDecisionVisibilityIntegrationTest,OrchestrationRuntimeFeedbackSmokeTest" test
```

结果：`67 tests / 0 failures / 0 errors / 0 skipped`，14/14 个测试类均有本轮报告；Surefire suite time 合计 43.164 秒，Maven 进程总耗时约 53.4 秒，退出码 0。

逐类 tests：

```text
RuleBasedOrchestratorDecisionBrainTest=9
DynamicPlanAppenderTest=6
OrchestratorDecisionMetadataTest=3
OrchestrationDecisionServiceLlmModeTest=4
OrchestrationDecisionServiceFallbackTest=4
OrchestrationDecisionFixtureContractTest=4
Stage2OrchestrationControlledAcceptanceTest=14
OrchestrationRuntimeDecisionServiceTest=10
OrchestrationTraceV2FixtureContractTest=2
OrchestrationDecisionAuditPayloadSizeTest=1
OrchestrationDecisionSummaryProjectorTest=5
ReportExportRendererOrchestrationDecisionTest=2
OrchestrationDecisionVisibilityIntegrationTest=1
OrchestrationRuntimeFeedbackSmokeTest=2
```

非阻断环境告警：Maven 3.9.9 报告全局 `settings.xml` 第 168 行存在未识别的嵌套 `mirrors` 标签；Spring 测试输出 commons-logging classpath 冲突提示。两者未造成测试失败或错误，本步骤不修改用户全局 Maven 配置或依赖边界。

- 当前执行步骤：Task 7 Step 1 已完成
- 已完成步骤占比：Task 7 1/8（12.5%）
- 剩余步骤：Step 2-8
- 步骤执行状态：Step 1 成功；Step 2-8 待执行
- 真实 Provider/Tavily 调用增量：0
- 数据库新增 live task：0
- 下一步唯一动作：执行 Task 7 Step 2 的第一组 Task 01-08 兼容回归，并单独记录结果

### 14.3 Step 2 Task 01-08 兼容回归启动记录

当前阶段：Task 7 Step 2 第一组兼容回归执行中

- [x] 信息采集：Step 1 已通过，Step 2 启动时间与真实测试开关状态已冻结
- [ ] 数据分析：等待第一组兼容回归结果；通过后才允许运行第二组
- [ ] 报告撰写：两组必须分别记录 tests/failures/errors/skipped 与耗时
- [ ] 质检复核：Step 2 两组全部通过前不进入完整 backend 回归

```text
startTime=2026-07-15 19:19:22 +08:00
RUN_STAGE2_ACCEPTANCE=false
RUN_STAGE2_DIAGNOSTIC=false
```

- 当前执行步骤：Task 7 Step 2 第一组兼容回归
- 已完成步骤占比：Task 7 1/8（12.5%）
- 剩余步骤：Step 2 第一组、第二组；Step 3-8
- 步骤执行状态：Step 1 成功；Step 2 执行中；Step 3-8 待执行
- 真实 Provider/Tavily 调用增量：0
- 下一步唯一动作：执行 Step 2 第一组原始命令并从本轮 Surefire XML 独立汇总

### 14.4 Step 2 第一组兼容回归完成记录

当前阶段：Task 7 Step 2 第一组已通过，第二组兼容回归准备执行

- [x] 信息采集：23 个目标测试类均找到本轮 Surefire XML
- [x] 数据分析：Maven 汇总与 XML 独立汇总均为 133 tests / 0 failures / 0 errors / 0 skipped
- [x] 报告撰写：第一组结果、耗时和故障分支日志边界已记录
- [ ] 质检复核：第二组通过前 Step 2 仍未完成

第一组执行结果：Maven 退出码 0，`BUILD SUCCESS`，总耗时 44.384 秒；23/23 个目标测试类合计 Suite time 37.45 秒。

测试日志中的 malformed JSON、缺少 Agent implementation、节点失败等 ERROR/WARN 是 `DagExecutorTest` 等故障分支的预期输入与断言事实；对应测试均通过，未观察到未捕获的产品失败。

- 当前执行步骤：Task 7 Step 2 第二组兼容回归
- 已完成步骤占比：Task 7 1/8（12.5%），Step 2 内部 1/2
- 剩余步骤：Step 2 第二组；Step 3-8
- 步骤执行状态：Step 1 成功；Step 2 第一组成功、第二组待执行
- 真实 Provider/Tavily 调用增量：0
- 下一步唯一动作：执行 Step 2 第二组原始命令并独立汇总结果

### 14.5 Step 2 第二组兼容回归失败记录

当前阶段：Task 7 Step 2 因一条失效测试契约保持未完成，Step 3-8 已阻断

- [x] 信息采集：已取得 Maven/Surefire 失败事实、失败源码、生产 Rule Brain 和 Policy 安全契约、Git 差异与既有验收记录
- [x] 数据分析：唯一失败是旧测试仍期待无来源 legacy 指令自动补图，与 Task 4 后冻结的安全停点语义冲突
- [x] 报告撰写：失败命令、断言、根因分类、停止边界和最小修复范围已记录
- [x] 质检复核：未无修改重跑、未继续 Step 3、未修改 Prompt/Policy/阈值、未调用真实 Provider

第二组命令结果：Maven 退出码 1，`148 tests / 1 failure / 0 errors / 0 skipped`，总耗时 13.656 秒。

唯一失败：

```text
class=OrchestrationDecisionServiceTest
test=shouldGenerateSupplementDecisionForFinalReviewEvidenceGap
line=125
expected=APPEND_DYNAMIC_BRANCH
actual=WAIT_FOR_HUMAN
```

失败输入明确为 `evidenceState=MISSING_SOURCE`、context/diagnosis/directive 的 `sourceUrls=[]`，旧断言仍期待 `APPEND_DYNAMIC_BRANCH/SUPPLEMENT_EVIDENCE/LEGACY_ADAPTER`。当前生产契约在 `RuleBasedOrchestratorDecisionBrain` 中先扫描无来源自动 legacy directive，并生成 `WAIT_FOR_HUMAN/MANUAL_REVIEW/RULE_ONLY/requiresConfirmation=true`；`DecisionPolicyService` 同时以 `MISSING_SOURCE_FOR_AUTOMATIC_MUTATION` 阻断无来源自动计划变更。该行为与 acceptance record §11.5-11.6、受控 Provider fallback、同文件其他无来源测试和 Policy 测试一致。

Git 差异证明本轮 Task 09 已更新同文件的 `passed=true + requiresHumanIntervention=true` 测试，却遗漏了第 97-129 行这条旧的无来源 legacy 断言。根因分类为 `PRODUCT_TEST_CONTRACT_STALE`：生产安全语义没有回归，兼容测试未同步，仍然构成 Step 2 阻断，不能忽略或写成通过。

允许的最小修复范围：只更新这条测试的名称与断言，使其验证 `WAIT_FOR_HUMAN/MANUAL_REVIEW/RULE_ONLY/MISSING_SOURCE/requiresHumanIntervention/requiresConfirmation`；不修改生产代码、Prompt、Parser、Policy、ActionMatrix、fixture 或 deadline。修复后先只运行该测试，再重新执行 Step 2 第二组完整原始命令；是否实施需用户明确批准。

- 当前执行步骤：Task 7 Step 2 第二组失败，等待处理测试契约
- 已完成步骤占比：Task 7 1/8（12.5%），Step 2 内部第一组通过、第二组失败
- 剩余步骤：修正并复验第二组；Step 3-8
- 步骤执行状态：Step 1 成功；Step 2 失败；Step 3-8 阻断
- 失败类型：产品代码库中的测试契约遗漏，不是外部环境失败
- 真实 Provider/Tavily 调用增量：0
- 数据库新增 live task：0
- 下一步唯一动作：由用户决定是否允许按上述最小范围修正失效测试契约

### 14.6 Step 2 最小测试修复与兼容回归完成记录

当前阶段：Task 7 Step 2 已完成，在 Step 3 完整 backend 回归前停止

- [x] 信息采集：用户已明确批准最小测试契约修复，实际 diff 与允许范围已核对
- [x] 数据分析：单方法与第二组完整原始命令均通过，首次失败已由测试契约同步关闭
- [x] 报告撰写：首次失败、根因、修复内容和两级复验事实完整保留
- [x] 质检复核：未修改生产代码、Prompt、Parser、Policy、ActionMatrix、fixture、deadline 或默认配置，未进入 Step 3

最小修复仅修改 `OrchestrationDecisionServiceTest.shouldGenerateSupplementDecisionForFinalReviewEvidenceGap`：

1. 重命名为 `shouldWaitForHumanWhenFinalReviewEvidenceGapHasNoSources`。
2. 将旧的 `APPEND_DYNAMIC_BRANCH/SUPPLEMENT_EVIDENCE/LEGACY_ADAPTER` 断言更新为 `WAIT_FOR_HUMAN/MANUAL_REVIEW/RULE_ONLY`。
3. 增加 `MISSING_SOURCE`、`requiresHumanIntervention=true`、`requiresConfirmation=true`、`sourceUrls=[]` 和 reason 包含“缺少 sourceUrls”的安全断言。

定向复验：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest#shouldWaitForHumanWhenFinalReviewEvidenceGapHasNoSources" test
```

结果：`1 test / 0 failures / 0 errors / 0 skipped`，Maven 退出码 0，总耗时 6.062 秒。

Step 2 第二组完整复验继续使用 §24 原始命令，结果：`148 tests / 0 failures / 0 errors / 0 skipped`，17/17 个本轮 Surefire XML 汇总一致；Maven 退出码 0，总耗时 18.897 秒。

Step 2 最终结果：第一组 `133/133` 通过；第二组修复后 `148/148` 通过。首次 1 failure 保留为发现过程，不从历史中删除，也不冒充首次即通过。

- 当前执行步骤：Task 7 Step 2 已完成
- 已完成步骤占比：Task 7 2/8（25%）
- 剩余步骤：Step 3-8
- 步骤执行状态：Step 1-2 成功；Step 3-8 待执行
- 真实 Provider/Tavily 调用增量：0
- 数据库新增 live task：0
- 下一步唯一动作：执行 Task 7 Step 3 完整 backend 回归 `mvn -pl backend test`

### 14.7 Step 3 完整 backend 回归失败记录

当前阶段：Task 7 Step 3 完整 backend 回归已执行但未通过，Step 4-8 阻断

- [x] 信息采集：已取得隔离执行的 Maven 退出码、315 个 Surefire XML、失败测试和 Spring 上下文根因
- [x] 数据分析：隔离执行稳定复现 13 failures / 6 errors；6 个 context error 均可追踪到缺少 `ModelGateway` Bean
- [x] 报告撰写：执行隔离异常、正式隔离结果、失败清单和停止边界已追加到验收记录
- [x] 质检复核：未修改生产代码、测试契约、Prompt、Parser、Policy、ActionMatrix、deadline 或默认配置，未进入 Step 4

执行命令：

```powershell
mvn -pl backend test
```

首次启动因执行工具的短超时返回而失去前台会话，但其 Maven/Java 子进程继续运行；随后启动的同命令与该遗留进程重叠，并发写入同一 Surefire 目录，导致部分 XML 拼接污染。该重叠结果不作为产品判定。确认所有相关 Maven/Surefire 进程自然结束后，使用相同原始命令完成一次隔离复验；隔离执行期间未启动其他 Maven 测试进程。

正式隔离结果：Maven 退出码 1，总耗时 350.9 秒；315/315 个 Surefire XML 可解析，时间范围为 2026-07-21 10:33:49 至 10:39:27。汇总为 `1413 tests / 13 failures / 6 errors / 10 skipped`，即 1384 条通过。

13 条 assertion failure：

1. `CitationAgentRepairabilityTest.shouldKeepWriterSourceUrlsWhenCitationIssuesAreStillRepairable`：expected `PARTIAL_SOURCE`，actual `FULL_SOURCE`。
2. `CollectorAgentFieldEvidenceLoopTest.shouldReleaseFieldEvidenceClaimsWhenCollectorAttemptFailsBeforeRetry`：expected counter 2，actual 4。
3. `ReportWriterAgentTest` 两条 citation gap 用例：expected `ERROR`，actual `WARNING`。
4. `Phase1WorkflowIntegrationTest.shouldExecutePhase1WorkflowThroughPauseResumeAndProduceDiagnosedReport`：expected true，actual false。
5. `Phase2WorkflowIntegrationTest` 两条工作流用例：预期 `STOPPED`，实际 `FAILED`。
6. `Phase5EnterpriseDeliveryIntegrationTest.shouldVerifyEnterpriseDeliveryChainFromKnowledgeIngestionToReplayAndExport`：expected `READY`，actual `NEEDS_EVIDENCE`。
7. `Task66CoverageContractRegressionTest.standardReportShouldStillBlockOnPricingAndWeaknesses`：expected `BLOCKER`，actual `WARNING`。
8. `Task66FieldFirstEvidenceLoopSystemTest.standardBilibiliShallowEntryShouldDeepenCoreFeaturesAndPricing`：expected `BLOCKER`，actual `WARNING`。
9. `SectionEvidenceBundleTest.shouldMarkGapWhenSectionHasNoUsableEvidence`：expected true，actual false。
10. `CoverageContractProviderTest.shouldFallbackToResolverWhenPlanSnapshotMissing`：expected `REQUIRED`，actual `OPTIONAL`。
11. `WorkflowFactoryTest.shouldEmbedSourceCandidatesIntoCollectorNodeConfig`：expected 1，actual 2。

6 条 context error 分布于 `ConversationControllerTest` 4 条、`Phase4WorkflowIntegrationTest` 1 条、`Phase5ConversationRoutingIntegrationTest` 1 条。三类首个上下文失败的共同根因均为：默认 Spring composition 创建 `OrchestrationDecisionModelInvoker` 时没有可注入的 `cn.bugstack.competitoragent.llm.ModelGateway` Bean；其余同类用例因 ApplicationContext failure threshold 跳过重复加载并计为 error。

10 条 skipped 均为显式真实 smoke/Stage 2 Provider 测试，包括 `Stage2OrchestrationRealE2ETest`、Provider latency diagnostic、real provider acceptance、provider preflight、Tavily 和 Bilibili real smoke。本步骤未启用真实验收开关，真实 Provider/Tavily 调用增量按执行边界记录为 0，数据库新增 live task 为 0。

- 当前执行步骤：Task 7 Step 3 失败，等待失败分类与修复
- 已完成步骤占比：Task 7 2/8（25%）
- 剩余步骤：关闭 Step 3 的 13 failures / 6 errors 并重新完成完整 backend 回归；Step 4-8
- 步骤执行状态：Step 1-2 成功；Step 3 失败；Step 4-8 阻断
- 失败类型：完整回归中的产品兼容/测试契约失败与 Spring composition 缺失依赖；不是外部 Provider 环境失败
- 下一步唯一动作：先对 19 条失败/错误按共同根因分组，确定生产回归与失效测试契约边界；修复并完成 Step 3 前不得进入 E 层 Live E2E

### 14.8 Step 3 失败修复与完整 backend 回归完成记录

当前阶段：Task 7 Step 3 已完成，在 Step 4 前停止

- [x] 信息采集：19 条原始失败/错误及后续显露的一条同类旧断言均已取得根因证据
- [x] 数据分析：产品回归、失效测试契约、旧 Collector 测试夹具和 Spring 测试隔离四类根因已分开处理
- [x] 报告撰写：修复范围、14 类联合回归、完整回归和真实调用边界已记录
- [x] 质检复核：完整命令退出码与 315 个 Surefire XML 汇总一致，未进入 Step 4

修复边界：

1. 唯一生产行为修复位于 `CitationAgent.resolveEvidenceState(...)`：新增消费 `writerEvidenceState`，保留上游 `PARTIAL_SOURCE`，不再仅因当前输出存在 URL 就错误升级为 `FULL_SOURCE`。
2. `pricing/strengths/weaknesses` 继续保持阶段1 `OPTIONAL/WARNING`；自动生成结论缺口继续保持 audit/rewrite-only；没有恢复旧的 `REQUIRED/BLOCKER/ERROR` 门槛。
3. Phase2 的 `FAILED` 不是并行 `nodeMap` 过期。测试桩缺少真实 Collector 已提供的 `readyForQuorum`，且仅有 2 个同域 URL，未满足 5 URL / 2 域名红线。夹具补齐正式 quorum 输出后，两条用例恢复到预期的人工 `STOPPED -> resume -> SUCCESS` 链路；没有修改 `DagExecutor` 或 quorum 门槛。
4. 三个 Spring 测试类显式 mock `OrchestrationDecisionModelInvoker`，避免其既有 `@MockBean LlmClient` 替换 `ModelGateway` 后破坏与测试目标无关的 Orchestrator composition。
5. 其余失败均为旧断言或旧夹具未同步当前生产契约，包括 collector 四轮搜索计数、Writer citation warning、Phase1/Phase2 生成结论非 blocker、Phase5 单来源 `NEEDS_EVIDENCE`、coverage optional、两条计划候选数量等。

受影响 14 类联合回归：

```powershell
mvn -pl backend "-Dtest=CitationAgentRepairabilityTest,CollectorAgentFieldEvidenceLoopTest,ReportWriterAgentTest,Phase1WorkflowIntegrationTest,Phase2WorkflowIntegrationTest,Phase5EnterpriseDeliveryIntegrationTest,Task66CoverageContractRegressionTest,Task66FieldFirstEvidenceLoopSystemTest,SectionEvidenceBundleTest,CoverageContractProviderTest,WorkflowFactoryTest,ConversationControllerTest,Phase4WorkflowIntegrationTest,Phase5ConversationRoutingIntegrationTest" test
```

结果：`38 tests / 0 failures / 0 errors / 0 skipped`，Maven 退出码 0。

完整回归仍使用原始命令：

```powershell
mvn -pl backend test
```

正式隔离结果：Maven 退出码 0，总耗时 340.9 秒；315/315 个 Surefire XML 可解析，时间范围为 2026-07-21 11:14:40 至 11:20:09，suite time 合计 328.430 秒。汇总为 `1413 tests / 0 failures / 0 errors / 10 skipped`。

10 条 skipped 仍全部来自显式真实 smoke/Stage 2 Provider 测试：Bilibili 3 条、Tavily 2 条、Stage 2 real E2E 1 条、Provider latency diagnostic 1 条、real provider acceptance 2 条、provider preflight 1 条。本轮未启用对应真实验收开关，真实 Provider/Tavily 调用增量为 0，数据库新增 live task 为 0。

- 当前执行步骤：Task 7 Step 3 已完成
- 已完成步骤占比：Task 7 3/8（37.5%）
- 剩余步骤：Step 4-8
- 步骤执行状态：Step 1-3 成功；Step 4-8 待执行
- 下一步唯一动作：等待用户确认后进入 Task 7 Step 4；本轮在 Step 3 停止

### 14.9 Step 4 E 层全真实基础设施 Live E2E 启动记录

当前阶段：Task 7 Step 4.1 环境与预算冻结执行中，尚未启动应用或创建任务

- [x] 信息采集：9093、dev PostgreSQL/Redis/RocketMQ、凭证布尔状态、数据库基线与工具链版本已冻结
- [ ] 数据分析：等待应用 health、安全配置校验和真实任务结果
- [ ] 报告撰写：执行中的问题、taskId、调用与 token 事实将在本节后续追加
- [ ] 质检复核：只允许创建并执行一个任务，失败后不自动 resume/retry/rerun，不进入 Step 5

冻结时间：`2026-07-21 13:34:23 +08:00`；Java `17.0.3.1`；Maven `3.9.9`。`9093` 未监听。Docker 中 PostgreSQL、Redis、RocketMQ NameServer 均为 healthy，Broker 正在运行且未配置容器 healthcheck；TCP 实测 `5432/16379/9876/10911` 均可达。

预检问题记录：首次按常见默认端口探测 Redis `6379` 得到不可达；核对项目配置后确认 dev 使用 `16379`，实际可达，因此这是预检端口假设错误，不是 Redis 故障。`DEEPSEEK_API_KEY` 环境变量已配置；`TAVILY_API_KEY` 环境变量未配置，但当前应用存在有效配置入口。后者作为凭证来源不规范问题保留，启动后仍必须由应用安全校验与真实调用证明可用，且任何记录不得输出 key。

PostgreSQL 执行前基线：

```text
analysis_task|max_id=111|count=55
task_plan|max_id=81|count=52
task_node|max_id=1151|count=598
task_workflow_event|max_id=1614|count=1074
ai_call_audit_record|max_id=1137|count=818
evidence_source|max_id=818|count=329
competitor_knowledge|max_id=126|count=42
report|max_id=132|count=26
organization_quota_snapshot|count=0|fingerprint=EMPTY
```

调用边界冻结：仅一个 Notion + Airtable 新任务；不做人工重跑。正常主链预计 5-7 次业务 Agent LLM 调用，Orchestrator 每个 cycle 首次调用最多叠加一次 parse retry；Extractor/Reviewer 仍受各自既有 JSON 与节点重试上限约束。preview 后必须记录实际 collector/pipeline 数并更新理论上界；执行过程中不得提高 Tavily 预算、节点重试、质量阈值或决策门槛。

- 当前执行步骤：Task 7 Step 4.1
- 已完成步骤占比：Task 7 3/8（37.5%）
- 剩余步骤：Step 4.1 readiness、Step 4.2-4.3 唯一任务；Step 5-8
- 步骤执行状态：Step 1-3 成功；Step 4 执行中；Step 5-8 待执行
- 当前真实 Provider/Tavily 调用增量：0
- 当前数据库新增 live task：0

启动问题 1：首次后台 `spring-boot:run` 在 Maven 参数解析阶段退出。PowerShell `Start-Process` 将包含空格的 `spring-boot.run.arguments` 拆分，`--orchestration.decision.shadow.enabled=false` 被 Maven 识别为非法选项。该进程未进入 Spring Boot、未监听 9093、未连接 Provider、未创建任务，真实 Provider/Tavily 与数据库增量均为 0。后续改为仅注入本次子进程的 `ORCHESTRATION_DECISION_MODE=LLM_PRIMARY`、`ORCHESTRATION_DECISION_SHADOW_ENABLED=false`，保持仓库默认配置不变并消除引号歧义。

readiness 结果：第二次启动成功，Maven launcher PID=`29820`，Spring Boot PID=`26040`。Tomcat 绑定 9093，health 为 HTTP 200 / `UP`；PostgreSQL 连接与 31 条 Flyway migration 校验通过；RocketMQ producer 初始化成功，真实 listener container 以 `CLUSTERING` 模式订阅 `task-workflow-events`；6 类 Agent capability 为 `COLLECTOR/EXTRACTOR/ANALYZER/WRITER/REVIEWER/CITATION`；搜索安全校验报告 Tavily configured=true。没有 Spring Bean 装配或基础设施 hard fail。

验证问题 2：首次 readiness 脚本收到的 `Invoke-WebRequest.Content` 是 UTF-8 字节数组，日志显示为数字序列，字符串正则因此误报 `READINESS=False`；显式 UTF-8 解码后同一接口为 `{"status":"UP","groups":["liveness","readiness"]}`。这是验收脚本解码问题，不是应用 health 失败；未因此重启应用或创建任务。

preview 结果：HTTP/API 成功，`TASK_PLAN_PREVIEW_V1` 展开 2 个竞品、6 个 Collector、8 个 pipeline，共 14 节点与 20 个计划 sourceUrls；preview 前后 `analysis_task=55`、`ai_call_audit_record=818`，确认只读。基于实际 DAG 冻结业务 LLM 理论上界：正常主链 5 次，含改写 7 次；若 Extractor/Reviewer JSON 修复全部触顶约 15 次，另加每个 Orchestrator cycle 最多 2 次。产品节点自动重试仍受计划配置约束，但本次禁止任何人工 resume/retry/rerun。

唯一任务已创建：`taskId=112`，初始 `PENDING`，`currentPlanVersion=1`、active `planId=82`；PostgreSQL 已落入 14 个 `PENDING` 节点和 3 条初始事件（TASK_CREATED、COLLABORATION_PLAN_RECORDED、COLLABORATION_CHECKPOINT_UPDATED）。创建后 `analysis_task=56/maxId=112`，AI audit 仍为 818。下一步只允许调用一次 execute，并从调用时刻使用固定 40 分钟 deadline。

### 14.10 Step 4 E 层 Live E2E 完成记录

当前阶段：Task 7 Step 4 已按诚实停点口径完成，带问题通过，在 Step 5 前停止

- [x] 信息采集：真实 9093/dev/PostgreSQL/Redis/RocketMQ/Tavily/全业务 Agent/双 DeepSeek 模型事实已取得
- [x] 数据分析：终态、节点、MQ、V2、报告、来源、AI audit/token、只读零增量与问题分组均已核对
- [x] 报告撰写：taskId 112、完整时间线、调用成本和 6 类过程问题已记录
- [x] 质检复核：只创建并执行一个任务，未 resume/retry/rerun，未修改预算、Prompt、Parser、Policy 或质量阈值，未进入 Step 5

唯一 execute 时间为 `2026-07-21 13:43:18 +08:00`，冻结 deadline 为 `14:23:18 +08:00`。任务在 `13:52:18` 收口，实际耗时约 9 分钟，数据库终态 `STOPPED`，原因为存在等待人工处理节点。节点状态为 `7 SUCCESS + 6 SUCCESS_DEGRADED + 1 WAITING_INTERVENTION`，等待节点是 `quality_check_final`；14 个节点均无 retry、无孤儿 RUNNING。任务 API `canResume=true`、`canViewReport=true`，本次按约束未 resume。

真实 MQ 闭环：35 条 task workflow event 全部 CONSUMED，包括 TASK_CREATED 1、TASK_EXECUTION_REQUESTED 1、NODE_READY 14、NODE_COMPLETED 14、ORCHESTRATION_DECISION_RECORDED 3、协作 plan/checkpoint 各 1。执行由真实 RocketMQ consumer thread 接管，未使用同步替身。

真实采集与持久化：6 个 Collector 均留下不同 Tavily requestId，证明 6 个分支真实调用；3 个分支保留 Tavily raw content。任务落入 9 条 evidence、2 条 competitor knowledge、1 条 report；证据分布 Airtable=8、Notion=1。6 个 Collector 全部因 `HARD_DEADLINE_REACHED` 进入 SUCCESS_DEGRADED，其中 3 个 readyForQuorum=true。报告存在且正文非空，qualityScore=38、qualityPassed=false、deliveryStatus=REVIEW_REQUIRED、readyForDelivery=false、blockerCount=0、evidenceGapCount=0、delivery sourceUrls=9。按 §24 的 E 层口径，质量分数不是硬门，当前属于来源与质量事实诚实可见的人工停点。

真实 AI 编排：数据库存在 3 个 ORCHESTRATION_TRACE_V2 cycle，均为 `LLM_PRIMARY` 且 Policy allowed。write_report 与 rewrite_report 分别形成 `SUPPLEMENT_EVIDENCE + READY + APPEND_NODES`；quality_check_final 经一次 parse retry 形成 `WAIT_FOR_HUMAN + CONFIRMATION_REQUIRED + MARK_WAITING_INTERVENTION`。终审 decisionId=`od-112-quality_check_final-llm-1`，aiAuditTraceId=`orch-81dcba65-655b-42dd-95b0-0b9e12c0e1e6`。report/replay 投影最新终审决策；公开 Markdown/HTML 均包含同一 decisionId、`LLM_PRIMARY`、traceId 和来源，不含 rawPrompt、rawResponse、Authorization 或 api-key 字样。

AI 调用与 token：task 112 共 45 条 audit。其中 CHAT 11 条且全部成功，actual input=1,715,091、output=15,947、total=1,731,038；业务 `deepseek-v4-pro` 7 次占 1,720,764，Orchestrator `deepseek-chat` 4 次占 10,274。按节点 actual total：Extractor 两次合计 33,610；Analyzer 37,236；首稿 Writer 134,277；初审 Reviewer 186,406；改写 Writer 457,308；终审 Reviewer 871,927；四次 Orchestrator 合计 10,274。其余 34 条均为失败 embedding audit，无 actual token：DeepSeek 14 次 HTTP_404、SiliconFlow fallback 14 次 HTTP_401、熔断跳过 6 次。

只读零副作用：调用 task/nodes/report/replay/Markdown/HTML 前后，task 112 的 AI audit 始终 45、actual token 始终 1,731,038，organization quota fingerprint 始终 EMPTY；公开下载未创建正式 export record，符合其无副作用接口边界。最终数据库关联计数为 task=1、plan=1、nodes=14、attempts=14、events=35、audits=45、evidence=9、knowledge=2、report=1、formal export record=0。

问题清单：

1. `COST_GOVERNANCE_DISABLED`：`ai.budgetEnabled=false`，默认单次预计输入上限 12,000 未生效；11 条成功 CHAT 均记录 `BUDGET_DISABLED`。实际 1.731M token 远超执行前 100k 建议预算，且 Prompt 从首稿约 131k input 膨胀到终审约 870k input。这是本轮最严重问题，Step 4 不因既定诚实停点口径失败，但后续真实运行前必须优先治理。
2. `EMBEDDING_PROVIDER_MISCONFIGURED`：DeepSeek embedding endpoint 返回 404，SiliconFlow fallback 返回 401，随后 circuit open；34 次失败 audit 增加噪声与调用尝试，embedding 能力实际不可用。
3. `COLLECTOR_HARD_DEADLINE_DEGRADATION`：6/6 Collector 均触发 hard deadline；虽然 Tavily 真实参与且主链可继续，但采集质量显著降级。
4. `EVIDENCE_DISTRIBUTION_IMBALANCE`：Airtable 8 条证据，Notion 仅 1 条，报告质量只有 38 分并进入人工终审。
5. `SUGGESTION_MUTATION_OWNERSHIP_GAP`：Writer 两次得到 `READY/APPEND_NODES`，但 `AgentSuggestion` gate 当前只执行人工暂停，`DynamicPlanAppender` 只处理终审 Reviewer，因此计划仍为 version 1、dynamic node=0。决策审计与实际 mutation ownership 的语义需后续收敛；这不是 MQ 丢消息。
6. `TASK_READ_MODEL_STARTED_AT_ABSENT`：数据库 `analysis_task.started_at` 有值，但公开 task 详情 DTO 不提供 startedAt，只能通过数据库和节点时间线核对启动时刻。

验收脚本自身还记录两项非产品问题：首次后台启动参数被 PowerShell 拆分；首次 health 正文未显式 UTF-8 解码。二者均在创建任务前修正，没有真实调用或数据库增量。

本轮只停止自身 PID 26040/29820，二者均已退出，9093 已释放；PostgreSQL task 112 及全部关联事实保留。

- [x] Task 7 Step 1：67/67
- [x] Task 7 Step 2：133/133 + 148/148
- [x] Task 7 Step 3：1413 tests / 0 failures / 0 errors / 10 skipped
- [x] Task 7 Step 4：`PASSED_WITH_RECORDED_ISSUES`，真实 taskId=112，STOPPED/WAITING_INTERVENTION 诚实停点
- [ ] Task 7 Step 5-8：待执行

- 当前执行步骤：Task 7 Step 4 已完成
- 已完成步骤占比：Task 7 4/8（50%）
- 剩余步骤：Step 5 安全扫描、Step 6 clean package、Step 7 最终 JAR 持久化复核、Step 8 零遗留审计
- 下一步唯一动作：等待用户确认是否进入 Step 5；本轮不修复上述问题、不自动复验、不进入 Step 5
