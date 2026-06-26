# Agent Collaboration Orchestration P3-4 Citation Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增独立 `Citation Agent`，在 Reviewer 之前完成报告引用覆盖、证据编号有效性和来源可信度核查，并把引用缺口按标准 `AgentSuggestion -> OrchestrationDecision -> trace/replay` 链路交给 Orchestrator 裁决。

**Architecture:** P3-4 采用固定模板节点，而不是自由规划器。后端新增 `AgentType.CITATION`、`citation_check` 与 `citation_check_revision` 两个受控节点，把 `write_report -> quality_check` 改为 `write_report -> citation_check -> quality_check`，把修订链路改为 `rewrite_report -> citation_check_revision -> quality_check_final`。`CitationAgent` 只做可复现的引用解析、证据编号校验和来源可信度规则评分；不调用外部抓取、不调用 LLM、不改写报告正文；缺口由 `CitationSuggestionAssembler` 转成标准协作建议，再由 `OrchestrationDecisionService / DagExecutor` 记录决策或阻断下游 Reviewer。

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JPA, JUnit 5, Mockito, AssertJ, Maven

---

## Source Context

1. 最新进度：`docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task6-progress.md` 已明确 P3-3 无剩余开发任务，后续阶段为 `P3-4 Citation Agent 引用核查与来源可信度验证`。
2. P3-2 基线：`docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-2-task5-progress.md` 已完成 Writer 章节引用缺口的最小闭环，但明确剩余为完整 Citation Agent 与逐句引用核查。
3. 架构规格：`docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md` P3 第 4 项要求 `Citation Agent 独立核查引用覆盖和来源可信度`，同时强调 3.4 不允许降低 `sourceUrls / evidenceState` 红线。
4. 总蓝图：`docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md` 已记录 P3-2/P3-3 状态，并把 P3 剩余收口点指向 Citation Agent。
5. 稳定演示计划：`docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md` 要求报告、诊断、编排决策中能看到 `sourceUrls / evidenceState`，P3-4 负责把“报告文字是否真的引用了可用来源”前置到 Reviewer 之前。

## Approach Decision

| 方案 | 做法 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- | --- |
| A. 固定 DAG 中新增独立 Citation 节点 | 新增 `AgentType.CITATION`，把 `citation_check / citation_check_revision` 插入 Writer 和 Reviewer 之间 | 符合“独立 Citation Agent”；引用核查阻断发生在 Reviewer 前；trace/replay 与对话预览天然复用 P1-P3-3 链路 | 需要更新固定模板、协作计划角色、节点视图、节点名分支和 `executionOrder` | 采用 |
| B. 继续把 Citation 逻辑塞进 Writer | 让 `ReportWriterAgent` 生成报告后继续做逐句引用核查 | 改动少，不增加 DAG 节点 | 与 P3-4 “独立 Citation Agent”冲突；Writer 会同时承担写作、质检和编排输入，职责膨胀 | 不采用 |
| C. Citation Agent 调用外部网页/LLM 做二次核验 | 对每个引用 URL 二次抓取或调用模型判定句子支撑度 | 长期能力更强 | 不可复现、成本高、容易把 P3-4 扩成搜索采集重开；外部调用还需重试与熔断 | 后续增强，不进本轮 |

P3-4 采用方案 A，并把外部二次抓取、LLM 语义蕴含判断、前端报告页大改版全部后移。第一版必须稳定、可测、可回放。

## Current Stage

当前阶段：P3-4 具体执行计划已写入并完成计划自检；本轮只定义独立 Citation Agent 的最小可运行闭环，待执行实现。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 冻结 Citation 核查契约、声明解析器和来源可信度策略 | 0.5 天 | P3-2 已产出 Writer 报告与引用缺口元数据 |
| Task 2 | 新增 `CitationAgent`，输出引用覆盖和来源可信度核查结果 | 0.5 天 | Task 1 契约通过 |
| Task 3 | 新增 `CitationSuggestionAssembler` 并接入 Orchestrator 决策 | 0.5 天 | Task 2 输出稳定 |
| Task 4 | 把 Citation 作为独立角色和固定 DAG 节点接入工作流 | 1 天 | Task 2-3 局部测试通过 |
| Task 5 | 补 replay、smoke 和 P1-P3-4 聚合验证 | 0.5 天 | Task 4 工作流模板通过 |
| Task 6 | 文档回链与进度持久化 | 0.5 天 | 自动化验证通过 |

## Scope Guard

### P3-4 必须完成

1. 新增 `AgentType.CITATION`，并保证 `SpringAgentCapabilityRegistry` 可注册 `CitationAgent`。
2. 新增固定 DAG 节点：
   - `citation_check` 依赖 `write_report`，`quality_check` 依赖 `citation_check`；
   - `citation_check_revision` 依赖 `rewrite_report`，`quality_check_final` 依赖 `citation_check_revision`；
   - `citation_check_revision` 使用 `trigger=rewrite_executed`，只在修订报告实际生成后执行。
3. `CitationAgent` 从 `sourceNode` 读取 `write_report / rewrite_report` 输出，至少核查：
   - 报告正文中的 `[证据：E001]` / `[证据:E001]` 引用编号是否存在；
   - 建议、结论、风险、机会、启示、行动等敏感章节的判断句是否有引用或显式降级；
   - 引用来源是否能回指 `EvidenceSource`；
   - 来源可信度是否满足第一版规则：官方/文档/定价页优先，未知域名、低 `sourceScore`、空 `contentSnippet/fullContent` 必须显式标记风险。
4. Citation 输出必须包含：
   - `citationEvidenceState`
   - `citationRiskSeverity`
   - `citationCoverageRate`
   - `citationIssues`
   - `sourceCredibilityFindings`
   - `sourceUrls`
   - `evidenceState`
   - `issueFlags`
   - `checkedSourceNode`
5. 新增 `CitationSuggestionAssembler`，把 Citation 缺口转换成标准 `AgentSuggestion`，`suggestionType` 统一为 `CITATION_VERIFICATION_GAP`。
6. `OrchestrationDecisionService` 支持 `citation_check / citation_check_revision`：
   - 无引用、未知证据编号、来源缺失进入 `WAIT_FOR_HUMAN / MANUAL_REVIEW`；
   - 有来源但弱支撑、低可信来源或覆盖率不足进入 `REWRITE_ONLY / REWRITE_CLAIM`；
   - 无问题输出 `NO_ACTION`。
7. `DagExecutor` suggestion gate 支持 Citation 节点，并继续只记录/阻断，不让 Citation Agent 自己创建动态 DAG。
8. replay/trace 能看到 Citation 触发的 `ORCHESTRATION_DECISION_RECORDED`，保留 `sourceUrls / evidenceState / inputRefs.agentSuggestionIds`。
9. `Conversation` 动作预览无需新增生成逻辑，但必须能通过 P3-3 既有读取最近决策能力展示 Citation 决策。
10. 自动化测试覆盖契约、Agent、Assembler、Orchestrator、WorkflowFactory、DagExecutor、replay 和聚合 smoke。

### Citation 与 Writer 缺口边界

1. `WriterCitationGapInspector` 保留为 P3-2 的 Writer 阶段章节级缺口检测器，继续只基于 `SectionEvidenceBundle / fallbackSourceUrls` 生成粗粒度 `WriterCitationGap`，不删除、不降级、不改造成 Citation Agent。
2. `CitationAgent` 的主输入是 `sourceNode` 指向的 `write_report / rewrite_report` 输出正文，直接解析报告文本中的 `[证据：E001] / [证据:E001]` 引用标记和敏感声明。
3. `CitationAgent` 可以读取 Writer 输出里已有的 `sectionCitationGaps / writerEvidenceState / sourceUrls` 作为上下文和 fallback 来源列表，但不直接调用 `WriterCitationGapInspector`，也不把 Writer 缺口当作最终逐句引用结论。
4. P3-4 落成后，两条链路并存：Writer 缺口用于“章节是否已有可回指证据束”的早期粗检，Citation 用于“报告正文声明是否真正带有有效引用和可信来源”的 Reviewer 前细检。
5. 中文注释统一解释规则边界和原因，例如“为什么这里按 MISSING_SOURCE 阻断”，避免只复述代码正在做什么。

### P3-4 明确不做

1. 不调用外部网页抓取工具做二次内容核验。
2. 不调用 LLM 做逐句语义蕴含判断。
3. 不自动改写报告正文；改写仍由 Writer / rewrite 链路承担。
4. 不新增数据库表；Citation 结果只进入节点 `outputData`、workflow event、trace/replay。
5. 不把 `ExecutionPlanDefinitionBuilder` 改成自由智能规划器，只扩展固定 DAG 模板。
6. 不重开搜索与采集、Extractor、Analyzer 专题。
7. 不做大规模前端重设计；必要时只补现有节点视图对 `CITATION` 的配置摘要。

## Risk Register

| 风险 | 影响 | 处理要求 |
| --- | --- | --- |
| Citation 节点名分支遗漏导致输出被静默跳过 | 当前 `DagExecutor.buildAgentSuggestions()` 与 `OrchestrationDecisionService.decide()` 主要依赖节点名 if-else 分支；若遗漏 `citation_check / citation_check_revision`，Citation 输出会被当作无建议处理 | Task 3 必须覆盖 `DagExecutor` suggestion gate 和 `OrchestrationDecisionService` Citation 分支；Task 5 必须用 replay/smoke 证明 `ORCHESTRATION_DECISION_RECORDED` 可见 |
| 引用核查误把“推测，需补充证据”判为错误 | Writer 已经显式降级的假设会被误阻断 | `CitationClaimExtractor` 必须识别“当前公开资料未能验证、需补充证据、待验证、低置信度”等降级短语 |
| Citation 节点阻断后 Reviewer 永远不执行 | 符合预期，但用户需要可解释原因 | `WAITING_INTERVENTION` 节点必须写入 `interventionReason`，trace/replay 必须带 `citationIssues / sourceUrls / evidenceState` |
| `executionOrder` 调整遗漏导致计划校验或执行顺序异常 | `WorkflowPlanValidator` 要求依赖节点的 `executionOrder` 必须更早且不能重复；插入两个 Citation 节点后，下游固定节点顺序必须整体后移 | Task 4 的 `WorkflowFactoryTest` 必须断言所有节点 `executionOrder` 连续、唯一，并满足 `write_report < citation_check < quality_check < rewrite_report < citation_check_revision < quality_check_final` |
| 修订链路 trigger 语义被误改导致 `quality_check_final` 误跳过 | 现有 `DagExecutor` 已支持 `trigger=review_failed / rewrite_executed`，但 `rewrite_executed` 语义依赖 `rewrite_report` 成功；插入 `citation_check_revision` 后必须保留这个条件并叠加新依赖 | Task 4 先运行现有 `DagExecutorTest#shouldClassifyFinalQualityGateFailureAsDownstreamConsumptionGap` 锁定 trigger 基线，再用 `WorkflowFactoryTest` 锁定 `rewrite_report -> citation_check_revision -> quality_check_final` 顺序 |
| `buildStages()` 未覆盖 Citation 节点 stage | 新节点若使用未知 stageCode 会被 `WorkflowPlanValidator` 拦截；若阶段文案不更新，预览会看不出 Reviewer 前新增引用核查 | P3-4 不新增 stage；`citation_check / citation_check_revision` 统一使用现有 `DELIVER` stage，并在 `buildStages()` 的 DELIVER summary/detail 中加入引用核查说明 |
| 来源可信度第一版过度承诺 | 规则评分不能证明网页内容绝对真实 | 输出字段命名为 `sourceTrustTier / sourceCredibilityFindings`，文档明确第一版是规则可信度，不是外部事实复核 |
| 对话动作预览不能解释 Citation 决策 | 用户看不到为什么被阻断 | P3-3 读取最近决策已通用；P3-4 smoke 必须用 Citation 决策样本验证 `taskActionPreview.orchestrationDecision` 可见 |

## File Structure

### Create

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationClaim.java`
  - 报告中的单句声明及其引用编号事实。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationIssue.java`
  - Citation Agent 发现的引用覆盖、未知证据、来源可信度问题。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationSourceTrustFinding.java`
  - 单个来源的可信度规则评分结果。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationCheckResult.java`
  - Citation Agent 输出总契约。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationClaimExtractor.java`
  - 解析报告正文中的敏感声明、证据编号和显式降级语句。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationSourceTrustPolicy.java`
  - 基于 `EvidenceSource` 元数据做第一版来源可信度评分。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationAgent.java`
  - 独立 Citation Agent。
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CitationSuggestionAssembler.java`
  - 把 Citation 缺口转换为 `AgentSuggestion`。
- `backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationClaimExtractorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationSourceTrustPolicyTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CitationSuggestionAssemblerTest.java`

### Modify

- `backend/src/main/java/cn/bugstack/competitoragent/model/enums/AgentType.java`
  - 新增 `CITATION`。
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanService.java`
  - 新增 Citation 角色，Reviewer 依赖 Citation。
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewService.java`
  - 允许并要求 `CITATION` 角色。
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewServiceTest.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
  - 插入 `citation_check / citation_check_revision` 固定节点。
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
  - 锁定新 DAG 顺序和配置摘要。
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskPlanPreviewAssembler.java`
  - 支持 `CITATION` 配置摘要。
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
  - 支持 `CITATION` 配置摘要和节点视图。
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
  - 把 `CITATION` 纳入业务运行节点投影。
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
  - 支持 Citation suggestion 决策。
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
  - 注入 `CitationSuggestionAssembler`，扩展 suggestion gate。
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`
- `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- `docs/superpowers/agent-collaboration-orchestration/task/2026-06-25-agent-collaboration-orchestration-p3-4-citation-agent-implementation-plan.md`

### Reference Only

- `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/WriterCitationGap.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/entity/EvidenceSource.java`
- `backend/src/main/java/cn/bugstack/competitoragent/repository/EvidenceSourceRepository.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AgentSuggestion.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/EvidenceState.java`
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java`

---

## Task 1: 冻结 Citation 核查契约、声明解析器和来源可信度策略

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationClaim.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationIssue.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationSourceTrustFinding.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationCheckResult.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationClaimExtractor.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationSourceTrustPolicy.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationClaimExtractorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationSourceTrustPolicyTest.java`

- [ ] **Step 1: 写 CitationClaimExtractor 红灯测试**

Create `CitationClaimExtractorTest`:

```java
package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.workflow.contract.CitationClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationClaimExtractorTest {

    private final CitationClaimExtractor extractor = new CitationClaimExtractor();

    @Test
    void shouldExtractEvidenceIdsFromChineseEvidenceMarks() {
        String report = """
                # 竞品分析报告
                ## 定价策略
                Notion AI 采用按席位计费，并在企业版中提供高级安全能力。[证据：E001][证据:E002]
                """;

        List<CitationClaim> claims = extractor.extract(report);

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).getClaimText()).contains("按席位计费");
        assertThat(claims.get(0).getEvidenceIds()).containsExactly("E001", "E002");
        assertThat(claims.get(0).isTraceabilitySensitive()).isTrue();
        assertThat(claims.get(0).isExplicitlyDowngraded()).isFalse();
    }

    @Test
    void shouldFlagSensitiveClaimWithoutCitationUnlessExplicitlyDowngraded() {
        String report = """
                ## 行动建议
                建议优先学习 Notion AI 的企业权限设计。
                该判断为推测，当前公开资料未能验证，需补充证据。
                """;

        List<CitationClaim> claims = extractor.extract(report);

        assertThat(claims).hasSize(2);
        assertThat(claims.get(0).getEvidenceIds()).isEmpty();
        assertThat(claims.get(0).isTraceabilitySensitive()).isTrue();
        assertThat(claims.get(0).isExplicitlyDowngraded()).isFalse();
        assertThat(claims.get(1).isExplicitlyDowngraded()).isTrue();
    }
}
```

Run: `mvn -pl backend "-Dtest=CitationClaimExtractorTest" test`

Expected: FAIL with `cannot find symbol: class CitationClaimExtractor`.

- [ ] **Step 2: 写 CitationSourceTrustPolicy 红灯测试**

Create `CitationSourceTrustPolicyTest`:

```java
package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.workflow.contract.CitationSourceTrustFinding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSourceTrustPolicyTest {

    private final CitationSourceTrustPolicy policy = new CitationSourceTrustPolicy();

    @Test
    void shouldTreatOfficialDocsPricingAsHighTrust() {
        EvidenceSource evidence = EvidenceSource.builder()
                .evidenceId("E001")
                .url("https://www.notion.so/pricing")
                .sourceDomain("www.notion.so")
                .sourceType("PRICING")
                .sourceCategory("OFFICIAL")
                .sourceScore(0.91)
                .contentSnippet("Notion pricing plans include Plus, Business and Enterprise.")
                .build();

        CitationSourceTrustFinding finding = policy.evaluate(evidence);

        assertThat(finding.getTrustTier()).isEqualTo("HIGH_TRUST");
        assertThat(finding.getIssueFlags()).isEmpty();
        assertThat(finding.getSourceUrls()).containsExactly("https://www.notion.so/pricing");
    }

    @Test
    void shouldFlagUnknownOrThinSourceAsLowTrust() {
        EvidenceSource evidence = EvidenceSource.builder()
                .evidenceId("E009")
                .url("https://mirror.example.net/notion-ai")
                .sourceDomain("mirror.example.net")
                .sourceType("UNKNOWN")
                .sourceCategory("AI_DISCOVERED")
                .sourceScore(0.31)
                .contentSnippet("")
                .fullContent("")
                .build();

        CitationSourceTrustFinding finding = policy.evaluate(evidence);

        assertThat(finding.getTrustTier()).isEqualTo("LOW_TRUST");
        assertThat(finding.getIssueFlags()).contains("LOW_SOURCE_SCORE", "THIN_SOURCE_CONTENT", "UNKNOWN_SOURCE_TYPE");
    }
}
```

Run: `mvn -pl backend "-Dtest=CitationSourceTrustPolicyTest" test`

Expected: FAIL with `cannot find symbol: class CitationSourceTrustPolicy`.

- [ ] **Step 3: 创建 Citation 契约类**

Implement the four contract classes with Lombok `@Data`, `@Builder(toBuilder = true)`, `@NoArgsConstructor`, `@AllArgsConstructor`.

Required fields:

`CitationClaim`:
- `claimId`
- `sectionKey`
- `sectionTitle`
- `claimText`
- `evidenceIds`
- `sourceUrls`
- `traceabilitySensitive`
- `explicitlyDowngraded`
- `issueFlags`

`CitationIssue`:
- `issueId`
- `issueType`
- `severity`
- `targetSection`
- `claimId`
- `evidenceId`
- `summary`
- `sourceUrls`
- `evidenceState`
- `suggestedQueries`
- `issueFlags`

`CitationSourceTrustFinding`:
- `evidenceId`
- `url`
- `sourceDomain`
- `sourceType`
- `sourceCategory`
- `sourceScore`
- `trustTier`
- `summary`
- `sourceUrls`
- `issueFlags`

`CitationCheckResult`:
- `contractVersion`
- `checkedSourceNode`
- `citationEvidenceState`
- `citationRiskSeverity`
- `citationCoverageRate`
- `claims`
- `citationIssues`
- `sourceCredibilityFindings`
- `sourceUrls`
- `evidenceState`
- `issueFlags`

Every class must provide `normalized()` and must enforce this invariant: when `sourceUrls` is empty, either `evidenceState=MISSING_SOURCE` or an issue flag such as `MISSING_SOURCE_URL / UNKNOWN_EVIDENCE_ID` must be present.

`normalized()` rules:

1. Trim text fields and convert blank strings to stable defaults, for example `targetSection=report`, `severity=NONE`, `evidenceState=NOT_APPLICABLE` or `MISSING_SOURCE` according to source availability.
2. Normalize list fields to non-null immutable lists using `List.copyOf(...)` after removing blank values and duplicates with insertion order preserved.
3. Normalize enum-like strings to uppercase for `severity / evidenceState / trustTier / issueFlags`, while preserving human-readable `summary / claimText` wording.
4. Do not mutate the current instance; return `toBuilder()...build()` like existing contract classes.

- [ ] **Step 4: 实现 CitationClaimExtractor**

Implementation requirements:

1. Split report content by lines, track current Markdown heading as `sectionTitle` and normalized `sectionKey`.
2. Split claim candidates by Chinese and English sentence punctuation.
3. Extract evidence ids with regex: `\\[证据[:：]\\s*([A-Za-z0-9_-]+)]`.
4. Mark `traceabilitySensitive=true` when section title or sentence contains one of:
   - `建议`
   - `结论`
   - `风险`
   - `机会`
   - `启示`
   - `行动`
   - `应该`
   - `必须`
   - `优先`
5. Mark `explicitlyDowngraded=true` when sentence contains one of:
   - `当前公开资料未能验证`
   - `公开资料未能验证`
   - `需补充证据`
   - `待验证`
   - `低置信度`
   - `推测`

Add Chinese comments above the regex block and the downgrade detection block.

- [ ] **Step 5: 实现 CitationSourceTrustPolicy**

Implementation requirements:

1. `HIGH_TRUST` when `sourceType` is `OFFICIAL / DOCS / PRICING / GITHUB` or `sourceCategory=OFFICIAL`, and no low-score/thin-content risk exists.
2. `MEDIUM_TRUST` when source type is known and content is not thin.
3. `LOW_TRUST` when source score `< 0.45`, source type is blank/unknown, URL is blank, or both `contentSnippet` and `fullContent` are blank.
4. Issue flags:
   - `MISSING_SOURCE_URL`
   - `UNKNOWN_SOURCE_TYPE`
   - `LOW_SOURCE_SCORE`
   - `THIN_SOURCE_CONTENT`

- [ ] **Step 6: 运行 Task 1 测试**

Run:

```bash
mvn -pl backend "-Dtest=CitationClaimExtractorTest,CitationSourceTrustPolicyTest" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationClaim.java backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationIssue.java backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationSourceTrustFinding.java backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationCheckResult.java backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationClaimExtractor.java backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationSourceTrustPolicy.java backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationClaimExtractorTest.java backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationSourceTrustPolicyTest.java
git commit -m "feat: add citation verification contracts"
```

---

## Task 2: 新增 CitationAgent

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/enums/AgentType.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationAgentTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/capability/SpringAgentCapabilityRegistryTest.java`

- [ ] **Step 1: 写 CitationAgent 红灯测试**

Create `CitationAgentTest` with three cases:

1. `shouldPassWhenClaimsHaveKnownEvidenceAndTrustedSources`
   - Given `write_report` output contains `content` with `[证据：E001]`.
   - Given repository returns `E001` with official pricing URL and non-empty snippet.
   - Expect output has `citationRiskSeverity=NONE`, `citationEvidenceState=FULL_SOURCE`, no `citationIssues`.

2. `shouldEmitMissingCitationIssueWhenSensitiveClaimHasNoCitation`
   - Given `write_report` output contains a recommendation sentence without evidence and without downgrade.
   - Expect `citationRiskSeverity=ERROR`, `citationEvidenceState=MISSING_SOURCE`, issue type `MISSING_CITATION`.

3. `shouldEmitUnknownEvidenceIssueWhenEvidenceIdDoesNotExist`
   - Given report references `[证据：E999]`.
   - Given repository does not contain `E999`.
   - Expect issue type `UNKNOWN_EVIDENCE_ID`, `evidenceState=MISSING_SOURCE`.

Run: `mvn -pl backend "-Dtest=CitationAgentTest" test`

Expected: FAIL with `cannot find symbol: class CitationAgent`.

- [ ] **Step 2: 新增 AgentType.CITATION**

Modify `AgentType.java`:

```java
@Schema(description = "引用核查 Agent — 检查报告引用覆盖和来源可信度")
CITATION("引用核查 Agent");
```

Adjust the enum commas so `REVIEWER` is no longer the last semicolon entry.

- [ ] **Step 3: 实现 CitationAgent**

Constructor dependencies:

- `AgentExecutionLogRepository`
- `AgentContextAssembler`
- `EvidenceSourceRepository`
- `ObjectMapper`
- `CitationClaimExtractor`
- `CitationSourceTrustPolicy`

Behavior:

1. `getType()` returns `AgentType.CITATION`.
2. `getName()` returns `引用核查智能体`.
3. Read `sourceNode` from `context.getCurrentNodeConfig()`, default to `write_report`.
4. Read writer output from `context.getSharedOutput(sourceNode)`.
5. If writer output is missing, return `AgentResult.failed("缺少待核查报告输出：" + sourceNode)`.
6. Parse `content` from writer output, fallback to raw writer output when it is not JSON.
7. Read `sectionCitationGaps / writerEvidenceState / sourceUrls` from writer output only as context and fallback source hints; do not call `WriterCitationGapInspector`, and do not treat Writer gap metadata as a substitute for report-text citation parsing.
8. Load all `EvidenceSource` by task id and build `evidenceId -> EvidenceSource` map.
9. Run `CitationClaimExtractor.extract(reportContent)`.
10. For each claim:
   - If sensitive and no evidence ids and not downgraded, create `MISSING_CITATION` issue.
   - If evidence id not found, create `UNKNOWN_EVIDENCE_ID` issue.
   - If evidence exists, attach source URL to claim.
11. Run `CitationSourceTrustPolicy` for every referenced evidence and every task evidence.
12. If any trust finding is `LOW_TRUST`, create `LOW_SOURCE_TRUST` issue with `PARTIAL_SOURCE` when URL exists, otherwise `MISSING_SOURCE`.
13. Build `CitationCheckResult`:
    - `citationCoverageRate = supported sensitive claims / sensitive claims`
    - `citationRiskSeverity = ERROR` when missing/unknown source exists, `HIGH` when low trust or coverage below configured `minCoverageRate`, else `NONE`
    - `citationEvidenceState = FULL_SOURCE / PARTIAL_SOURCE / MISSING_SOURCE`
14. Serialize the normalized result and return success.

Add Chinese comments before:

- source node resolution
- evidence id lookup
- missing citation rule
- source trust rule

- [ ] **Step 4: 注册表测试覆盖 Citation**

Append to `SpringAgentCapabilityRegistryTest`:

```java
@Test
void shouldResolveCitationAgentCapability() {
    Agent citationAgent = new StubAgent(AgentType.CITATION);
    SpringAgentCapabilityRegistry registry = new SpringAgentCapabilityRegistry(List.of(citationAgent));

    assertThat(registry.resolve(AgentType.CITATION)).isNotNull();
}
```

If the existing test helper is not reusable, create a local stub implementing `Agent`.

- [ ] **Step 5: 运行 Task 2 测试**

Run:

```bash
mvn -pl backend "-Dtest=CitationAgentTest,SpringAgentCapabilityRegistryTest" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/model/enums/AgentType.java backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationAgentTest.java backend/src/test/java/cn/bugstack/competitoragent/agent/capability/SpringAgentCapabilityRegistryTest.java
git commit -m "feat: add citation agent"
```

---

## Task 3: CitationSuggestionAssembler 与 Orchestrator 决策接入

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CitationSuggestionAssembler.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CitationSuggestionAssemblerTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`

- [ ] **Step 1: 写 CitationSuggestionAssembler 红灯测试**

Create `CitationSuggestionAssemblerTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSuggestionAssemblerTest {

    private final CitationSuggestionAssembler assembler = new CitationSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldCreateMissingCitationSuggestionFromCitationOutput() {
        String output = """
                {
                  "citationRiskSeverity": "ERROR",
                  "citationEvidenceState": "MISSING_SOURCE",
                  "citationIssues": [{
                    "issueId": "ci-1",
                    "issueType": "MISSING_CITATION",
                    "severity": "ERROR",
                    "targetSection": "action_suggestion",
                    "claimId": "claim-1",
                    "summary": "行动建议缺少引用",
                    "sourceUrls": [],
                    "evidenceState": "MISSING_SOURCE",
                    "suggestedQueries": ["action_suggestion official evidence"]
                  }]
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromCitationOutput(90L, "citation_check", output);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getProducerAgentType()).isEqualTo("CITATION");
        assertThat(suggestion.getSuggestionType()).isEqualTo("CITATION_VERIFICATION_GAP");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("rewrite_report");
    }
}
```

Run: `mvn -pl backend "-Dtest=CitationSuggestionAssemblerTest" test`

Expected: FAIL with `cannot find symbol: class CitationSuggestionAssembler`.

- [ ] **Step 2: 实现 CitationSuggestionAssembler**

Rules:

1. Ignore output when `citationRiskSeverity=NONE`.
2. For each `citationIssues[]`, create one `AgentSuggestion`.
3. Use:
   - `producerAgentType=CITATION`
   - `suggestionType=CITATION_VERIFICATION_GAP`
   - `targetSection=issue.targetSection`
   - `summary=issue.summary`
   - `severity=issue.severity` fallback to top-level `citationRiskSeverity`
   - `sourceUrls=issue.sourceUrls`
   - `evidenceState=issue.evidenceState`
   - `suggestedQueries=issue.suggestedQueries`
   - `suggestedTargetNode=rewrite_report`
4. Suggestion id format:
   - `as-task-{taskId}-{producerNodeName}-citation-{index}`
5. JSON parse failures return empty list and log warn.

- [ ] **Step 3: 扩展 OrchestrationDecisionService 红灯测试**

Append tests:

1. `shouldWaitForHumanWhenCitationSuggestionHasMissingSource`
   - trigger node `citation_check`
   - suggestion type `CITATION_VERIFICATION_GAP`
   - sourceUrls empty, evidenceState `MISSING_SOURCE`
   - expect `WAIT_FOR_HUMAN / MANUAL_REVIEW`

2. `shouldRewriteClaimWhenCitationSuggestionHasSourceBackedWeakSupport`
   - trigger node `citation_check`
   - sourceUrls non-empty, evidenceState `PARTIAL_SOURCE`
   - expect `REWRITE_ONLY / REWRITE_CLAIM`
   - target node `rewrite_report`

- [ ] **Step 4: 实现 OrchestrationDecisionService Citation 分支**

Implementation requirements:

1. Add `isCitationTrigger(String triggerNodeName)` for `citation_check` and `citation_check_revision`.
2. Route Citation trigger before `quality_check_final`.
3. Add `decideCitationSuggestions(OrchestrationContext context)`.
4. Missing source/unknown evidence:
   - if any `CITATION_VERIFICATION_GAP` suggestion has empty `sourceUrls` or `evidenceState=MISSING_SOURCE`, return `waitForHuman(context, "Citation Agent 发现引用缺口但缺少 sourceUrls，禁止进入 Reviewer。")`.
5. Source-backed issues:
   - return new decision:
     - `decisionType=REWRITE_ONLY`
     - `actionType=REWRITE_CLAIM`
     - `targetNode=rewrite_report`
     - `affectedScope=CURRENT_NODE_ONLY`
     - `targetSection=suggestion.targetSection`
     - `reason=suggestion.summary`
     - `sourceUrls=suggestion.sourceUrls`
     - `evidenceState=suggestion.evidenceState`
6. No suggestions:
   - return `NO_ACTION` with reason `citation_check 未产生 AgentSuggestion，无需编排动作。`

- [ ] **Step 5: 扩展 DagExecutor suggestion gate**

Modify constructor fields and overloads to include `CitationSuggestionAssembler`.

Modify `buildAgentSuggestions`:

```java
if ("citation_check".equals(completedNode.getNodeName())
        || "citation_check_revision".equals(completedNode.getNodeName())) {
    return citationSuggestionAssembler.fromCitationOutput(
            taskId,
            completedNode.getNodeName(),
            completedNode.getOutputData());
}
```

Update the Chinese comment above `buildAgentSuggestions` to include Citation.

- [ ] **Step 6: 增加 DagExecutor Citation gate 测试**

Append to `DagExecutorTest`:

1. Build nodes `write_report -> citation_check -> quality_check`.
2. Stub `write_report` success with report content.
3. Stub `citation_check` success with `citationRiskSeverity=ERROR` and missing source issue.
4. Expect:
   - `citation_check` becomes `WAITING_INTERVENTION`
   - `quality_check` remains `PENDING`
   - `OrchestrationTraceService.recordDecision(...)` called with `WAIT_FOR_HUMAN`

- [ ] **Step 7: 运行 Task 3 测试**

Run:

```bash
mvn -pl backend "-Dtest=CitationSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest" test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/CitationSuggestionAssembler.java backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/CitationSuggestionAssemblerTest.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
git commit -m "feat: route citation gaps through orchestrator"
```

---

## Task 4: 固定 DAG、协作角色和节点视图接入 Citation

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewServiceTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskPlanPreviewAssembler.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`

- [ ] **Step 1: 扩展协作计划测试**

Update `CollaborationPlanServiceTest.shouldCreateStandardRolePlanWithoutGeneratingFreeDagNodes`:

```java
assertThat(plan.getAgentRoleAssignments()).extracting(AgentRoleAssignment::getAgentType)
        .containsExactlyInAnyOrder("COLLECTOR", "EXTRACTOR", "ANALYZER", "WRITER", "CITATION", "REVIEWER");
```

Add assertions:

```java
AgentRoleAssignment citationRole = plan.getAgentRoleAssignments().stream()
        .filter(role -> "CITATION".equals(role.getAgentType()))
        .findFirst()
        .orElseThrow();
assertThat(citationRole.getDependsOn()).containsExactly("role-writer-01");
assertThat(citationRole.getQualityGate()).contains("citation");

AgentRoleAssignment reviewerRole = plan.getAgentRoleAssignments().stream()
        .filter(role -> "REVIEWER".equals(role.getAgentType()))
        .findFirst()
        .orElseThrow();
assertThat(reviewerRole.getDependsOn()).containsExactly("role-citation-01");
```

Run: `mvn -pl backend "-Dtest=CollaborationPlanServiceTest" test`

Expected: FAIL because Citation role is absent.

- [ ] **Step 2: 实现 CollaborationPlanService Citation 角色**

Insert role between Writer and Reviewer:

- role id: `role-citation-01`
- agent type: `CITATION`
- mission: `核查报告引用覆盖、证据编号有效性和来源可信度，阻止无引用强结论进入 Reviewer。`
- expected outputs: `CitationCheckResult`, `AgentSuggestion`
- dependsOn: `role-writer-01`
- qualityGate: `citation coverage must pass before reviewer`

Update Reviewer role dependsOn to `role-citation-01`.

- [ ] **Step 3: 更新 InitialPlanReviewService**

Modify:

```java
private static final List<String> ALLOWED_AGENT_TYPES = List.of(
        "COLLECTOR", "EXTRACTOR", "ANALYZER", "WRITER", "CITATION", "REVIEWER"
);
```

`REQUIRED_AGENT_TYPES` continues to equal `ALLOWED_AGENT_TYPES`.

Add or update test so a plan without `CITATION` is blocked with `缺少必需角色 CITATION`.

- [ ] **Step 4: 写 WorkflowFactory 红灯测试**

Append to `WorkflowFactoryTest`:

Add imports when missing:

```java
import java.util.stream.IntStream;
```

```java
@Test
void shouldInsertCitationChecksBeforeReviewerNodes() throws Exception {
    WorkflowFactory workflowFactory = buildWorkflowFactory(buildBrowserProperties());
    AnalysisTask task = AnalysisTask.builder()
            .id(91L)
            .taskName("Citation Agent DAG")
            .subjectProduct("企业级 RAG 知识库")
            .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
            .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
            .analysisDimensions(objectMapper.writeValueAsString(List.of("pricing", "security")))
            .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页")))
            .build();

    WorkflowPlan plan = workflowFactory.buildPlan(task);

    assertThat(plan.getNodes()).extracting(WorkflowPlan.WorkflowPlanNode::getNodeName)
            .containsSubsequence("write_report", "citation_check", "quality_check",
                    "rewrite_report", "citation_check_revision", "quality_check_final");
    assertThat(plan.getNodes()).extracting(WorkflowPlan.WorkflowPlanNode::getExecutionOrder)
            .doesNotHaveDuplicates()
            .containsExactlyElementsOf(IntStream.range(0, plan.getNodes().size()).boxed().toList());

    WorkflowPlan.WorkflowPlanNode citation = plan.getNodes().stream()
            .filter(node -> "citation_check".equals(node.getNodeName()))
            .findFirst()
            .orElseThrow();
    assertThat(citation.getAgentType()).isEqualTo("CITATION");
    assertThat(citation.getStageCode()).isEqualTo("DELIVER");
    assertThat(citation.getDependsOn()).containsExactly("write_report");

    WorkflowPlan.WorkflowPlanNode quality = plan.getNodes().stream()
            .filter(node -> "quality_check".equals(node.getNodeName()))
            .findFirst()
            .orElseThrow();
    assertThat(quality.getDependsOn()).containsExactly("citation_check");
    assertThat(citation.getExecutionOrder()).isLessThan(quality.getExecutionOrder());

    WorkflowPlan.WorkflowPlanNode revisionCitation = plan.getNodes().stream()
            .filter(node -> "citation_check_revision".equals(node.getNodeName()))
            .findFirst()
            .orElseThrow();
    assertThat(revisionCitation.getAgentType()).isEqualTo("CITATION");
    assertThat(revisionCitation.getStageCode()).isEqualTo("DELIVER");
    assertThat(revisionCitation.getDependsOn()).containsExactly("rewrite_report");
    JsonNode revisionConfig = objectMapper.readTree(revisionCitation.getNodeConfig());
    assertThat(revisionConfig.path("sourceNode").asText()).isEqualTo("rewrite_report");
    assertThat(revisionConfig.path("trigger").asText()).isEqualTo("rewrite_executed");

    WorkflowPlan.WorkflowPlanNode finalQuality = plan.getNodes().stream()
            .filter(node -> "quality_check_final".equals(node.getNodeName()))
            .findFirst()
            .orElseThrow();
    assertThat(finalQuality.getDependsOn()).containsExactly("citation_check_revision");
    assertThat(revisionCitation.getExecutionOrder()).isLessThan(finalQuality.getExecutionOrder());
}
```

Run: `mvn -pl backend "-Dtest=WorkflowFactoryTest#shouldInsertCitationChecksBeforeReviewerNodes" test`

Expected: FAIL because Citation nodes are absent.

Before editing the fixed DAG template, run the existing trigger baseline:

```bash
mvn -pl backend "-Dtest=DagExecutorTest#shouldClassifyFinalQualityGateFailureAsDownstreamConsumptionGap" test
```

Expected: PASS before and after Task 4. This proves existing `trigger=review_failed / rewrite_executed` semantics still work while inserting Citation nodes.

- [ ] **Step 5: 修改 ExecutionPlanDefinitionBuilder**

Keep using the existing `int order` plus `executionOrder(order++)` style. Do not assign hand-written constants. After inserting `citation_check` and `citation_check_revision`, all downstream fixed nodes must naturally receive later `executionOrder` values, with no duplicates and no dependency whose order is greater than or equal to its child.

Insert `citation_check` after `write_report`:

- `nodeName="citation_check"`
- `displayName="报告引用核查"`
- `agentType="CITATION"`
- `stageCode="DELIVER"`
- `goal="核查首版报告引用覆盖、证据编号有效性和来源可信度"`
- `summary="在质量初审前确认报告结论可回指来源"`
- `dependsOn=List.of("write_report")`
- `required=true`
- `allowFailedDependency=false`
- `retryable=true`
- `maxRetries=2`
- `executionOrder(order++)`
- `nodeConfig` fields:
  - `sourceNode=write_report`
  - `mode=initial`
  - `minCoverageRate=0.85`
  - `trustPolicy=official-first`

`minCoverageRate=0.85` is the first-pass threshold: it allows a small number of explicitly downgraded or non-sensitive sentences while still blocking under-cited conclusions before Reviewer. Keep it in `nodeConfig` so later stages can tune it without changing Java constants.

Change `quality_check.dependsOn` from `write_report` to `citation_check`; its `executionOrder(order++)` must now occur after `citation_check`.

Insert `citation_check_revision` after `rewrite_report`:

- `nodeName="citation_check_revision"`
- `displayName="修订报告引用复核"`
- `agentType="CITATION"`
- `stageCode="DELIVER"`
- `goal="复核修订报告引用覆盖、证据编号有效性和来源可信度"`
- `summary="在终审前确认修订后的报告结论可回指来源"`
- `dependsOn=List.of("rewrite_report")`
- `required=false`
- `allowFailedDependency=false`
- `retryable=true`
- `maxRetries=2`
- `executionOrder(order++)`
- `nodeConfig` fields:
  - `sourceNode=rewrite_report`
  - `mode=revision`
  - `trigger=rewrite_executed`
  - `minCoverageRate=0.90`
  - `trustPolicy=official-first`

`minCoverageRate=0.90` is stricter because it runs after rewrite and should confirm the repair actually improved citation coverage. Keep it in `nodeConfig` for the same reason as the initial threshold.

Change `quality_check_final.dependsOn` from `rewrite_report` to `citation_check_revision`; keep its existing `trigger=rewrite_executed`, and ensure its `executionOrder(order++)` remains after `citation_check_revision`.

Do not add a new stage. Both Citation nodes use the existing `DELIVER` stage. Update `buildStages()` only to adjust the `DELIVER` summary/detail so preview users see that delivery now includes report generation, Citation verification, reviewer quality checks, and rewrite/final-review closure.

- [ ] **Step 6: 节点视图支持 CITATION**

`TaskPlanPreviewAssembler.buildConfigSummaryData`:

When `agentType=CITATION`, return summary with:

- `summaryText`: `核查 {sourceNode} 引用覆盖，最低覆盖率 {minCoverageRate}`
- `sourceNode`
- `qualityPolicy` or `trustPolicy`

`TaskNodeViewAssembler.buildConfigSummaryData`:

When `agentType == AgentType.CITATION`, return summary with:

- `summaryText`: `引用核查：{sourceNode}，最低覆盖率 {minCoverageRate}`
- `sourceNode`
- `qualityPolicy`: `trustPolicy`

If `TaskNodeConfigSummary` lacks fields, reuse existing `sourceNode / qualityPolicy / summaryText` fields instead of adding new DTO fields.

- [ ] **Step 7: 运行 Task 4 测试**

Run:

```bash
mvn -pl backend "-Dtest=CollaborationPlanServiceTest,InitialPlanReviewServiceTest,WorkflowFactoryTest,TaskNodeViewAssemblerTest" test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanService.java backend/src/main/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewService.java backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskPlanPreviewAssembler.java backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java
git commit -m "feat: insert citation checks into workflow"
```

---

## Task 5: Replay、Smoke 和聚合验证

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`

- [ ] **Step 1: TaskReplayProjectionService 支持 CITATION**

Modify the method that decides whether a node is a business runtime node. It currently includes `COLLECTOR / EXTRACTOR / ANALYZER / WRITER`; add `AgentType.CITATION`.

Add test:

1. Create a `citation_check` task node with output containing `citationRiskSeverity=ERROR`.
2. Create matching `ORCHESTRATION_DECISION_RECORDED` event.
3. Assert replay timeline includes:
   - node name `citation_check`
   - agent type `CITATION`
   - decision reason
   - `evidenceState=MISSING_SOURCE`
   - `sourceUrls` preserved when present.

- [ ] **Step 2: CollaborationPlanningSmokeTest 覆盖 Citation DAG**

Add smoke:

1. Build a preview or workflow for a task.
2. Assert plan contains `citation_check / citation_check_revision`.
3. Assert `quality_check` is downstream of `citation_check`.
4. Assert collaboration role list includes `CITATION`.

Add smoke:

1. Execute a minimal DAG with stubbed `CitationAgent` returning missing citation output.
2. Assert `citation_check` enters `WAITING_INTERVENTION`.
3. Assert `quality_check` does not run.
4. Assert trace recorded `WAIT_FOR_HUMAN`.

- [ ] **Step 3: 运行 P3-4 局部聚合验证**

Run:

```bash
mvn -pl backend "-Dtest=CitationClaimExtractorTest,CitationSourceTrustPolicyTest,CitationAgentTest,CitationSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest,WorkflowFactoryTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,TaskReplayProjectionServiceTest,CollaborationPlanningSmokeTest" test
```

Expected: PASS.

- [ ] **Step 4: 运行 P1+P2+P3-1+P3-2+P3-3+P3-4 编排聚合验证**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,WriterSuggestionAssemblerTest,CitationSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest,ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationServiceTest,ConversationControllerContractTest" test
```

Expected: PASS.

- [ ] **Step 5: 运行 backend 全量回归**

Run:

```bash
mvn -pl backend test
```

Expected: PASS.

- [ ] **Step 6: 运行 diff 检查**

Run:

```bash
git diff --check -- backend/src/main/java/cn/bugstack/competitoragent backend/src/test/java/cn/bugstack/competitoragent docs/superpowers/agent-collaboration-orchestration/task/2026-06-25-agent-collaboration-orchestration-p3-4-citation-agent-implementation-plan.md
```

Expected: exit code 0; LF/CRLF warnings are acceptable, whitespace errors are not.

- [ ] **Step 7: 记录 Task 5 progress**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-25-p3-4-task5-progress.md`:

```markdown
# P3-4 Task 5 Progress - 2026-06-25

当前阶段：P3-4 已完成局部验证、编排聚合验证和 backend 全量回归。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

## 验证结果

| 命令 | 结果 |
| --- | --- |
| `mvn -pl backend "-Dtest=CitationClaimExtractorTest,CitationSourceTrustPolicyTest,CitationAgentTest,CitationSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest,WorkflowFactoryTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,TaskReplayProjectionServiceTest,CollaborationPlanningSmokeTest" test` | PASS |
| `mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,WriterSuggestionAssemblerTest,CitationSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest,ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationServiceTest,ConversationControllerContractTest" test` | PASS |
| `mvn -pl backend test` | PASS |
| `git diff --check -- backend/src/main/java/cn/bugstack/competitoragent backend/src/test/java/cn/bugstack/competitoragent docs/superpowers/agent-collaboration-orchestration/task/2026-06-25-agent-collaboration-orchestration-p3-4-citation-agent-implementation-plan.md` | PASS |

## 下一步

执行 Task 6：文档回链与进度持久化。
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java docs/superpowers/agent-collaboration-orchestration/progress/2026-06-25-p3-4-task5-progress.md
git commit -m "test: verify p3-4 citation orchestration"
```

---

## Task 6: 文档回链与进度持久化

**Files:**
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- Modify: `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-25-agent-collaboration-orchestration-p3-4-citation-agent-implementation-plan.md`
- Create: `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-25-p3-4-task6-progress.md`

- [ ] **Step 1: 更新总蓝图 3.4 状态**

Append under `3.4 Agent 协作编排层`:

```markdown
- P3-4 实施：`✅` Citation Agent 已作为独立 `CITATION` 角色和固定 DAG 节点接入 `write_report -> citation_check -> quality_check` 与 `rewrite_report -> citation_check_revision -> quality_check_final`；引用覆盖、证据编号有效性和来源可信度风险会输出为 `CitationCheckResult`，并通过 `CitationSuggestionAssembler -> OrchestrationDecision` 进入可回放决策轨迹。无引用、未知证据编号或缺来源会在 Reviewer 前进入 `WAIT_FOR_HUMAN`，有来源但弱支撑或低可信来源会记录 `REWRITE_ONLY / REWRITE_CLAIM`，继续保持 `sourceUrls / evidenceState` 红线。
```

- [ ] **Step 2: 更新 3.4 架构规格实现记录**

Append to implementation records:

```markdown
2026-06-25 P3-4 自动化实现记录：Citation Agent 已独立接入协作计划、固定 DAG、节点运行和 Orchestrator suggestion gate。`citation_check / citation_check_revision` 会在 Reviewer 前核查报告引用覆盖、证据编号有效性和来源可信度；Citation 输出的 `citationEvidenceState / citationRiskSeverity / citationIssues / sourceCredibilityFindings / sourceUrls` 会被 `CitationSuggestionAssembler` 转成 `CITATION_VERIFICATION_GAP`，再由 `OrchestrationDecisionService` 裁决为 `WAIT_FOR_HUMAN` 或 `REWRITE_ONLY / REWRITE_CLAIM`。本轮仍不调用外部抓取或 LLM 做二次事实核验。
```

- [ ] **Step 3: 更新稳定演示计划**

Update current stage:

```markdown
当前阶段：3.4 P3-4 已把独立 Citation Agent 接入 Writer 与 Reviewer 之间；稳定演示版可解释报告引用覆盖、证据编号有效性、来源可信度、编排阻断原因和后续重写建议。
```

Add checklist item:

```markdown
- [x] Citation Agent 能在 Reviewer 前发现无引用、未知证据编号或低可信来源，并把缺口写入可回放 `OrchestrationDecision`。
```

- [ ] **Step 4: 更新本计划执行进度**

Append to this plan:

```markdown
## 2026-06-25 执行进度

当前阶段：P3-4 Citation Agent 已完成自动化实现、编排接入、回放验证和文档回链。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

- [x] Task 1：Citation 契约、声明解析器和来源可信度策略
- [x] Task 2：CitationAgent
- [x] Task 3：CitationSuggestionAssembler 与 Orchestrator 决策接入
- [x] Task 4：固定 DAG、协作角色和节点视图接入
- [x] Task 5：Replay、Smoke 和聚合验证
- [x] Task 6：文档回链与进度持久化
```

- [ ] **Step 5: 创建 Task 6 progress**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-25-p3-4-task6-progress.md`:

```markdown
# P3-4 Task 6 Progress - 2026-06-25

当前阶段：P3-4 已完成文档回链与进度持久化。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

## 已完成内容

1. 总蓝图已记录 P3-4 Citation Agent 独立接入。
2. 3.4 架构规格已追加 P3-4 自动化实现记录。
3. 稳定演示计划已更新当前阶段和检查清单。
4. P3-4 计划文档已写入最终执行进度和验证结果。

## 剩余未做

1. P3-4 范围内无剩余开发任务。
2. 后续增强：外部二次抓取、LLM 语义蕴含判断和更细粒度报告页 Citation 可视化不在本轮范围内。
```

- [ ] **Step 6: 运行文档 diff 检查**

Run:

```bash
git diff --check -- docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md docs/superpowers/agent-collaboration-orchestration/task/2026-06-25-agent-collaboration-orchestration-p3-4-citation-agent-implementation-plan.md docs/superpowers/agent-collaboration-orchestration/progress/2026-06-25-p3-4-task6-progress.md
```

Expected: exit code 0; no whitespace errors.

- [ ] **Step 7: Commit**

```bash
git add docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md docs/superpowers/agent-collaboration-orchestration/task/2026-06-25-agent-collaboration-orchestration-p3-4-citation-agent-implementation-plan.md docs/superpowers/agent-collaboration-orchestration/progress/2026-06-25-p3-4-task6-progress.md
git commit -m "docs: record p3-4 citation agent"
```

---

## P3-4 Live Smoke Suggestion

自动化通过后再执行真实 smoke。本轮 smoke 不要求外部二次抓取，只验证 Citation Agent 能在真实任务报告后给出可解释的引用核查结果。

| 样本 | 目的 | 预期 |
| --- | --- | --- |
| Writer 生成无引用行动建议样本 | 验证无引用强结论被 Citation 阻断 | `citation_check` 进入 `WAITING_INTERVENTION`，`quality_check` 不执行，replay 有 `WAIT_FOR_HUMAN / MISSING_SOURCE` |
| Writer 引用未知证据编号样本 | 验证 `[证据：E999]` 不会被当成有效引用 | `citationIssues[].issueType=UNKNOWN_EVIDENCE_ID`，Orchestrator 决策进入人工介入 |
| 官方来源完整引用样本 | 验证可信来源和引用覆盖可放行 | `citationRiskSeverity=NONE`，`citationEvidenceState=FULL_SOURCE`，`quality_check` 可继续执行 |
| 修订后报告样本 | 验证 `citation_check_revision` 在终审前执行 | `rewrite_report -> citation_check_revision -> quality_check_final` 顺序成立 |

推荐请求输入：

```json
{
  "taskName": "P3-4 Citation Agent smoke",
  "subjectProduct": "企业级 RAG 知识库平台",
  "competitorNames": ["Notion AI"],
  "competitorUrls": [
    "https://www.notion.so/product/ai",
    "https://www.notion.so/pricing"
  ],
  "analysisDimensions": ["产品功能", "价格策略", "安全与权限", "适用客户"],
  "sourceScope": ["官网", "产品文档", "定价页"],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}
```

验收检查：

1. `GET /api/task/{taskId}/nodes` 中存在 `citation_check`，其 `agentType=CITATION`。
2. `citation_check.outputData` 包含 `citationEvidenceState / citationRiskSeverity / citationIssues / sourceCredibilityFindings / sourceUrls`。
3. 若 `citationRiskSeverity=ERROR`，`quality_check` 保持 `PENDING` 或被依赖阻断，不应审查无引用报告。
4. `GET /api/task/{taskId}/replay` 中能看到 `citation_check` 触发的 `ORCHESTRATION_DECISION_RECORDED`。
5. 对话里问“系统建议我下一步做什么？”时，P3-3 预览能展示 Citation 决策原因、`evidenceState` 和 `sourceUrls`。

---

## Plan Self-Review

### Spec Coverage

1. 架构规格 P3 第 4 项 “Citation Agent 独立核查引用覆盖和来源可信度”：Task 1-4 覆盖。
2. `sourceUrls / evidenceState` 红线：Task 1 契约、Task 2 Agent 输出、Task 3 suggestion/decision、Task 5 replay 全部覆盖。
3. 不把 Citation Agent 变成自由调度器：Task 4 固定 DAG 接入，Scope Guard 明确不改自由规划。
4. P3-2 Writer 缺口继承：Task 2 从 Writer 输出读取报告正文、`sourceUrls` 和 Writer gap 元数据作为上下文，但保留 `WriterCitationGapInspector` 的章节级粗检职责，不让它承担 Citation 逐句核查。
5. P3-3 Conversation 复用：Live Smoke 要求最近 Citation 决策可由对话动作预览展示。
6. 固定 DAG 顺序与阶段：Task 4 覆盖 `executionOrder` 连续唯一、Citation 节点归属现有 `DELIVER` stage、`buildStages()` 交付阶段文案更新和现有 trigger 语义预验证。
7. 文档回链：Task 6 覆盖总蓝图、架构规格、稳定演示计划和本计划进度。

### Placeholder Scan

本文没有 `TBD`、`TODO`、未命名文件、未指定测试命令或未定义动作类型。外部二次抓取、LLM 语义核验和报告页可视化均明确标为后续增强，不是本轮占位实现。

### Type Consistency

1. 新 Agent 类型统一为 `CITATION`。
2. 固定节点统一为 `citation_check / citation_check_revision`。
3. Citation 输出总契约统一为 `CitationCheckResult`。
4. Citation suggestion 类型统一为 `CITATION_VERIFICATION_GAP`。
5. 缺来源引用问题统一进入 `WAIT_FOR_HUMAN / MANUAL_REVIEW`。
6. 有来源但弱支撑或低可信问题统一记录 `REWRITE_ONLY / REWRITE_CLAIM`。
7. 证据状态继续使用既有 `EvidenceState.FULL_SOURCE / PARTIAL_SOURCE / MISSING_SOURCE / NOT_APPLICABLE`。
8. Citation 固定节点统一使用既有 `DELIVER` stage，不新增阶段码。
9. 固定节点顺序继续使用 `executionOrder(order++)`，不手写常量。

---

## 2026-06-25 Plan Writing Progress

当前阶段：P3-4 具体执行计划已完成并通过计划自检，待执行实现。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

- [x] 读取 P3-3 最新进度、P3-2 最新总结、3.4 架构规格、总蓝图和稳定演示计划
- [x] 确认 P3-4 范围：独立 Citation Agent 引用核查与来源可信度验证，不做外部二次抓取或 LLM 语义核验
- [x] 梳理现有 `WriterCitationGapInspector / WriterSuggestionAssembler / OrchestrationDecisionService / DagExecutor / ExecutionPlanDefinitionBuilder` 边界
- [x] 写入 P3-4 具体执行计划
- [x] 根据执行风险复核并补充：`executionOrder`、`WriterCitationGapInspector` 边界、节点名分支遗漏风险、trigger 基线验证、`buildStages()` 的 `DELIVER` stage 归属、`normalized()` 规范和中文注释要求
- [x] 完成计划自检：占位符、范围、类型命名、测试命令和文档回链均已检查；执行者在开始 Task 1 前仍需再次确认工作区未覆盖他人未提交改动

## 2026-06-25 执行进度

当前阶段：P3-4 Citation Agent 已完成自动化实现、编排接入、回放验证和文档回链。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

- [x] Task 1：Citation 契约、声明解析器和来源可信度策略
- [x] Task 2：CitationAgent
- [x] Task 3：CitationSuggestionAssembler 与 Orchestrator 决策接入
- [x] Task 4：固定 DAG、协作角色和节点视图接入
- [x] Task 5：replay、smoke 和聚合验证
- [x] Task 6：文档回链与进度持久化
