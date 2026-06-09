# 08 AI Infrastructure And Tooling

## 文档目的

本文档用于定义系统底层 AI 能力治理与工具系统边界，避免模型供应、路由、容错与工具调用逻辑散落在业务代码中。

## 本能力域在系统中的职责

本能力域负责：

1. 为上层业务提供统一 AI 能力接口。
2. 管理多供应商、多模型、重试、熔断和审计。
3. 管理工具注册、权限、超时、回退与 trace。

## 业务目标与用户价值

1. 提高系统稳定性和可运营性。
2. 降低业务层对单一模型供应商的耦合。
3. 让智能体的工具能力在受控边界内可用。

## 当前结论

最终系统有必要形成独立的 `infra-ai` 能力层。

它的目标是：

`对上为业务层提供统一 AI 能力接口，对下屏蔽不同供应商差异，并负责路由、熔断、降级、故障转移与审计。`

## 目标能力

1. 统一 Chat、Embedding、Rerank 能力接口
2. Provider 适配器抽象
3. 策略路由与成本控制
4. 重试、超时、熔断、故障转移
5. AI 调用指标和审计
6. 工具注册、权限和调用治理

## 关键场景

1. 某个模型供应商失败时自动切换到备用供应商。
2. 根据任务节点类型选择不同模型能力。
3. Agent 在上下文内自主调用搜索、浏览器、RAG 或任务动作工具。
4. 系统记录每次 AI 调用的模型、耗时、token 和错误码。

## 核心对象 / 核心模块

### AI 基础设施层

1. `LLMService`
2. `EmbeddingService`
3. `RerankService`
4. `ProviderRegistry`
5. `RoutingPolicy`
6. `CircuitBreaker / FailoverPolicy`
7. `AIAuditLogger`

### 工具系统

1. `Tool Registry`
2. `Tool Permission Control`
3. `Tool Retry`
4. `Tool Timeout`
5. `Tool Fallback`
6. `Tool Trace`
7. `Tool Audit`

## 核心流程 / 状态流 / 编排关系

1. 上层业务请求统一 AI 接口。
2. `RoutingPolicy` 根据任务类型、节点类型、成本和稳定性选择合适 Provider。
3. `Reliability Layer` 负责超时、重试、熔断和故障转移。
4. `Observability Layer` 持久化审计与指标。
5. Agent 通过工具目录申请工具调用。
6. 权限与 Guardrail 决定是否放行，并记录 trace。

## 关键约束与设计原则

1. `业务层不直连单一 SDK`
   业务代码应依赖统一接口而不是具体供应商。
2. `AI 基础设施与工具系统分层`
   模型能力供应与外部工具调用是两类不同职责。
3. `工具自主调用必须受控`
   不是放任式 Agent，而是可自主调用但运行在权限边界内。
4. `容错是默认要求`
   所有外部模型与工具依赖都应有异常处理与重试机制。

## 与其他能力域的边界

1. 本文档不定义任务图编排策略，详见 `04-agent-runtime-and-workflow-architecture.md`。
2. 本文档不定义业务证据规则，详见 `09-quality-evidence-and-audit.md`。
3. 本文档与对话、RAG、Agent Runtime 深度协作，但不替代它们的业务语义。

## 扩展预留

1. 后续可扩展 Vision、OCR、Transcription 等多模态能力接口。
2. 后续可增加供应商评分、限流策略和成本预算控制面板。
3. 后续可扩展更丰富的工具市场与连接器能力。

## 待确认问题

1. 是否需要把工具系统和连接器系统在未来正式拆分。
2. 是否需要为不同组织或租户提供独立的 Provider 路由策略。

## 本阶段不约束的内容

1. 不要求当前版本立即完成所有 AI 能力类型统一。
2. 不要求工具系统一开始就支持开放式第三方插件生态。
