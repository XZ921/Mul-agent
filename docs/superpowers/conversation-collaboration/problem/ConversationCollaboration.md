# ConversationCollaboration 诊断

## 结论

1. 对话入口当前真实停点不是“看不到编排决策”，而是“它已经能读取和翻译最近一次编排决策，但仍只是受控动作网关，不是协作 runtime 本身”。`ConversationService` 现在能做解释、预览、确认和少量正式执行，但它依赖最近事件与现有 `TaskRuntimeFacade`，还不能独立解释多轮协作推进状态。
2. Conversation 不应只做静态展示，它已经需要触发受控动作，但动作边界必须继续收敛在既有 `RERUN_NODE / SUPPLEMENT_EVIDENCE / RESUME_TASK` 这类正式入口里。也就是说，它更像“决策展示 + 安全确认网关”，而不是新一层 Orchestrator。
3. 不改架构时，最多只能继续优化意图去歧义、预览文案、确认门槛和最近决策摘要展示；仅改 `ConversationService / ModeRouter` 不能补出 `pendingActions / checkpoint / 多轮分支恢复` 这类 runtime 语义，也无法让对话入口替代编排层重新生成决策。
4. 进入 4.x 时，ConversationCollaboration 不作为首轮前置阻塞项。首轮 4.x 应先稳定主业务链路 `采集 -> 提取 -> 分析 -> 写作 -> Citation -> 质检 -> 修订/重写 -> 交付/审计` 的 runtime contract 和动态协作闭环；对话协同后置为 runtime contract 的消费端、安全确认网关和受控动作入口，读取运行态、解释 Orchestrator 决策、展示待处理动作并触发经过策略校验的动作。

## 代码级证据

1. `IntentRecognitionService` 仍是规则优先路由，核心职责是把解释、补证、动作、表单草稿拆成稳定模式，而不是让黑盒模型直接决定编排。
2. `ModeRouter` 只做极薄的最终收口，尤其把缺 `taskId` 的 `RESEARCH / TASK_ACTION / EXPLAIN` 统一降到 `CLARIFICATION`，说明对话层当前仍以安全路由为主。
3. `ConversationService.resolveLatestDecision()` 明确只读消费最近一次编排决策，查询失败时直接降级，不在这里重算任何编排逻辑。
4. `ConversationService.buildTaskActionResponse()` 与 `buildResearchResponse()` 都会把最近 `ConversationOrchestrationDecisionView` 交给 `TaskActionTranslator`，说明对话入口已接入 3.4，但只是消费端。
5. `TaskActionTranslator.buildDecisionPreview()` 目前只稳定映射 `WAIT_FOR_HUMAN / SUPPLEMENT_EVIDENCE / REWRITE_* / NO_ACTION` 等有限模式，证明它还是受控翻译层，不是完整 runtime。
6. `TaskActionTranslator.buildExecutionPlan()` 只支持 `RERUN_NODE / SUPPLEMENT_EVIDENCE / RESUME_TASK` 三类正式执行对象，动作面仍很窄。
7. `ConversationOrchestrationDecisionQueryService` 只从最近一次 `ORCHESTRATION_DECISION_RECORDED` 事件提取视图，并合并 `evidenceState / sourceUrls`，说明当前会话解释依赖“最近一条决策事件”，而不是完整协作轨迹。
