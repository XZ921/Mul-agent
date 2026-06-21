# Post-Collection Downstream Evidence Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Execution Order Note (2026-06-19 refresh):** 本文档不作废，但执行顺序需要按当前项目状态调整。`family discovery convergence` 和 `site discovery deep collection` 已完成；执行本文档前，先完成并冻结 [2026-06-19-search-and-collection-jina-first-playwright-minimization-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/task/search-and-collection/2026-06-19-search-and-collection-jina-first-playwright-minimization-plan.md)。该计划完成后，搜索与采集专题只保留 P0/P1 缺陷修复；本文档作为“切回业务主线后的第一项”执行。

**Goal:** 承接父方案 `Wave 12`，把采集阶段已经正式化的 `collectionAudit / qualitySignals / structuredBlocks / sourceUrls` 收口为下游统一证据视图，先打通 `extract_schema -> analyze_competitors` 的正式消费边界，再渐进传导到 `write_report -> quality_check -> report / export`，并切断“任务现场抽取结果默认伪装成 DOMAIN 记忆”的持续污染。

**Architecture:** 本轮不再扩新的搜索 provider、RSS / feed 订阅能力、Playwright 策略或跨重启 replay 底座，而是聚焦“采集结果如何被下游真实消费”。执行拆成两个切片：Slice A 只做最小业务闭环，按 `红灯契约 -> DownstreamEvidenceView 装配器 -> extractor 正式输入 -> analyzer 消费边界 -> TASK/DOMAIN 记忆边界` 推进；Slice B 再把同一证据视图投影到 writer、reviewer、report、export。正式边界采用“三层分工”：`DownstreamEvidenceView` 表示采集证据的标准运行期视图；`ExtractResult / CompetitorKnowledgeDraft / EvidenceFragment / SectionEvidenceBundle` 继续承接抽取与分析契约；`CompetitorKnowledge` 退回任务快照与受治理的记忆写回载体，显式区分 `TASK` 与 `DOMAIN` 语义。

**Tech Stack:** Java 17, Spring Boot, Jackson, Flyway, JPA, JUnit 5, Mockito

---

## Scope Guard

### Slice A 必须完成

1. 新增统一装配边界，集中把 `EvidenceSource.pageMetadata` 里的 `qualitySignals / structuredBlocks / structuredPayload / sourceUrls / qualityScore / failureKind` 转换为 `DownstreamEvidenceView`，禁止 extractor、analyzer、report 各自重复解析 metadata。
2. `SchemaExtractorAgent` 不得再把 `EvidenceSource.fullContent` 直接当作唯一正式输入；必须先消费 `DownstreamEvidenceView`，并把 `sourceUrls / qualitySignals / structuredBlocks / issueFlags` 写入 prompt 与 `ExtractResult`。
3. `ExtractResult`、`CompetitorKnowledgeDraft`、`AnalysisResult` 必须共享同一组正式证据契约字段，至少覆盖：
   - `sourceUrls`
   - `issueFlags`
   - `evidenceFragments`
   - `sectionEvidenceBundles`
   - `qualitySignals`
   - `structuredBlocks`
   - `evidenceCoverage`
4. `SchemaExtractorAgent` 与 `CompetitorAnalysisAgent` 之间必须收口正式消费边界，analyzer 优先消费 extractor 输出的统一证据视图；只有历史任务或回放缺失该视图时，才允许回退到 repository 任务快照。
5. `CompetitorKnowledge` 必须显式区分“任务现场抽取快照”和“终审通过后允许写回的 DOMAIN 记忆”；extract 阶段默认不得再落 `memoryLayer=DOMAIN`。
6. 为 `CompetitorKnowledge` 新增或补齐正式边界字段时，必须同步提供 Flyway migration，不能只改实体。当前迁移目录截至 2026-06-19 为 `V26`，因此本计划继续使用 `V27`；执行前如已有新 migration，必须改成实际下一个版本号。
7. 所有新增对象和投影继续强制满足 `sourceUrls` 可追溯红线；缺少来源时返回空列表并显式打缺口标记。

### Slice B 完成条件

1. Writer、Reviewer、ReportService、EvidenceQueryService 能直接解释“某个结论为什么证据不足、缺的到底是 sourceUrls、structured block，还是字段 coverage”，而不是只看到大字符串或模糊 issue。
2. 测试覆盖真实主停点：`unsupported_claim`、`missing_evidence`、`STRUCTURE_COMPLETENESS`、`EVIDENCE_TRACEABILITY`、`ACTIONABILITY` 不再只依赖正文句子审查，还要感知采集质量与字段 coverage。
3. Slice B 不得反向修改搜索、采集、Jina、Playwright 路由；如果发现采集质量不足，只记录缺口并转缺陷或 future，不在本计划内继续扩采集能力。

### 本轮明确不做

1. 不继续扩 `Wave 10` 的 News discovery、额外 GitHub vertical discovery provider、Atom 支持或 `Wave 11` subscription / cursor / 去重窗口。
2. 不继续调整 JinaReader、DirectHtmlReader、Playwright fallback、站内递归、候选验证或搜索 ranking；这些全部归 2026-06-19 搜索采集收尾计划或后续 P0/P1 缺陷。
3. 不重写 `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`NodeExecutionRecoveryPolicy` 的总体恢复策略。
4. 不把跨重启 replay 持久化底座并入本轮；这仍是父方案里独立的后续平台任务。
5. 不新建前端页面或重做前端交互，只要求后端 DTO、查询投影和导出契约正式可用。
6. 不把 `CompetitorAnalysisAgent`、`ReportWriterAgent`、`QualityReviewAgent` 拆成大量新类；Slice A 只允许新增一个证据视图装配器，Slice B 只做必要的投影增强。
7. 不把 DOMAIN 记忆融合策略整体重构成独立专题，只先阻断 extractor 默认污染长期层的路径。

---

## Current Stage

当前阶段：搜索与采集已经完成 family discovery convergence、site discovery deep collection，并进入 2026-06-19 `DirectHtmlReader -> JinaReader -> Playwright` 轻量采集收尾。父方案 `Wave 9 / Wave 12` 的主停点已经从“能否采到更多页面”转移到“采集质量信号是否被 extractor、analyzer、writer、reviewer 和 report/export 正式消费”。`ExtractionStructured.md` 已完成诊断；本文档现在承担“采集冻结后切回业务主链路”的第一项计划。

同时，2026-06-19 复核发现旧计划 scope 过宽：如果一次性推进 writer / reviewer / report / export 全量投影，会继续拖住业务主线。因此执行时必须先落 Slice A 最小闭环，确认 `extract_schema -> analyze_competitors` 已共享同一组证据视图和 TASK 记忆边界，再启动 Slice B。

- [x] 父方案 `Wave 12` 范围确认：已完成
- [x] 第六轮 / 第七轮尾证复核：已完成
- [x] `ExtractionStructured.md` blocking 归并：已完成
- [x] 下游链路真实代码边界核对：已完成
- [x] 第八轮实施计划落稿：已完成
- [x] family discovery convergence 前置条件：已完成
- [x] site discovery deep collection 前置条件：已完成
- [x] 2026-06-19 轻量采集收尾：已完成，搜索采集专题已冻结并切入 `Wave 12`
- [x] Slice A 下游证据最小闭环：已完成
- [x] Slice B 报告/质检/交付投影增强：已完成

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase A0 | 等待并冻结 2026-06-19 轻量采集收尾 | 0.5-1 天 | Direct/Jina/Playwright 路由验收通过 | 已完成 |
| Phase A1 | 锁定下游证据闭环红灯契约 | 0.5 天 | Phase A0 完成，`ExtractionStructured.md` 已存在 | 已完成 |
| Phase A2 | 新增统一证据视图对象与装配器 | 0.5-1 天 | Phase A1 红灯测试存在 | 已完成 |
| Phase A3 | 收口 extractor 正式输入与 TASK 快照边界 | 1 天 | Phase A2 完成 | 已完成 |
| Phase A4 | 让 analyzer 优先消费 extractor 统一证据视图 | 0.5-1 天 | Phase A3 完成 | 已完成 |
| Phase A5 | Slice A 聚合验证与文档停点 | 0.5 天 | Phase A1-A4 完成 | 已完成 |
| Phase B1 | Writer / Reviewer 消费同一证据视图 | 1 天 | Slice A 已完成 | 已完成 |
| Phase B2 | Report / EvidenceQuery / Export 投影增强 | 1 天 | Phase B1 完成 | 已完成 |
| Phase B3 | dev live 验收与父文档回链 | 0.5-1 天 | Phase B1-B2 完成 | 已完成 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceView.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceBlock.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceQuality.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceViewAssembler.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/DownstreamEvidenceClosureContractTest.java`
- `backend/src/main/resources/db/migration/V27__add_task_memory_boundary_columns_to_competitor_knowledge.sql`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
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
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceViewAssemblerTest.java`

### Docs - Modify

- `docs/superpowers/plans/search-and-collection/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- `docs/problem/ExtractionStructured.md`

---

### Task 1: 锁定 Slice A 下游证据红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/DownstreamEvidenceClosureContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceViewAssemblerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`

- [ ] **Step 1: 新建下游统一证据视图红灯测试，锁定 extractor 不再只吃大字符串正文**

```java
package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceQuality;
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

- [ ] **Step 2: 新建装配器红灯测试，锁定 pageMetadata 只由统一边界解析**

```java
package cn.bugstack.competitoragent.workflow.contract;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamEvidenceViewAssemblerTest {

    @Test
    void shouldBuildEvidenceViewsFromEvidenceSourceMetadata() {
        EvidenceSource evidence = EvidenceSource.builder()
                .evidenceId("E001")
                .competitorName("Acme")
                .sourceType("DOCS")
                .title("Pricing Docs")
                .url("https://docs.example.com/pricing")
                .fullContent("公开定价页正文")
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://docs.example.com/pricing"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                          "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                          "qualityScore": 0.82
                        }
                        """)
                .build();

        DownstreamEvidenceViewAssembler assembler = new DownstreamEvidenceViewAssembler(new com.fasterxml.jackson.databind.ObjectMapper());
        List<DownstreamEvidenceView> views = assembler.fromEvidenceSources(List.of(evidence));

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getSourceUrls()).containsExactly("https://docs.example.com/pricing");
        assertThat(views.get(0).getQualitySignals()).contains("STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT");
        assertThat(views.get(0).getStructuredBlocks()).extracting(DownstreamEvidenceBlock::getBlockType)
                .containsExactly("PRICING_BLOCK");
    }
}
```

- [ ] **Step 3: 扩展 extractor / analyzer / memory 红灯测试，锁定 Slice A 三个核心缺口**

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
void shouldCarryDownstreamEvidenceViewsFromExtractorToAnalyzer() {
    // 锁定 analyzer 优先读取 extract_schema 输出里的 downstreamEvidenceViews，
    // 而不是只回库读取 CompetitorKnowledge 后重新拼 prompt。
}
```

- [ ] **Step 4: 运行 Slice A 红灯测试集合**

Run:
`mvn -pl backend "-Dtest=DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`

Expected:
- FAIL
- `DownstreamEvidenceView` 相关对象不存在
- extractor 仍主要按 `fullContent.substring(0, 8000)` 构造输入
- `CompetitorKnowledge` 默认仍会被补成 `memoryLayer=DOMAIN`
- analyzer 还不能稳定从 extractor 输出中消费 `downstreamEvidenceViews`

---

### Task 2: 新增统一证据视图对象与装配器

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceView.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceBlock.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceQuality.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceViewAssembler.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceViewAssemblerTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

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

- [ ] **Step 2: 新建 `DownstreamEvidenceBlock` 与 `DownstreamEvidenceQuality`**

```java
package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 下游证据结构块。
 * 结构块保留采集阶段识别到的价格、文档、发布说明等高价值片段，
 * 让抽取、分析和质检能区分“正文存在”与“结构化证据足够”。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DownstreamEvidenceBlock {

    private String blockType;
    private String summary;
    @Builder.Default
    private List<String> sourceUrls = List.of();

    public DownstreamEvidenceBlock normalized() {
        return this.toBuilder()
                .sourceUrls(sourceUrls == null ? List.of() : sourceUrls.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList())
                .build();
    }
}
```

```java
package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下游证据质量摘要。
 * 该对象只承接采集质量的稳定字段，避免下游直接依赖完整 pageMetadata。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DownstreamEvidenceQuality {

    private Double qualityScore;
    private String failureKind;
    private Long durationMillis;

    public DownstreamEvidenceQuality normalized() {
        return this.toBuilder()
                .failureKind(failureKind == null || failureKind.isBlank() ? null : failureKind.trim())
                .build();
    }
}
```

- [ ] **Step 3: 新建统一装配器，集中解析 `EvidenceSource.pageMetadata`**

```java
package cn.bugstack.competitoragent.workflow.contract;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 下游证据视图装配器。
 * 所有下游节点都应通过这里把 EvidenceSource 转成 DownstreamEvidenceView，
 * 避免 extractor、analyzer、report 各自解析 pageMetadata 导致字段漂移。
 */
@Component
@RequiredArgsConstructor
public class DownstreamEvidenceViewAssembler {

    private final ObjectMapper objectMapper;

    public List<DownstreamEvidenceView> fromEvidenceSources(List<EvidenceSource> evidences) {
        List<DownstreamEvidenceView> views = new ArrayList<>();
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            JsonNode metadata = readJson(evidence.getPageMetadata());
            List<String> sourceUrls = readStringList(metadata.path("sourceUrls"));
            if (sourceUrls.isEmpty() && evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
                sourceUrls = List.of(evidence.getUrl().trim());
            }
            views.add(DownstreamEvidenceView.builder()
                    .evidenceId(evidence.getEvidenceId())
                    .competitorName(evidence.getCompetitorName())
                    .sourceType(evidence.getSourceType())
                    .title(evidence.getTitle())
                    .content(firstNonBlank(evidence.getFullContent(), evidence.getContentSnippet()))
                    .sourceUrls(sourceUrls)
                    .issueFlags(readStringList(metadata.path("issueFlags")))
                    .qualitySignals(readStringList(metadata.path("qualitySignals")))
                    .structuredBlocks(readStructuredBlocks(metadata.path("structuredBlocks"), sourceUrls))
                    .quality(DownstreamEvidenceQuality.builder()
                            .qualityScore(readDouble(metadata.path("qualityScore")))
                            .failureKind(readText(metadata.path("failureKind")))
                            .durationMillis(readLong(metadata.path("durationMillis")))
                            .build())
                    .build()
                    .normalized());
        }
        return views;
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText(null);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values.stream().distinct().toList();
    }

    private List<DownstreamEvidenceBlock> readStructuredBlocks(JsonNode node, List<String> fallbackSourceUrls) {
        List<DownstreamEvidenceBlock> blocks = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                blocks.add(DownstreamEvidenceBlock.builder()
                        .blockType(readText(item.path("blockType")))
                        .summary(readText(item.path("summary")))
                        .sourceUrls(readStringList(item.path("sourceUrls")).isEmpty()
                                ? fallbackSourceUrls
                                : readStringList(item.path("sourceUrls")))
                        .build()
                        .normalized());
            }
        }
        return blocks;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String readText(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private Double readDouble(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asDouble();
    }

    private Long readLong(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asLong();
    }
}
```

- [ ] **Step 4: 在 collector 输出中增加 `downstreamEvidenceViews`**

Use the assembler instead of local metadata parsing:

```java
output.put("downstreamEvidenceViews", downstreamEvidenceViewAssembler.fromEvidenceSources(
        evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId())
));
```

Implementation note:
`CollectorAgent` 构造器需要注入 `DownstreamEvidenceViewAssembler`。如果现有测试大量手动构造 `CollectorAgent`，同步补入 `new DownstreamEvidenceViewAssembler(new ObjectMapper())` 或 Mockito mock。

- [ ] **Step 5: 运行装配器与 collector 边界测试**

Run:
`mvn -pl backend "-Dtest=DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,CollectorAgentTest" test`

Expected:
- PASS

---

### Task 3: 打通 `extract -> analyze` 统一证据视图

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/ExtractResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CompetitorKnowledgeDraft.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`

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

- [ ] **Step 3: 让 extractor 从 `DownstreamEvidenceView` 构造 prompt，而不是只拼接截断正文**

```java
List<DownstreamEvidenceView> evidenceViews = downstreamEvidenceViewAssembler.fromEvidenceSources(competitorEvidence);
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
        if (evidence.getQuality() != null) {
            collectedContent.append("quality: ").append(evidence.getQuality()).append('\n');
        }
        if (evidence.getStructuredBlocks() != null && !evidence.getStructuredBlocks().isEmpty()) {
            collectedContent.append("structuredBlocks: ").append(evidence.getStructuredBlocks()).append('\n');
        }
        String content = evidence.getContent();
        if (content != null && content.length() > 4000) {
            // 单条正文只做兜底截断，结构化质量信号必须保留在正文前面。
            content = content.substring(0, 4000) + "...(truncated)";
        }
        collectedContent.append(content == null ? "" : content).append("\n\n");
    }
    return collectedContent.toString();
}
```

Implementation note:
`SchemaExtractorAgent` 构造器需要注入 `DownstreamEvidenceViewAssembler`。`buildEvidenceCatalog` 也应改为接收 `List<DownstreamEvidenceView>`，至少输出 `evidenceId/title/sourceUrls/qualitySignals/structuredBlockTypes/snippet`。

- [ ] **Step 4: 运行下游统一证据视图回归**

Run:
`mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`

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
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

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
当前迁移目录最后一个版本是 `V26__add_task_quota_reserved_flag.sql`，因此本计划使用 `V27`。执行前必须重新查看 `backend/src/main/resources/db/migration`，如果已有新版本，文件名改成实际下一个版本。字段命名可以与实现微调，但目标必须保持一致：`CompetitorKnowledge` 要能显式表达“这是任务现场快照”，而不是只靠 `memoryLayer` 间接猜。

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
`SchemaExtractorAgent` 不再直接制造长期可复用 DOMAIN 记录；保留终审通过后通过 `MemoryWritebackService` 写 DOMAIN 的现有路径。本任务只阻断默认污染，不重构 `MemoryReusePolicy` 的整体检索/融合策略。

- [ ] **Step 4: 运行边界与 migration 回归**

Run:
`mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test`

Expected:
- PASS

---

### Task 5: Slice B 回归 writer、reviewer、报告、导出与证据查询投影

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/QualityDiagnosis.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/RevisionDirective.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/RevisionPlan.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssemblerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ExportPackageServiceTest.java`

Slice gate:
只有 Task 1-4 全部通过后才能启动本任务。若 Slice A 未完成，不允许先改 reviewer/report，否则会继续形成“下游又各自解析一套证据”的分裂状态。

- [ ] **Step 1: 让 reviewer 诊断显式携带采集质量语义**

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
        .repairSuggestion("请优先补齐结构块证据；如果采集端已冻结，则记录为证据缺口，不在本轮继续扩采集能力。")
        .build()
        .normalized());
```

Implementation note:
`QualityReviewAgent` 需要新增一条判断：当章节缺口并非纯正文引用缺失，而是 `DownstreamEvidenceView.structuredBlocks` 或 `qualitySignals` 已表明“结构化证据不足”时，diagnosis 必须能区分为采集质量问题，而不是继续都压成通用 `unsupported_claim`。

- [ ] **Step 2: 在证据查询投影中把采集质量字段显式结构化**

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

- [ ] **Step 3: 让 `ReportService` / `ReportDiagnosisAssembler` 把结构化证据缺口升级为业务可读诊断**

```java
summary = "当前报告暂不可交付，主要缺口已从“有无来源链接”进一步细化为“结构块证据不足 / 字段 coverage 缺失 / 正文质量不足”。";
```

Implementation note:
本轮至少要让 `deliverySummary`、`auditSummary`、`reportDiagnosis.sections[*].repairSuggestions` 能区分：
1. `sourceUrls` 缺失
2. `structuredBlocks` 缺失
3. `qualitySignals` 命中失败
4. `evidenceCoverage` 缺字段

- [ ] **Step 4: 运行报告与交付投影回归**

Run:
`mvn -pl backend "-Dtest=ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`

Expected:
- PASS

---

### Task 6: 第八轮聚合验证、文档回链与 dev live 验收

**Files:**

- Modify: `docs/superpowers/plans/search-and-collection/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/problem/ExtractionStructured.md`
- Modify: `docs/superpowers/task/search-and-collection/2026-06-17-search-and-collection-eighth-iteration-downstream-evidence-closure-implementation-plan.md`

- [x] **Step 1: 运行 Slice A 聚合测试**

Run:
`mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`

Expected:
- PASS

Result:
- 2026-06-20 已 fresh 通过：`Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`

- [x] **Step 2: 运行 Slice B 聚合测试**

Run:
`mvn -pl backend "-Dtest=ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`

Expected:
- PASS

Result:
- 2026-06-20 已 fresh 通过：`Tests run: 41, Failures: 0, Errors: 0, Skipped: 0`

- [x] **Step 3: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

Result:
- 2026-06-20 已 fresh 通过：`mvn -pl backend test`

- [x] **Step 4: 执行第八轮 dev live 验收**

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

Result:
- 2026-06-20 已用真实任务 `50` 完成 live 验收。
- 首次验收失败根因不是链路未继续执行，而是本地旧 Spring Boot 进程占用 `9093`，rerun 实际命中了旧代码；清理旧进程并重启最新 backend 后，验收恢复正常。
- `POST /api/task/50/nodes/extract_schema/rerun` 后，`extract_schema -> analyze_competitors -> write_report -> quality_check -> rewrite_report -> quality_check_final` 全部执行到 `SUCCESS`。
- `GET /api/report/50` 已返回 `deliverySummary / evidenceEntryPoint / reportDiagnosis / sectionEvidenceBundles / revisionPlan`。
- `GET /api/report/50/evidences` 已返回 `qualitySignals / structuredBlocks / qualityScore / failureKind / sourceUrls`。
- `GET /api/task/50/replay` 已能从回放侧看到事件时间线、来源与诊断线索。
- extractor 落库记录已确认走 `TASK` 边界，而不是旧的默认 `DOMAIN` 污染路径。
- 任务总状态仍为 `FAILED`，但根因已收敛为真实业务质量门禁：`qualityPassed=false`、`qualityScore=46`、`deliveryStatus=BLOCKED`、`primaryIssue=关键结论缺少充分支撑`，不再是执行链路崩溃。

- [x] **Step 5: 回写父计划、总控看板与提取诊断状态**

Update parent plan wording like:

```md
第八轮实施承接 `Wave 12`，把 `collectionAudit / qualitySignals / structuredBlocks / sourceUrls`
正式传给 `extract_schema -> analyze_competitors -> write_report -> quality_check -> report / export`，
并将 extractor 默认 `DOMAIN` 污染路径改为显式 `TASK` 快照边界。
```

Update specs wording like:

```md
- 搜索与采集：完成 2026-06-19 轻量采集收尾后冻结，后续第八轮进入 `Wave 12` 下游证据闭环，重点不再是新增采集 owner，
  而是让采集质量信号、结构块与字段 coverage 正式传导到提取、分析、写作、质检与交付主路径。
- 提取结构化：从 `⬜ 方案` 升为 `🟡`，已具备正式实施计划入口，围绕 `ExtractResult vs CompetitorKnowledge`
  边界、TASK / DOMAIN 语义分层与共享追溯契约收口推进，并作为第八轮完成后的直接下一项任务。
```

Update `ExtractionStructured.md` note like:

```md
第八轮已把本诊断中的两个核心 blocking 显式转成实施项：
1. 下游正式边界由 `ExtractResult / CompetitorKnowledgeDraft / EvidenceFragment / SectionEvidenceBundle / DownstreamEvidenceView` 共同承接；
2. extractor 默认不再直接落 DOMAIN 记忆，任务现场快照与长期领域知识改走显式边界。
```

- [x] **Step 6: 完成后给出下一轮建议**

Completion note should say:

```md
本轮完成后，下一步直接进入 `提取结构化` 任务，
继续围绕 `ExtractResult vs CompetitorKnowledge` 边界、`TASK / DOMAIN` 语义分层与共享追溯契约推进主链路。
`跨任务缓存与隐私 / TTL / 失效设计`、`Playwright 浏览器并发上下文池设计` 只登记为后续专项，
不并入当前收尾与第八轮主线，待提取结构化主任务完成后，再根据真实 trace / report 指标决定优先级。
```

---

## Verification

- 轻量采集前置：执行并通过 `2026-06-19-search-and-collection-jina-first-playwright-minimization-plan.md` 中的定向回归；未完成前不得启动本文档实现。
- Slice A 红灯：`mvn -pl backend "-Dtest=DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`
- 证据视图装配器与 collector：`mvn -pl backend "-Dtest=DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,CollectorAgentTest" test`
- extractor / analyzer 统一证据视图：`mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`
- 任务快照 / 记忆边界：`mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test`
- Slice A 聚合：`mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`
- Slice B 报告 / 证据查询 / 导出投影：`mvn -pl backend "-Dtest=ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`
- 第八轮整体：`mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `Wave 12` 的正式下游证据视图由 Task 1、Task 2、Task 3 覆盖。
2. `ExtractResult vs CompetitorKnowledge` 边界与 `TASK / DOMAIN` 分层由 Task 3、Task 4 覆盖。
3. 报告、质检、交付主路径消费统一证据契约由 Slice B 的 Task 5 覆盖。
4. Flyway migration 与实体边界同步落地由 Task 4 覆盖。
5. 文档回链与 dev live 验收由 Task 6 覆盖。
6. 2026-06-19 刷新后的搜索采集冻结要求由 Scope Guard、Current Stage 和 Verification 前置项覆盖。

### Placeholder scan

1. 本计划未留下未完成占位语句。
2. 每个任务都给出了具体文件、命令和预期结果。
3. 本轮未把 `Wave 11` 订阅专题、跨重启 replay 底座、新的 News discovery 或 Playwright 路由调整误塞进 scope。

### Type consistency

1. `DownstreamEvidenceView` 只承接下游统一证据输入，不替代 `EvidenceFragment / SectionEvidenceBundle` 的共享追溯职责。
2. `ExtractResult / CompetitorKnowledgeDraft / AnalysisResult` 在本轮都只追加统一证据视图，不破坏现有契约。
3. `CompetitorKnowledge` 在本轮被明确区分为 `TASK` 快照与受治理的 `DOMAIN` 写回载体。
4. `RevisionDirective / RevisionPlan / ReportResponse` 持续承接 `sourceUrls` 与解释型诊断，不再退回模糊字符串。
5. `DownstreamEvidenceViewAssembler` 是唯一允许从 `EvidenceSource.pageMetadata` 装配下游证据视图的边界，避免 extractor、analyzer、report 重复解析导致字段漂移。
## 2026-06-20 执行进度回写

### 已完成

- Slice A 已完成：
  - `DownstreamEvidenceView / DownstreamEvidenceViewAssembler` 已落地。
  - `CollectorAgent / SchemaExtractorAgent / CompetitorAnalysisAgent` 已共享统一下游证据视图。
  - extractor 默认 `DOMAIN` 污染路径已切换为显式 `TASK` 快照边界，并补充了 `V27` Flyway。
- Slice B 已完成：
  - `QualityReviewAgent` 新增 `missing_structured_evidence` 诊断，能显式识别 `structuredBlocks / qualitySignals / qualityScore / failureKind` 造成的结构化证据不足。
  - `EvidenceQueryService` 已显式投影 `qualitySignals / structuredBlocks / qualityScore / failureKind`。
  - `ReportService / ReportDiagnosisAssembler` 已把交付阻塞细分为 `sourceUrls`、`structuredBlocks`、`qualitySignals`、`evidenceCoverage` 四类业务可读缺口。

### 已完成验证

- 2026-06-20 已通过：
  - `mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`
  - `mvn -pl backend "-Dtest=ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`
  - `mvn -pl backend "-Dtest=QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest" test`
  - `mvn -pl backend "-Dtest=ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`
  - `mvn -pl backend test`
- 2026-06-20 已完成真实 dev live 验收：
  - 清理旧 `9093` 进程并重启最新 backend 后，任务 `50` 的 `extract_schema` rerun 不再命中旧代码。
  - `extract_schema -> analyze_competitors -> write_report -> quality_check -> rewrite_report -> quality_check_final` 全部执行到 `SUCCESS`。
  - `/api/report/50`、`/api/report/50/evidences`、`/api/task/50/replay` 已确认对外暴露统一证据视图与解释型诊断。
  - `competitor_knowledge` 已确认写入 `task_id=50 / memory_layer=TASK / snapshot_scope=TASK / producer_node_name=extract_schema`。

### 待继续执行

- 业务后续跟进：
  - 基于任务 `50` 的 `deliverySummary / reportDiagnosis / revisionPlan`，继续推进真实证据补强与质量闭环，而不是回退到采集或 extractor 崩溃排查。

### 下一轮直接目标

本轮收尾后，下一步仍围绕 `ExtractResult vs CompetitorKnowledge` 边界、`TASK / DOMAIN` 分层和共享追溯契约继续推进；优先结合任务 `50` 当前暴露出的 `structuredBlocks / evidenceCoverage / unsupported_claim / actionability` 诊断，补齐真实业务质量闭环。`跨任务缓存 / TTL / 隐私治理` 与 `Playwright 并发上下文池` 继续登记为后续专题，不并入当前第八轮主线。

## 2026-06-20 二次归因复核与 extractor 零字段保护

### 已完成

- 重新审计任务 `50` 的真实输出后确认：采集侧存在 `structuredBlocks` 不足、首个采集节点 `search_status=DEGRADED`、部分 Notion 页面返回浏览器兼容性正文等质量缺口，但采集结果仍包含可读正文与 8 个 `sourceUrls`，不能单独解释后续报告完全依赖空结构化知识的问题。
- 锁定首个决定性放行点在 `extract_schema`：
  - rerun 前旧输出中 `drafts[0].fieldsExtracted=0`。
  - `summary / positioning / targetUsers / coreFeatures / pricing / strengths / weaknesses` 全为空或空集合。
  - `issueFlags` 只有 `SOURCE_URLS_BACKFILLED`，但节点仍为 `SUCCESS`，导致 analyzer / writer 在空业务字段上继续执行。
- 已在 `SchemaExtractorAgent` 增加零业务字段保护：
  - `sourceUrls` 回填只代表结果可追溯，不再代表抽取成功。
  - 当 `countExtractedFields(schemaJson) == 0` 时，不保存 `CompetitorKnowledge`，不向下游输出成功结果。
  - 节点返回错误：`未能抽取出任何业务字段，请检查提取提示词或模型输出`。
- 已补充/调整测试：
  - 新增 `shouldFailWhenModelReturnsNoBusinessFieldsDespiteUsableEvidence`，覆盖“有可用采集正文但模型返回空业务字段”的真实断点。
  - 将空 JSON 数组恢复用例改为 `shouldRejectRecoveredEmptyJsonArrayWithoutBusinessFields`，避免再次把空对象恢复成成功知识快照。

### 已完成验证

- TDD 红灯已确认：新增测试初次运行失败于 `expected: <FAILED> but was: <SUCCESS>`。
- 代码修复后已通过：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldFailWhenModelReturnsNoBusinessFieldsDespiteUsableEvidence" test`
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test`
  - `mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test`
  - `mvn -pl backend "-Dtest=CollectorAgentTest,DownstreamEvidenceClosureContractTest,DownstreamEvidenceViewAssemblerTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,ReportWriterAgentTest,QualityReviewAgentTest,EvidenceQueryServiceTest,ReportServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest" test`
- 已用最新 backend 对任务 `50` 执行真实 rerun：
  - `POST /api/task/50/nodes/extract_schema/rerun`
  - `analysis_task.status=STOPPED`
  - `extract_schema.status=WAITING_INTERVENTION`
  - `extract_schema.error_message=未能抽取出任何业务字段，请检查提取提示词或模型输出`
  - `analyze_competitors / write_report / quality_check / rewrite_report / quality_check_final` 保持 `PENDING`
- 结论：本次不是继续回头查 extractor 崩溃，也不是盲目补证据；真实归因是 extractor 曾把 `0` 个业务字段的模型输出误判为成功。现在任务会停在正确节点，避免空结构化知识继续污染下游。

### 待继续执行

- 继续定位为什么 extractor 在已有可读采集正文的情况下仍输出空业务字段：
  - 优先检查 extractor prompt 是否过度依赖 `structuredBlocks`，导致正文可读但模型未抽字段。
  - 检查 `DownstreamEvidenceView` 进入 prompt 的 `evidenceCatalog / collectedContent` 是否对业务字段抽取足够明确。
  - 若 prompt 输入完整，再检查模型返回格式与提取指令是否需要增加“必须基于可读正文抽取最小字段”的重试约束。
- 如果确认 extractor 输入不足，再回看采集质量；如果确认输入足够，则下一步属于 extractor prompt / retry / acceptance gate 的结构化提取任务，而不是搜索与采集任务。

### 下一步直接目标

围绕 `extract_schema` 做“非空业务字段抽取”闭环：先复盘 task `50` 的 prompt 输入，再补一个最小 prompt / retry 改动，让已有可读正文至少抽出 `summary / positioning / coreFeatures / pricing` 中的可证字段；随后再恢复 analyzer / writer / reviewer 的质量闭环验证。
