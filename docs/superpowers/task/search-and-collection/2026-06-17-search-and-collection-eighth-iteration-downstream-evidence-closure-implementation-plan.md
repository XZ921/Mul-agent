# Search And Collection Eighth Iteration Downstream Evidence Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Execution Order Note (2026-06-17):** 本文档仍是未执行的正式第八轮计划，不是历史快照，也不作废。但在 `架构 1` 冻结后，它不再是当前最先启动的任务。执行本轮前，应先完成 [2026-06-17-search-and-collection-family-discovery-convergence-implementation-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/task/2026-06-17-search-and-collection-family-discovery-convergence-implementation-plan.md)，先补齐 `official / github` discovery 侧的家族收敛缺口，再启动本轮 `Wave 12` 下游证据闭环。

**Goal:** 承接父方案 `Wave 12`，把搜索与采集阶段已经正式化的 `collectionAudit / qualitySignals / structuredBlocks / sourceUrls` 收口为下游统一证据视图，打通 `extract_schema -> analyze_competitors -> write_report -> quality_check -> report / export` 的正式消费边界，并切断“任务现场抽取结果默认伪装成 DOMAIN 记忆”的持续污染。

**Architecture:** 本轮不再扩新的搜索 provider、RSS / feed 订阅能力或跨重启 replay 底座，而是聚焦“采集结果如何被下游真实消费”。总体顺序固定为 `红灯契约 -> 提取边界收口 -> 下游统一证据视图 -> 记忆边界止血 -> 报告/质检/交付投影回归 -> dev live 验收`。正式边界采用“双层分工”：`ExtractResult / CompetitorKnowledgeDraft / EvidenceFragment / SectionEvidenceBundle` 组成下游运行期正式证据视图；`CompetitorKnowledge` 退回任务快照与受治理的记忆写回载体，显式区分 `TASK` 与 `DOMAIN` 语义，不再让 extractor 默认落成长期领域知识。

**Tech Stack:** Java 17, Spring Boot, Jackson, Flyway, JPA, JUnit 5, Mockito

---

## Scope Guard

### 本轮必须完成

1. `SchemaExtractorAgent` 不得再把 `EvidenceSource.fullContent` 直接当作唯一正式输入；必须先把 `pageMetadata` 里的 `qualitySignals / structuredBlocks / structuredPayload / sourceUrls` 提炼成稳定下游证据视图，再传给抽取阶段。
2. `ExtractResult`、`CompetitorKnowledgeDraft`、`AnalysisResult`、`ReportWriterAgent` 输出、`QualityReviewAgent` 输出、`ReportResponse` / `EvidenceQueryService` 投影必须共享同一组正式证据契约字段，至少覆盖：
   - `sourceUrls`
   - `issueFlags`
   - `evidenceFragments`
   - `sectionEvidenceBundles`
   - `qualitySignals`
   - `structuredBlocks`
   - `evidenceCoverage`
3. `SchemaExtractorAgent` 与 `CompetitorAnalysisAgent` 之间必须收口正式消费边界，不能继续让“节点输出一套、Repository 再读一套”的分裂状态无约束扩散。
4. `CompetitorKnowledge` 必须显式区分“任务现场抽取快照”和“终审通过后允许写回的 DOMAIN 记忆”；extract 阶段默认不得再落 `memoryLayer=DOMAIN`。
5. 为 `CompetitorKnowledge` 新增或补齐正式边界字段时，必须同步提供 Flyway migration，不能只改实体。
6. Writer、Reviewer、ReportService、EvidenceQueryService 必须能直接解释“某个结论为什么证据不足、缺的到底是 sourceUrls、structured block，还是字段 coverage”，而不是只看到大字符串或模糊 issue。
7. 所有新增对象和投影继续强制满足 `sourceUrls` 可追溯红线；缺少来源时返回空列表并显式打缺口标记。
8. 测试必须覆盖真实主停点：`unsupported_claim`、`missing_evidence`、`STRUCTURE_COMPLETENESS`、`EVIDENCE_TRACEABILITY`、`ACTIONABILITY` 不再只依赖正文句子审查，还要感知采集质量与字段 coverage。

### 本轮明确不做

1. 不继续扩 `Wave 10` 的 News discovery、额外 GitHub vertical discovery provider、Atom 支持或 `Wave 11` subscription / cursor / 去重窗口。
2. 不重写 `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`NodeExecutionRecoveryPolicy` 的总体恢复策略。
3. 不把跨重启 replay 持久化底座并入本轮；这仍是父方案里独立的后续平台任务。
4. 不新建前端页面或重做前端交互，只要求后端 DTO、查询投影和导出契约正式可用。
5. 不把 `CompetitorAnalysisAgent`、`ReportWriterAgent`、`QualityReviewAgent` 拆成大量新类；本轮先收口正式边界，再决定后续是否拆类。
6. 不把 DOMAIN 记忆融合策略整体重构成独立专题，只先阻断 extractor 默认污染长期层的路径。

---

## Current Stage

当前阶段：第七轮 RSS 专项自动化收口已完成，父方案 `Wave 9` 尾证表明主停点已从“采集和 extractor 兼容性故障”转移到“下游证据消费和真实质量门禁”。`ExtractionStructured.md` 已完成诊断，但 `提取结构化` 链路仍无正式方案文档；第八轮需要把搜索与采集专题的 `Wave 12` 落成一份可执行实施计划，并与提取结构化专题共享同一条正式证据边界。

同时，`架构 1` 冻结后新增了一个更前置的执行事实：`official / github` discovery 侧仍有 family-first convergence 缺口。因此本计划当前状态不是“被取消”，而是“等待前置收敛计划完成后执行”。

- [x] 父方案 `Wave 12` 范围确认：已完成
- [x] 第六轮 / 第七轮尾证复核：已完成
- [x] `ExtractionStructured.md` blocking 归并：已完成
- [x] 下游链路真实代码边界核对：已完成
- [x] 第八轮实施计划落稿：已完成
- [x] family discovery convergence 前置条件：已完成
- [ ] 第八轮实现与验证：待执行（前置条件已满足，可按顺序启动）

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase L1 | 锁定下游证据闭环红灯契约 | 0.5 天 | 第七轮自动化收口已完成，`ExtractionStructured.md` 已存在 | 待执行 |
| Phase L2 | 收口 extractor 正式输入与任务快照边界 | 1-1.5 天 | Phase L1 红灯测试存在 | 待执行 |
| Phase L3 | 打通 `extract -> analyze -> write -> review` 统一证据视图 | 1-2 天 | Phase L2 完成 | 待执行 |
| Phase L4 | 阻断 extractor 默认 `DOMAIN` 记忆污染并补 Flyway | 0.5-1 天 | Phase L2 完成 | 待执行 |
| Phase L5 | 回归报告、导出、证据查询与质检诊断投影 | 1 天 | Phase L3-L4 完成 | 待执行 |
| Phase L6 | 聚合验证、文档回链与 dev live 验收 | 0.5-1 天 | Phase L1-L5 完成 | 待执行 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceView.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceBlock.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceQuality.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/DownstreamEvidenceClosureContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/DownstreamEvidenceProjectionContractTest.java`
- `backend/src/main/resources/db/migration/V27__add_task_memory_boundary_columns_to_competitor_knowledge.sql`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/entity/EvidenceSource.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/ExtractResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CompetitorKnowledgeDraft.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/entity/CompetitorKnowledge.java`
- `backend/src/main/java/cn/bugstack/competitoragent/repository/CompetitorKnowledgeRepository.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/QualityDiagnosis.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/RevisionDirective.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/RevisionPlan.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/memory/MemoryWritebackService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/memory/MemoryReusePolicy.java`

### Backend - Test

- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssemblerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/ExportPackageServiceTest.java`

### Docs - Modify

- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- `docs/problem/ExtractionStructured.md`

---

### Task 1: 锁定第八轮红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/DownstreamEvidenceClosureContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/report/DownstreamEvidenceProjectionContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`

- [ ] **Step 1: 新建下游统一证据视图红灯测试，锁定 extractor 不再只吃大字符串正文**

```java
package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamEvidenceClosureContractTest {

    @Test
    void shouldExposeStructuredEvidenceViewWithQualitySignalsBlocksAndSourceUrls() {
        DownstreamEvidenceView evidenceView = DownstreamEvidenceView.builder()
                .evidenceId("E001")
                .sourceType("DOCS")
                .title("Pricing Docs")
                .content("公开定价页正文")
                .sourceUrls(List.of("https://docs.example.com/pricing"))
                .qualitySignals(List.of("STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"))
                .structuredBlocks(List.of(
                        DownstreamEvidenceBlock.builder()
                                .blockType("PRICING_BLOCK")
                                .summary("Pro 199 / 月")
                                .sourceUrls(List.of("https://docs.example.com/pricing"))
                                .build()
                ))
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(0.82D)
                        .failureKind(null)
                        .build())
                .build()
                .normalized();

        assertThat(evidenceView.getSourceUrls()).containsExactly("https://docs.example.com/pricing");
        assertThat(evidenceView.getQualitySignals()).contains("STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT");
        assertThat(evidenceView.getStructuredBlocks()).extracting(DownstreamEvidenceBlock::getBlockType)
                .containsExactly("PRICING_BLOCK");
        assertThat(evidenceView.getQuality().getQualityScore()).isEqualTo(0.82D);
    }
}
```

- [ ] **Step 2: 新建报告投影红灯测试，锁定 `qualitySignals / structuredBlocks / sourceUrls` 必须进入主路径 DTO**

```java
package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamEvidenceProjectionContractTest {

    @Test
    void shouldExposeQualitySignalsStructuredBlocksAndTraceableSourcesInReportProjection() {
        ReportResponse.EvidenceInfo evidenceInfo = new ReportResponse.EvidenceInfo(
                "E001",
                "Pricing Docs",
                "https://docs.example.com/pricing",
                "公开定价页正文",
                "Acme",
                null,
                "DOCS",
                "SEARCH",
                "docs.example.com",
                "命中文档",
                null,
                0.91D,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("PRICING_BLOCK_HIT"),
                Map.of(
                        "qualitySignals", List.of("PRICING_BLOCK_HIT"),
                        "structuredBlocks", List.of(Map.of("blockType", "PRICING_BLOCK")),
                        "sourceUrls", List.of("https://docs.example.com/pricing")
                )
        );

        assertThat(evidenceInfo.getPageMetadata()).containsKeys("qualitySignals", "structuredBlocks", "sourceUrls");
    }
}
```

- [ ] **Step 3: 扩展 extractor / analyzer / writer / reviewer 红灯测试，锁定第八轮四个核心缺口**

```java
@Test
void shouldBuildExtractorInputFromStructuredEvidenceViewInsteadOfRawFullContentOnly() {
    // 锁定 extractor 在 EvidenceSource.pageMetadata 已存在 structuredBlocks / qualitySignals 时，
    // prompt 与 output 都能看到正式视图字段，而不是只截断 fullContent。
}
```

```java
@Test
void shouldPreferTaskScopedKnowledgeBoundaryOverDefaultDomainMemoryLayer() {
    // 锁定 SchemaExtractorAgent 落库的 CompetitorKnowledge 默认不是 DOMAIN。
}
```

```java
@Test
void shouldCarryCollectionQualitySignalsIntoReviewerDiagnoses() {
    // 锁定 reviewer 生成的 diagnosis 不再只看正文句子，还能看到 structured block / quality gap。
}
```

```java
@Test
void shouldProjectStructuredEvidenceBlocksIntoReportDiagnosisAndEntryPoint() {
    // 锁定 report / evidence query 主路径能直接回答“缺的是哪类结构块证据”。
}
```

- [ ] **Step 4: 运行第八轮红灯测试集合**

Run:
`mvn -pl backend "-Dtest=DownstreamEvidenceClosureContractTest,DownstreamEvidenceProjectionContractTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest" test`

Expected:
- FAIL
- `DownstreamEvidenceView` 相关对象不存在
- extractor 仍主要按 `fullContent.substring(0, 8000)` 构造输入
- `CompetitorKnowledge` 默认仍会被补成 `memoryLayer=DOMAIN`
- report / reviewer 还不能稳定消费 `qualitySignals / structuredBlocks`

---

### Task 2: 收口 extractor 正式输入与任务快照边界

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceView.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceBlock.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceQuality.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/entity/EvidenceSource.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/ExtractResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CompetitorKnowledgeDraft.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

- [ ] **Step 1: 新建下游统一证据视图对象**

```java
package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 下游统一证据视图。
 * 该对象是 extract/analyze/write/review/report 共享的正式证据输入，
 * 不再让每个节点各自重新解析 pageMetadata 或重新猜 structured block 语义。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DownstreamEvidenceView {

    private String evidenceId;
    private String competitorName;
    private String sourceType;
    private String title;
    private String content;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    @Builder.Default
    private List<String> issueFlags = List.of();
    @Builder.Default
    private List<String> qualitySignals = List.of();
    @Builder.Default
    private List<DownstreamEvidenceBlock> structuredBlocks = List.of();
    private DownstreamEvidenceQuality quality;

    public DownstreamEvidenceView normalized() {
        LinkedHashSet<String> normalizedSourceUrls = new LinkedHashSet<>();
        if (sourceUrls != null) {
            for (String sourceUrl : sourceUrls) {
                if (sourceUrl != null && !sourceUrl.isBlank()) {
                    normalizedSourceUrls.add(sourceUrl.trim());
                }
            }
        }
        LinkedHashSet<String> normalizedIssueFlags = new LinkedHashSet<>();
        if (issueFlags != null) {
            for (String issueFlag : issueFlags) {
                if (issueFlag != null && !issueFlag.isBlank()) {
                    normalizedIssueFlags.add(issueFlag.trim());
                }
            }
        }
        LinkedHashSet<String> normalizedQualitySignals = new LinkedHashSet<>();
        if (qualitySignals != null) {
            for (String qualitySignal : qualitySignals) {
                if (qualitySignal != null && !qualitySignal.isBlank()) {
                    normalizedQualitySignals.add(qualitySignal.trim());
                }
            }
        }
        return this.toBuilder()
                .sourceUrls(new ArrayList<>(normalizedSourceUrls))
                .issueFlags(new ArrayList<>(normalizedIssueFlags))
                .qualitySignals(new ArrayList<>(normalizedQualitySignals))
                .structuredBlocks(structuredBlocks == null ? List.of() : structuredBlocks.stream()
                        .filter(block -> block != null)
                        .map(DownstreamEvidenceBlock::normalized)
                        .toList())
                .quality(quality == null ? DownstreamEvidenceQuality.builder().build().normalized() : quality.normalized())
                .build();
    }
}
```

- [ ] **Step 2: 在 collector 兼容映射中正式输出 `downstreamEvidenceViews`**

```java
output.put("downstreamEvidenceViews", buildDownstreamEvidenceViews(
        evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId())
));
```

```java
private List<DownstreamEvidenceView> buildDownstreamEvidenceViews(List<EvidenceSource> evidences) {
    List<DownstreamEvidenceView> views = new ArrayList<>();
    for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
        JsonNode metadata = readJson(evidence.getPageMetadata());
        views.add(DownstreamEvidenceView.builder()
                .evidenceId(evidence.getEvidenceId())
                .competitorName(evidence.getCompetitorName())
                .sourceType(evidence.getSourceType())
                .title(evidence.getTitle())
                .content(firstNonBlank(evidence.getFullContent(), evidence.getContentSnippet()))
                .sourceUrls(readStringList(metadata == null ? null : metadata.path("sourceUrls")))
                .issueFlags(readStringList(metadata == null ? null : metadata.path("issueFlags")))
                .qualitySignals(readStringList(metadata == null ? null : metadata.path("qualitySignals")))
                .structuredBlocks(readStructuredBlocks(metadata == null ? null : metadata.path("structuredBlocks")))
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(readDouble(metadata, "qualityScore"))
                        .failureKind(readText(metadata, "failureKind"))
                        .build())
                .build()
                .normalized());
    }
    return views;
}
```

- [ ] **Step 3: 让 extractor 从 `DownstreamEvidenceView` 构造 prompt，而不是只拼接截断正文**

```java
List<DownstreamEvidenceView> evidenceViews = buildDownstreamEvidenceViews(evidences);
String prompt = promptService.render("extractor", Map.of(
        "competitorName", competitorName,
        "evidenceCatalog", buildEvidenceCatalog(evidenceViews),
        "collectedContent", buildCollectedContent(evidenceViews),
        "taskRagContext", taskRagContext
));
```

```java
private String buildCollectedContent(List<DownstreamEvidenceView> evidences) {
    StringBuilder collectedContent = new StringBuilder();
    for (DownstreamEvidenceView evidence : evidences) {
        collectedContent.append("--- Source: ")
                .append(safe(evidence.getEvidenceId()))
                .append(" ")
                .append(safe(evidence.getTitle()))
                .append(" ---\n");
        collectedContent.append("sourceUrls: ").append(evidence.getSourceUrls()).append('\n');
        collectedContent.append("qualitySignals: ").append(evidence.getQualitySignals()).append('\n');
        if (evidence.getStructuredBlocks() != null && !evidence.getStructuredBlocks().isEmpty()) {
            collectedContent.append("structuredBlocks: ").append(evidence.getStructuredBlocks()).append('\n');
        }
        String content = evidence.getContent();
        if (content != null && content.length() > 4000) {
            content = content.substring(0, 4000) + "...(truncated)";
        }
        collectedContent.append(content == null ? "" : content).append("\n\n");
    }
    return collectedContent.toString();
}
```

- [ ] **Step 4: 运行 extractor / collector 边界测试**

Run:
`mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,SchemaExtractorAgentTest" test`

Expected:
- PASS

---

### Task 3: 打通 `extract -> analyze -> write -> review` 统一证据视图

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/ExtractResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CompetitorKnowledgeDraft.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/QualityDiagnosis.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/RevisionDirective.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/RevisionPlan.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`

- [ ] **Step 1: 在 `ExtractResult / CompetitorKnowledgeDraft / AnalysisResult` 中正式挂出统一证据视图**

```java
private List<DownstreamEvidenceView> downstreamEvidenceViews;
```

Implementation note:
本字段需要同时进入：
- `ExtractResult`
- `CompetitorKnowledgeDraft`
- `AnalysisResult`

但不要求替代既有 `evidenceFragments / sectionEvidenceBundles`；第八轮目标是“共享同一证据视图”，不是立刻删除旧契约。

- [ ] **Step 2: 让 analyzer 正式优先消费 extractor 视图，而不是只回库拼 `CompetitorKnowledge`**

```java
String extractorOutput = context.getSharedOutput("extract_schema");
List<DownstreamEvidenceView> downstreamEvidenceViews = readDownstreamEvidenceViews(extractorOutput);
if (downstreamEvidenceViews.isEmpty()) {
    downstreamEvidenceViews = buildDownstreamEvidenceViewsFromKnowledge(knowledges);
}
```

Implementation note:
`CompetitorAnalysisAgent` 仍可继续使用 `knowledgeRepository.findByTaskIdOrderByIdAsc(...)` 作为任务快照来源，但正式分析输入必须先读取 extractor 的运行期输出视图；只有当历史数据或回放场景没有这条视图时，才回退到 repository 补构。

- [ ] **Step 3: 让 reviewer 诊断显式携带采集质量语义**

```java
diagnoses.add(QualityDiagnosis.builder()
        .dimensionCode("EVIDENCE_TRACEABILITY")
        .dimensionName("证据可追溯性")
        .type("missing_structured_evidence")
        .section(sectionTitle)
        .severity("ERROR")
        .level("BLOCKER")
        .title("关键结论缺少结构化证据支撑")
        .detail("当前章节缺少 pricing / docs / release note 等高价值结构块证据。")
        .evidenceBasis("structuredBlocks 与 qualitySignals 未达到可用门槛。")
        .sourceUrls(sourceUrls)
        .repairSuggestion("请优先补齐结构块证据，必要时回到采集节点补抓对应内容页。")
        .build()
        .normalized());
```

Implementation note:
`QualityReviewAgent` 需要新增一条判断：当章节缺口并非纯正文引用缺失，而是 `DownstreamEvidenceView.structuredBlocks` 或 `qualitySignals` 已表明“结构化证据不足”时，diagnosis 必须能区分为采集质量问题，而不是继续都压成通用 `unsupported_claim`。

- [ ] **Step 4: 运行下游统一证据视图回归**

Run:
`mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,ReportWriterAgentTest,QualityReviewAgentTest" test`

Expected:
- PASS

---

### Task 4: 阻断 extractor 默认 `DOMAIN` 记忆污染并补 Flyway

**Files:**

- Create: `backend/src/main/resources/db/migration/V27__add_task_memory_boundary_columns_to_competitor_knowledge.sql`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/entity/CompetitorKnowledge.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/repository/CompetitorKnowledgeRepository.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/memory/MemoryWritebackService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/memory/MemoryReusePolicy.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`

- [ ] **Step 1: 补 Flyway migration，给任务快照边界正式落库**

```sql
ALTER TABLE competitor_knowledge
    ADD COLUMN IF NOT EXISTS snapshot_scope VARCHAR(40) NOT NULL DEFAULT 'TASK',
    ADD COLUMN IF NOT EXISTS producer_node_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS plan_version_id BIGINT,
    ADD COLUMN IF NOT EXISTS branch_key VARCHAR(120);

CREATE INDEX IF NOT EXISTS idx_knowledge_task_scope
    ON competitor_knowledge(task_id, snapshot_scope);
```

Implementation note:
本轮命名可以与实现微调，但目标必须保持一致：`CompetitorKnowledge` 要能显式表达“这是任务现场快照”，而不是只靠 `memoryLayer` 间接猜。

- [ ] **Step 2: 让 extractor 默认落 `TASK` 边界，而不是默认 `DOMAIN`**

```java
return CompetitorKnowledge.builder()
        .taskId(context.getTaskId())
        .competitorName(competitorName)
        .memoryLayer("TASK")
        .snapshotScope("TASK")
        .producerNodeName(context.getCurrentNodeName())
        .planVersionId(context.getPlanVersionId())
        .branchKey(context.getBranchKey())
        .versionSource("TASK_EXTRACT@" + context.getPlanVersionId())
        .invalidationScope("TASK_RERUN")
        .invalidationReason("PLAN_VERSION_CHANGED")
        // ... existing fields
        .build();
```

- [ ] **Step 3: 只允许 `MemoryWritebackService` 在通过治理后写 DOMAIN 记录**

Implementation note:
`SchemaExtractorAgent` 不再直接制造长期可复用 DOMAIN 记录；保留 `QualityReviewAgent` 终审通过后通过 `MemoryWritebackService` 写 DOMAIN 的现有路径。

- [ ] **Step 4: 运行边界与 migration 回归**

Run:
`mvn -pl backend "-Dtest=SchemaExtractorAgentTest,QualityReviewAgentTest" test`

Expected:
- PASS

---

### Task 5: 回归报告、导出、证据查询与质检诊断投影

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssemblerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ExportPackageServiceTest.java`

- [ ] **Step 1: 在证据查询投影中把采集质量字段显式结构化**

```java
putIfPresent(metadata, "qualitySignals", readStringList(metadata, "qualitySignals"));
putIfPresent(metadata, "structuredBlocks", metadata.get("structuredBlocks"));
putIfPresent(metadata, "qualityScore", metadata.get("qualityScore"));
putIfPresent(metadata, "failureKind", metadata.get("failureKind"));
```

Implementation note:
这里不是把 `pageMetadata` 原样透传，而是要让 `EvidenceQueryService` 产出的 `EvidenceInfo`、`SectionEvidenceBundleInfo`、`EvidenceEntryPointInfo` 可以直接回答：
- 哪个 evidence 命中了结构块
- 缺的是哪类结构块
- 当前 evidence 的质量信号是什么

- [ ] **Step 2: 让 `ReportService` / `ReportDiagnosisAssembler` 把结构化证据缺口升级为业务可读诊断**

```java
summary = "当前报告暂不可交付，主要缺口已从“有无来源链接”进一步细化为“结构块证据不足 / 字段 coverage 缺失 / 正文质量不足”。";
```

Implementation note:
本轮至少要让 `deliverySummary`、`auditSummary`、`reportDiagnosis.sections[*].repairSuggestions` 能区分：
1. `sourceUrls` 缺失
2. `structuredBlocks` 缺失
3. `qualitySignals` 命中失败
4. `evidenceCoverage` 缺字段

- [ ] **Step 3: 运行报告与交付投影回归**

Run:
`mvn -pl backend "-Dtest=EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`

Expected:
- PASS

---

### Task 6: 第八轮聚合验证、文档回链与 dev live 验收

**Files:**

- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/problem/ExtractionStructured.md`
- Modify: `docs/superpowers/task/2026-06-17-search-and-collection-eighth-iteration-downstream-evidence-closure-implementation-plan.md`

- [ ] **Step 1: 运行第八轮聚合测试**

Run:
`mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceProjectionContractTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`

Expected:
- PASS

- [ ] **Step 2: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

- [ ] **Step 3: 执行第八轮 dev live 验收**

Manual API smoke:

1. 准备一条已知会命中结构块的任务，例如同时包含官网定价页、文档页、新闻正文页。
2. `POST /api/task/{id}/execute`
   - 确认 `collect_sources_*` 输出包含 `downstreamEvidenceViews`
   - 确认 `extract_schema` 输出不再只依赖截断正文，包含 `downstreamEvidenceViews / evidenceFragments / sectionEvidenceBundles`
3. `POST /api/task/{id}/resume`
   - 确认 `extract_schema -> analyze_competitors -> write_report -> quality_check` 在同一次任务链路中沿用同一组 `sourceUrls / issueFlags / structured evidence` 语义
4. `GET /api/report/{taskId}`
   - 确认 `deliverySummary / evidenceEntryPoint / reportDiagnosis / sectionEvidenceBundles` 可直接看到结构块缺口与质量信号
5. `GET /api/task/{id}/replay`
   - 确认主停点若仍出现在 reviewer，能够明确区分是 `采集质量问题 / 结构块缺口 / coverage 缺口 / unsupported_claim`
6. 若终审通过，再检查写回：
   - 确认 extractor 落库记录是 `TASK` 边界
   - 只有终审通过后的 writeback 才产生 `DOMAIN` 记忆

Expected:
- 第八轮验收重点不再是“多采了多少来源”，而是“当前所有下游节点是否终于在消费同一条正式证据边界”。
- 如果任务最终仍被 reviewer 拦住，必须能明确指出阻断发生在：
  - `structured block` 不足
  - `coverage` 缺口
  - `unsupported claim`
  - `actionability` 不足
  之一，而不是再次退化为“结果不好”。

- [ ] **Step 4: 回写父计划、总控看板与提取诊断状态**

Update parent plan wording like:

```md
第八轮实施承接 `Wave 12`，把 `collectionAudit / qualitySignals / structuredBlocks / sourceUrls`
正式传给 `extract_schema -> analyze_competitors -> write_report -> quality_check -> report / export`，
并将 extractor 默认 `DOMAIN` 污染路径改为显式 `TASK` 快照边界。
```

Update specs wording like:

```md
- 搜索与采集：第八轮开始进入 `Wave 12` 下游证据闭环，重点不再是新增采集 owner，
  而是让采集质量信号、结构块与字段 coverage 正式传导到提取、分析、写作、质检与交付主路径。
- 提取结构化：从 `⬜ 方案` 升为 `🟡`，已具备正式实施计划入口，围绕 `ExtractResult vs CompetitorKnowledge`
  边界、TASK / DOMAIN 语义分层与共享追溯契约收口推进。
```

Update `ExtractionStructured.md` note like:

```md
第八轮已把本诊断中的两个核心 blocking 显式转成实施项：
1. 下游正式边界由 `ExtractResult / CompetitorKnowledgeDraft / EvidenceFragment / SectionEvidenceBundle / DownstreamEvidenceView` 共同承接；
2. extractor 默认不再直接落 DOMAIN 记忆，任务现场快照与长期领域知识改走显式边界。
```

- [ ] **Step 5: 完成后给出下一轮建议**

Completion note should say:

```md
本轮完成后，下一步建议按真实瓶颈二选一：
1. 若下游证据边界已统一，但采集来源仍不足：回到 `Wave 10 / Wave 11`，补 news discovery / subscription。
2. 若下游边界已统一，主瓶颈转到分析推理或报告写作语义：分别启动 `分析推理`、`报告写作`、`质量审查` 专题诊断与方案。
```

---

## Verification

- extractor 输入与统一证据视图：`mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,SchemaExtractorAgentTest" test`
- 下游统一证据视图：`mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest,ReportWriterAgentTest,QualityReviewAgentTest" test`
- 任务快照 / 记忆边界：`mvn -pl backend "-Dtest=SchemaExtractorAgentTest,QualityReviewAgentTest" test`
- 报告 / 证据查询 / 导出投影：`mvn -pl backend "-Dtest=DownstreamEvidenceProjectionContractTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`
- 第八轮整体：`mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceProjectionContractTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `Wave 12` 的正式下游证据视图由 Task 1、Task 2、Task 3 覆盖。
2. `ExtractResult vs CompetitorKnowledge` 边界与 `TASK / DOMAIN` 分层由 Task 2、Task 4 覆盖。
3. 报告、质检、交付主路径消费统一证据契约由 Task 3、Task 5 覆盖。
4. Flyway migration 与实体边界同步落地由 Task 4 覆盖。
5. 文档回链与 dev live 验收由 Task 6 覆盖。

### Placeholder scan

1. 本计划未使用 `TODO / TBD / implement later`。
2. 每个任务都给出了具体文件、命令和预期结果。
3. 本轮未把 `Wave 11` 订阅专题、跨重启 replay 底座或新的 News discovery 误塞进 scope。

### Type consistency

1. `DownstreamEvidenceView` 只承接下游统一证据输入，不替代 `EvidenceFragment / SectionEvidenceBundle` 的共享追溯职责。
2. `ExtractResult / CompetitorKnowledgeDraft / AnalysisResult` 在本轮都只追加统一证据视图，不破坏现有契约。
3. `CompetitorKnowledge` 在本轮被明确区分为 `TASK` 快照与受治理的 `DOMAIN` 写回载体。
4. `RevisionDirective / RevisionPlan / ReportResponse` 持续承接 `sourceUrls` 与解释型诊断，不再退回模糊字符串。
