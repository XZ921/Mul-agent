package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.context.TaskRagContextSummaryFormatter;
import cn.bugstack.competitoragent.knowledge.TaskKnowledgeSnapshotResolver;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CollectorSearchAudit;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CompetitorKnowledgeInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CompetitorEvidenceCoverage;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceCoverageOverview;
import cn.bugstack.competitoragent.model.dto.ReportResponse.QualityIssue;
import cn.bugstack.competitoragent.model.dto.ReportResponse.RevisionDirectiveInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewCheckpoint;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewNextAction;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReportDiagnosisInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SearchAuditOverview;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SectionEvidenceBundleInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SectionEvidenceCoverage;
import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.model.dto.RevisionPlan;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.QualityDimension;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 报告服务，负责聚合报告详情以及对外导出 Markdown / HTML。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final EvidenceQueryService evidenceQueryService;
    private final ReportDiagnosisAssembler reportDiagnosisAssembler;
    private final ObjectMapper objectMapper;
    private static final List<CoverageFieldDefinition> COVERAGE_FIELD_DEFINITIONS = List.of(
            new CoverageFieldDefinition("summary", "overview", "产品概览"),
            new CoverageFieldDefinition("positioning", "positioning", "市场定位"),
            new CoverageFieldDefinition("targetUsers", "target_users", "目标用户"),
            new CoverageFieldDefinition("coreFeatures", "features", "核心能力"),
            new CoverageFieldDefinition("pricing", "pricing", "定价策略"),
            new CoverageFieldDefinition("strengths", "strengths", "优势判断"),
            new CoverageFieldDefinition("weaknesses", "weaknesses", "短板与风险")
    );

    /**
     * 组装报告详情页所需的统一视图数据：正文、证据、结构化知识和质检闭环状态。
     */
    public ReportResponse getReport(Long taskId) {
        Report report = reportRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.REPORT_NOT_FOUND, "taskId=" + taskId));

        List<ReportResponse.EvidenceInfo> evidenceInfos = evidenceQueryService.listTaskEvidence(taskId);

        // 报告页只应展示当前任务最新的一组 TASK 快照；
        // 否则 rerun 前的旧 coverage 会和新结果叠加，直接把同一竞品算成两份。
        List<CompetitorKnowledge> knowledges = TaskKnowledgeSnapshotResolver.resolveCurrentTaskSnapshots(
                knowledgeRepository.findByTaskIdOrderByIdAsc(taskId)
        );
        List<CompetitorKnowledgeInfo> knowledgeInfos = knowledges.stream()
                .map(knowledge -> new CompetitorKnowledgeInfo(
                        knowledge.getCompetitorName(),
                        knowledge.getOfficialUrl(),
                        knowledge.getSummary(),
                        knowledge.getPositioning(),
                        parseJsonList(knowledge.getTargetUsers()),
                        parseJsonMap(knowledge.getPricing()),
                        parseJsonList(knowledge.getSourceUrls()),
                        parseJsonMap(knowledge.getEvidenceCoverage())))
                .toList();

        List<QualityIssue> issues = parseQualityIssues(report.getQualityIssues());
        List<TaskNode> nodes = taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        TaskNode initialReviewNode = findNode(nodes, "quality_check");
        TaskNode rewriteNode = findNode(nodes, "rewrite_report");
        TaskNode finalReviewNode = findNode(nodes, "quality_check_final");
        SearchAuditOverview searchAuditOverview = buildSearchAuditOverview(nodes);
        List<ReportResponse.TaskRagAuditInfo> taskRagAudits = buildTaskRagAudits(nodes);
        EvidenceCoverageOverview evidenceCoverageOverview = buildEvidenceCoverageOverview(knowledgeInfos);
        List<SectionEvidenceBundleInfo> sectionEvidenceBundles = buildSectionEvidenceBundles(
                evidenceInfos,
                nodes,
                evidenceCoverageOverview
        );
        ReviewCheckpoint initialReview = toReviewCheckpoint(initialReviewNode);
        ReviewCheckpoint finalReview = toReviewCheckpoint(finalReviewNode);
        ReportDiagnosisInfo reportDiagnosis = reportDiagnosisAssembler.assemble(
                evidenceInfos,
                issues,
                initialReview,
                finalReview,
                evidenceCoverageOverview,
                nodes
        );
        RevisionPlan revisionPlan = resolveRevisionPlan(
                initialReviewNode,
                finalReviewNode,
                initialReview,
                finalReview,
                reportDiagnosis
        );
        ReportResponse.DeliverySummaryInfo deliverySummary = buildDeliverySummary(
                report,
                issues,
                revisionPlan,
                reportDiagnosis
        );
        ReportResponse.EvidenceEntryPointInfo evidenceEntryPoint =
                resolveEvidenceEntryPoint(evidenceInfos, sectionEvidenceBundles);
        ReportResponse.AuditSummaryInfo auditSummary = buildAuditSummary(
                searchAuditOverview,
                taskRagAudits
        );

        // 这里把闭环节点状态和证据摘要一起返回，前端无需再自己拼接多份接口结果。
        return ReportResponse.builder()
                .id(report.getId())
                .taskId(report.getTaskId())
                .title(report.getTitle())
                .content(report.getContent())
                .summary(report.getSummary())
                .qualityScore(report.getQualityScore())
                .qualityPassed(report.isQualityPassed())
                .qualityIssues(issues)
                .initialReview(initialReview)
                .revisionPlan(revisionPlan)
                .rewriteApplied(rewriteNode != null && rewriteNode.getStatus() == TaskNodeStatus.SUCCESS)
                .finalReview(finalReview)
                .evidenceCount(report.getEvidenceCount())
                .evidences(evidenceInfos)
                .sourceUrls(collectReportSourceUrls(
                        evidenceInfos,
                        reportDiagnosis,
                        deliverySummary,
                        evidenceEntryPoint,
                        auditSummary))
                .searchAuditOverview(searchAuditOverview)
                .taskRagAudits(taskRagAudits)
                .evidenceCoverageOverview(evidenceCoverageOverview)
                .sectionEvidenceBundles(sectionEvidenceBundles)
                .competitorKnowledges(knowledgeInfos)
                .reportDiagnosis(reportDiagnosis)
                .deliverySummary(deliverySummary)
                .evidenceEntryPoint(evidenceEntryPoint)
                .auditSummary(auditSummary)
                .createdAt(report.getCreatedAt())
                .updatedAt(report.getUpdatedAt())
                .build();
    }

    /**
     * 交付中心主路径优先回答“当前是否可交付、最大的阻塞是什么、下一步建议做什么”，
     * 因此这里把 report / diagnosis / revision plan 的原始结构收口成一个轻量摘要对象。
     */
    private ReportResponse.DeliverySummaryInfo buildDeliverySummary(Report report,
                                                                    List<QualityIssue> issues,
                                                                    RevisionPlan revisionPlan,
                                                                    ReportDiagnosisInfo reportDiagnosis) {
        int blockerCount = resolveBlockerCount(issues, reportDiagnosis);
        int evidenceGapCount = resolveEvidenceGapCount(issues, reportDiagnosis);
        boolean readyForDelivery = report.isQualityPassed() && blockerCount == 0 && evidenceGapCount == 0;
        String deliveryStatus = readyForDelivery
                ? "READY"
                : blockerCount > 0 ? "BLOCKED" : evidenceGapCount > 0 ? "NEEDS_EVIDENCE" : "REVIEW_REQUIRED";
        String summary = readyForDelivery
                ? "当前报告已满足交付条件，可进入正式导出。"
                : "当前报告暂不可交付，存在 %d 个阻塞问题和 %d 个证据缺口。".formatted(blockerCount, evidenceGapCount);

        return ReportResponse.DeliverySummaryInfo.builder()
                .readyForDelivery(readyForDelivery)
                .deliveryStatus(deliveryStatus)
                .summary(readyForDelivery ? summary : buildDeliverySummaryText(blockerCount, evidenceGapCount, reportDiagnosis))
                .primaryIssue(resolvePrimaryIssue(issues, reportDiagnosis))
                .recommendedAction(resolveRecommendedAction(revisionPlan, reportDiagnosis))
                .blockerCount(blockerCount)
                .evidenceGapCount(evidenceGapCount)
                .sourceUrls(collectDeliverySourceUrls(issues, revisionPlan, reportDiagnosis))
                .build();
    }

    /**
     * 审计摘要只保留主路径最需要解释的两类信息：
     * 1. 采集检索是否可靠；
     * 2. Task RAG 是否真的参与了本次结论生成。
     */
    private ReportResponse.AuditSummaryInfo buildAuditSummary(SearchAuditOverview searchAuditOverview,
                                                              List<ReportResponse.TaskRagAuditInfo> taskRagAudits) {
        String searchAuditSummary = buildSearchAuditSummary(searchAuditOverview);
        String taskRagAuditSummary = buildTaskRagAuditSummary(taskRagAudits);
        List<String> summaryParts = new ArrayList<>();
        if (searchAuditSummary != null) {
            summaryParts.add(searchAuditSummary);
        }
        if (taskRagAuditSummary != null) {
            summaryParts.add(taskRagAuditSummary);
        }
        return ReportResponse.AuditSummaryInfo.builder()
                .summary(summaryParts.isEmpty() ? "当前暂无可展示的审计摘要" : String.join("；", summaryParts))
                .searchAuditSummary(searchAuditSummary)
                .taskRagAuditSummary(taskRagAuditSummary)
                .sourceUrls(collectAuditSourceUrls(searchAuditOverview))
                .build();
    }

    /**
     * 阻塞数优先复用 diagnosis 的聚合结果；
     * 只有旧数据尚未生成 diagnosis 聚合时，才回退到 qualityIssues 自行统计。
     */
    /**
     * 证据入口优先复用 EvidenceQueryService 的投影逻辑，
     * 但如果当前链路还是旧实现、mock 或尚未产出 section bundle，
     * 这里也要退回到最小可读摘要，避免交付中心主路径直接拿到 null。
     */
    private ReportResponse.EvidenceEntryPointInfo resolveEvidenceEntryPoint(List<ReportResponse.EvidenceInfo> evidenceInfos,
                                                                            List<SectionEvidenceBundleInfo> sectionEvidenceBundles) {
        ReportResponse.EvidenceEntryPointInfo projected =
                evidenceQueryService.toEvidenceEntryPointInfo(evidenceInfos, sectionEvidenceBundles);
        if (projected != null) {
            return projected;
        }

        ReportResponse.EvidenceInfo primaryEvidence = evidenceInfos == null || evidenceInfos.isEmpty()
                ? null
                : evidenceInfos.get(0);
        SectionEvidenceBundleInfo primaryBundle = sectionEvidenceBundles == null || sectionEvidenceBundles.isEmpty()
                ? null
                : sectionEvidenceBundles.get(0);

        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (primaryBundle != null && primaryBundle.getSourceUrls() != null) {
            sourceUrls.addAll(primaryBundle.getSourceUrls());
        }
        if (primaryEvidence != null && primaryEvidence.getUrl() != null && !primaryEvidence.getUrl().isBlank()) {
            sourceUrls.add(primaryEvidence.getUrl().trim());
        }

        String summary = primaryBundle == null ? null : defaultText(primaryBundle.getSummary(), null);
        if (summary == null && primaryEvidence != null) {
            summary = "可优先核对证据：" + defaultText(primaryEvidence.getTitle(), primaryEvidence.getUrl());
        }
        if (summary == null && primaryBundle != null) {
            summary = defaultText(primaryBundle.getGapSummary(), null);
        }
        if (summary == null) {
            summary = "当前暂无可直接展开的关键证据入口";
        }

        return ReportResponse.EvidenceEntryPointInfo.builder()
                .summary(summary)
                .sectionKey(primaryBundle == null ? null : primaryBundle.getSectionKey())
                .sectionTitle(primaryBundle == null ? null : primaryBundle.getSectionTitle())
                .evidenceId(primaryEvidence == null ? null : primaryEvidence.getEvidenceId())
                .title(primaryEvidence == null ? null : primaryEvidence.getTitle())
                .url(primaryEvidence == null ? null : primaryEvidence.getUrl())
                .sourceType(primaryEvidence == null ? null : primaryEvidence.getSourceType())
                .sourceUrls(sourceUrls.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList())
                .build();
    }

    /**
     * 交付摘要要把“证据不足”进一步细分成业务可读的阻塞原因，
     * 避免继续停留在泛化的缺证据表述。
     */
    private String buildDeliverySummaryText(int blockerCount,
                                            int evidenceGapCount,
                                            ReportDiagnosisInfo reportDiagnosis) {
        if (reportDiagnosis == null || reportDiagnosis.getSections() == null || reportDiagnosis.getSections().isEmpty()) {
            return "当前报告暂不可交付，存在 %d 个阻塞问题和 %d 个证据缺口。".formatted(blockerCount, evidenceGapCount);
        }
        LinkedHashSet<String> gapKinds = new LinkedHashSet<>();
        for (ReportResponse.DiagnosisSection section : reportDiagnosis.getSections()) {
            if (section == null || section.getDiagnoses() == null) {
                continue;
            }
            for (ReportResponse.DiagnosisItem diagnosisItem : section.getDiagnoses()) {
                if (diagnosisItem == null || diagnosisItem.getDiagnosis() == null) {
                    continue;
                }
                String evidenceBasis = defaultText(diagnosisItem.getDiagnosis().getEvidenceBasis(), "");
                List<String> sourceUrls = diagnosisItem.getDiagnosis().getSourceUrls();
                if (sourceUrls == null || sourceUrls.isEmpty()) {
                    gapKinds.add("sourceUrls 缺失");
                }
                if (evidenceBasis.contains("structuredBlocks")) {
                    gapKinds.add("structuredBlocks 缺失");
                }
                if (evidenceBasis.contains("qualitySignals")) {
                    gapKinds.add("qualitySignals 命中失败");
                }
                if (evidenceBasis.contains("evidenceCoverage")) {
                    gapKinds.add("evidenceCoverage 缺字段");
                }
            }
        }
        if (gapKinds.isEmpty()) {
            return "当前报告暂不可交付，存在 %d 个阻塞问题和 %d 个证据缺口。".formatted(blockerCount, evidenceGapCount);
        }
        return "当前报告暂不可交付，存在 %d 个阻塞问题和 %d 个证据缺口；主要缺口已细化为%s。"
                .formatted(blockerCount, evidenceGapCount, String.join(" / ", gapKinds));
    }

    private int resolveBlockerCount(List<QualityIssue> issues, ReportDiagnosisInfo reportDiagnosis) {
        if (reportDiagnosis != null && reportDiagnosis.getBlockerCount() != null) {
            return reportDiagnosis.getBlockerCount();
        }
        int count = 0;
        for (QualityIssue issue : issues == null ? List.<QualityIssue>of() : issues) {
            if (issue == null) {
                continue;
            }
            if ("BLOCKER".equalsIgnoreCase(defaultText(issue.getLevel(), null))
                    || "ERROR".equalsIgnoreCase(defaultText(issue.getSeverity(), null))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 证据缺口数同样优先使用 diagnosis 汇总，
     * 回退路径只做最小语义判断，避免老数据完全没有摘要可展示。
     */
    private int resolveEvidenceGapCount(List<QualityIssue> issues, ReportDiagnosisInfo reportDiagnosis) {
        if (reportDiagnosis != null && reportDiagnosis.getEvidenceGapCount() != null) {
            return reportDiagnosis.getEvidenceGapCount();
        }
        int count = 0;
        for (QualityIssue issue : issues == null ? List.<QualityIssue>of() : issues) {
            if (issue == null) {
                continue;
            }
            String type = defaultText(issue.getType(), null);
            String basis = defaultText(issue.getEvidenceBasis(), null);
            if ("MISSING_EVIDENCE".equalsIgnoreCase(type)
                    || "EVIDENCE_GAP".equalsIgnoreCase(type)
                    || (basis != null && basis.contains("证据"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 主路径的“最大问题”优先取 diagnosis 第一条标题，
     * 其次回退到 quality issue 的证据依据或建议文案。
     */
    private String resolvePrimaryIssue(List<QualityIssue> issues, ReportDiagnosisInfo reportDiagnosis) {
        if (reportDiagnosis != null && reportDiagnosis.getSections() != null) {
            for (ReportResponse.DiagnosisSection section : reportDiagnosis.getSections()) {
                if (section == null || section.getDiagnoses() == null) {
                    continue;
                }
                for (ReportResponse.DiagnosisItem diagnosisItem : section.getDiagnoses()) {
                    if (diagnosisItem == null || diagnosisItem.getDiagnosis() == null) {
                        continue;
                    }
                    String title = defaultText(diagnosisItem.getDiagnosis().getTitle(), null);
                    if (title != null) {
                        return title;
                    }
                }
            }
        }
        for (QualityIssue issue : issues == null ? List.<QualityIssue>of() : issues) {
            if (issue == null) {
                continue;
            }
            String basis = defaultText(issue.getEvidenceBasis(), null);
            if (basis != null) {
                return basis;
            }
            String suggestion = defaultText(issue.getSuggestion(), null);
            if (suggestion != null) {
                return suggestion;
            }
        }
        return "当前报告仍需进一步复核关键结论与证据关系。";
    }

    /**
     * 下一步动作优先使用 revision plan 的稳定摘要，
     * 若当前任务尚未生成 revision plan，再回退到 diagnosis 的 nextActions。
     */
    private String resolveRecommendedAction(RevisionPlan revisionPlan, ReportDiagnosisInfo reportDiagnosis) {
        if (revisionPlan != null && defaultText(revisionPlan.primaryActionSummary(), null) != null) {
            return revisionPlan.primaryActionSummary();
        }
        if (reportDiagnosis != null && reportDiagnosis.getNextActions() != null) {
            for (ReviewNextAction nextAction : reportDiagnosis.getNextActions()) {
                if (nextAction == null) {
                    continue;
                }
                String description = defaultText(nextAction.getDescription(), null);
                if (description != null) {
                    return description;
                }
                String title = defaultText(nextAction.getTitle(), null);
                if (title != null) {
                    return title;
                }
            }
        }
        return "建议先回到任务链路补齐关键证据，再决定是否继续重写或导出。";
    }

    /**
     * 报告顶层 sourceUrls 是交付主路径的总入口，优先保留证据列表中的原始 URL，
     * 再合并诊断、交付摘要、证据入口和审计摘要，避免前端到多个嵌套对象里自行拼装。
     */
    private List<String> collectReportSourceUrls(List<ReportResponse.EvidenceInfo> evidenceInfos,
                                                 ReportDiagnosisInfo reportDiagnosis,
                                                 ReportResponse.DeliverySummaryInfo deliverySummary,
                                                 ReportResponse.EvidenceEntryPointInfo evidenceEntryPoint,
                                                 ReportResponse.AuditSummaryInfo auditSummary) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (ReportResponse.EvidenceInfo evidenceInfo : evidenceInfos == null ? List.<ReportResponse.EvidenceInfo>of() : evidenceInfos) {
            if (evidenceInfo != null) {
                appendSourceUrl(sourceUrls, evidenceInfo.getUrl());
            }
        }
        if (reportDiagnosis != null) {
            appendSourceUrls(sourceUrls, reportDiagnosis.getSourceUrls());
        }
        if (deliverySummary != null) {
            appendSourceUrls(sourceUrls, deliverySummary.getSourceUrls());
        }
        if (evidenceEntryPoint != null) {
            appendSourceUrl(sourceUrls, evidenceEntryPoint.getUrl());
            appendSourceUrls(sourceUrls, evidenceEntryPoint.getSourceUrls());
        }
        if (auditSummary != null) {
            appendSourceUrls(sourceUrls, auditSummary.getSourceUrls());
        }
        return new ArrayList<>(sourceUrls);
    }

    private void appendSourceUrls(LinkedHashSet<String> sourceUrls, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach(value -> appendSourceUrl(sourceUrls, value));
    }

    private void appendSourceUrl(LinkedHashSet<String> sourceUrls, String value) {
        if (value != null && !value.isBlank()) {
            sourceUrls.add(value.trim());
        }
    }

    /**
     * 交付摘要必须保留可追溯来源链接，
     * 因此这里统一合并 diagnosis、quality issue 和 revision directive 的 sourceUrls。
     */
    private List<String> collectDeliverySourceUrls(List<QualityIssue> issues,
                                                   RevisionPlan revisionPlan,
                                                   ReportDiagnosisInfo reportDiagnosis) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (reportDiagnosis != null && reportDiagnosis.getSourceUrls() != null) {
            sourceUrls.addAll(reportDiagnosis.getSourceUrls());
        }
        for (QualityIssue issue : issues == null ? List.<QualityIssue>of() : issues) {
            if (issue != null && issue.getSourceUrls() != null) {
                sourceUrls.addAll(issue.getSourceUrls());
            }
        }
        if (revisionPlan != null) {
            sourceUrls.addAll(revisionPlan.primarySourceUrls());
        }
        return sourceUrls.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * 检索审计摘要压缩成一句业务可读描述，
     * 让用户先知道这份报告的来源是否稳定，而不是先读完整 collector trace。
     */
    private String buildSearchAuditSummary(SearchAuditOverview searchAuditOverview) {
        if (searchAuditOverview == null) {
            return null;
        }
        return "采集节点 %d 个，已记录轨迹 %d 个，最终选中候选 %d 个".formatted(
                searchAuditOverview.getCollectorNodeCount() == null ? 0 : searchAuditOverview.getCollectorNodeCount(),
                searchAuditOverview.getTraceRecordedCount() == null ? 0 : searchAuditOverview.getTraceRecordedCount(),
                searchAuditOverview.getSelectedCandidateCount() == null ? 0 : searchAuditOverview.getSelectedCandidateCount()
        );
    }

    /**
     * Task RAG 审计主路径只保留一句最关键的上下文说明，
     * 详细检索上下文仍由原始 taskRagAudits 对象继续承载。
     */
    private String buildTaskRagAuditSummary(List<ReportResponse.TaskRagAuditInfo> taskRagAudits) {
        if (taskRagAudits == null || taskRagAudits.isEmpty()) {
            return null;
        }
        for (ReportResponse.TaskRagAuditInfo taskRagAudit : taskRagAudits) {
            if (taskRagAudit == null) {
                continue;
            }
            String context = defaultText(taskRagAudit.getTaskRagContext(), null);
            if (context != null) {
                return context;
            }
        }
        return null;
    }

    /**
     * 审计摘要默认保留 collector 选中的来源链接，
     * 让用户在主路径也能知道“这份检索说明主要指向哪些来源”。
     */
    private List<String> collectAuditSourceUrls(SearchAuditOverview searchAuditOverview) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (searchAuditOverview != null && searchAuditOverview.getCollectors() != null) {
            for (CollectorSearchAudit collector : searchAuditOverview.getCollectors()) {
                if (collector != null && collector.getSelectedUrls() != null) {
                    sourceUrls.addAll(collector.getSelectedUrls());
                }
            }
        }
        return sourceUrls.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    public byte[] exportMarkdown(Long taskId) {
        Report report = reportRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.REPORT_NOT_FOUND, "taskId=" + taskId));

        String content = report.getContent();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResultCode.REPORT_EXPORT_FAILED, "报告内容为空，无法导出");
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * HTML 导出复用详情页聚合数据，保证导出视图和页面视图尽量一致。
     */
    public byte[] exportHtml(Long taskId) {
        ReportResponse report = getReport(taskId);
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>%s</title>
                  <style>
                    body { font-family: "Segoe UI", Arial, sans-serif; margin: 0; background: #f5f7fb; color: #1f2937; }
                    .page { max-width: 1080px; margin: 0 auto; padding: 32px 24px 48px; }
                    .card { background: #fff; border-radius: 16px; padding: 24px; margin-bottom: 20px; box-shadow: 0 8px 32px rgba(15,23,42,0.08); }
                    h1, h2, h3 { margin-top: 0; }
                    .meta { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 16px; }
                    .badge { background: #e8eefc; color: #1d4ed8; padding: 6px 12px; border-radius: 999px; font-size: 14px; }
                    .badge.warn { background: #fff3cd; color: #9a6700; }
                    .badge.ok { background: #dcfce7; color: #166534; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
                    .metric { background: #f8fafc; border-radius: 12px; padding: 16px; }
                    .metric strong { display: block; font-size: 28px; margin-top: 8px; }
                    .report-body { white-space: pre-wrap; line-height: 1.7; background: #0f172a; color: #e2e8f0; padding: 20px; border-radius: 12px; overflow-x: auto; }
                    ul { padding-left: 20px; }
                    li { margin-bottom: 8px; }
                    table { width: 100%%; border-collapse: collapse; }
                    th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid #e5e7eb; vertical-align: top; }
                    .muted { color: #64748b; }
                  </style>
                </head>
                <body>
                  <div class="page">
                    <section class="card">
                      <h1>%s</h1>
                      <div class="meta">
                        <span class="badge %s">质量 %s</span>
                        <span class="badge">证据 %d</span>
                        <span class="badge">%s</span>
                      </div>
                    </section>

                    <section class="card">
                      <h2>报告摘要</h2>
                      <p>%s</p>
                    </section>

                    <section class="card">
                      <h2>执行摘要</h2>
                      <div class="grid">
                        <div class="metric">
                          <span class="muted">初审结果</span>
                          <strong>%s</strong>
                        </div>
                        <div class="metric">
                          <span class="muted">终审结果</span>
                          <strong>%s</strong>
                        </div>
                        <div class="metric">
                          <span class="muted">改写流程</span>
                          <strong>%s</strong>
                        </div>
                      </div>
                    </section>

                    <section class="card">
                      <h2>报告正文</h2>
                      <div class="report-body">%s</div>
                    </section>

                    <section class="card">
                      <h2>竞品溯源</h2>
                      %s
                    </section>

                    <section class="card">
                      <h2>证据来源</h2>
                      %s
                    </section>

                    <section class="card">
                      <h2>搜索审计摘要</h2>
                      %s
                    </section>
                  </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(report.getTitle()),
                escapeHtml(report.getTitle()),
                report.isQualityPassed() ? "ok" : "warn",
                report.getQualityScore() == null ? "N/A" : escapeHtml(report.getQualityScore() + "/100"),
                report.getEvidenceCount() == null ? 0 : report.getEvidenceCount(),
                report.isRewriteApplied() ? "已执行改写" : "无需改写",
                escapeHtml(report.getSummary() == null || report.getSummary().isBlank() ? "暂无摘要" : report.getSummary()),
                formatReviewStatus(report.getInitialReview()),
                formatReviewStatus(report.getFinalReview()),
                report.isRewriteApplied() ? "已完成改写闭环" : "单轮直出",
                escapeHtml(report.getContent()),
                buildKnowledgeHtml(report.getCompetitorKnowledges()),
                buildEvidenceHtml(report.getEvidences()),
                buildSearchAuditHtml(report.getSearchAuditOverview())
        );

        return html.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 汇总节点级 Task RAG 摘要，给报告详情页提供稳定的审计出口。
     * 这里只回流最终采用的文本摘要，不直接暴露底层召回分数和模型细节。
     */
    private List<ReportResponse.TaskRagAuditInfo> buildTaskRagAudits(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<ReportResponse.TaskRagAuditInfo> audits = new ArrayList<>();
        for (TaskNode node : nodes) {
            JsonNode output = readJson(node.getOutputData());
            String taskRagContext = output == null ? null : output.path("taskRagContext").asText(null);
            if (taskRagContext == null || taskRagContext.isBlank()) {
                continue;
            }
            audits.add(ReportResponse.TaskRagAuditInfo.builder()
                    .nodeName(node.getNodeName())
                    .agentType(node.getAgentType() == null ? null : node.getAgentType().name())
                    .taskRagContext(TaskRagContextSummaryFormatter.format(taskRagContext))
                    .build());
        }
        return audits;
    }

    /**
     * 导出时把 sourceUrls 和 evidenceCoverage 一并展开，方便离线文件继续追溯字段来源。
     */
    private String buildKnowledgeHtml(List<CompetitorKnowledgeInfo> knowledges) {
        if (knowledges == null || knowledges.isEmpty()) {
            return "<p class=\"muted\">暂无结构化竞品知识。</p>";
        }

        StringBuilder builder = new StringBuilder();
        for (CompetitorKnowledgeInfo knowledge : knowledges) {
            builder.append("<div style=\"margin-bottom:20px;\">")
                    .append("<h3>").append(escapeHtml(knowledge.getCompetitorName())).append("</h3>")
                    .append("<p>").append(escapeHtml(defaultText(knowledge.getSummary(), "暂无摘要"))).append("</p>")
                    .append("<p><strong>来源链接：</strong> ")
                    .append(escapeHtml(String.join(", ", knowledge.getSourceUrls() == null ? List.of() : knowledge.getSourceUrls())))
                    .append("</p>")
                    .append("<pre class=\"report-body\" style=\"background:#f8fafc;color:#1f2937;\">")
                    .append(escapeHtml(prettyJson(knowledge.getEvidenceCoverage())))
                    .append("</pre>")
                    .append("</div>");
        }
        return builder.toString();
    }

    /**
     * HTML 导出保留简化证据表格，优先满足“能回查来源”，不把整段正文全部塞进导出文件。
     */
    private String buildEvidenceHtml(List<ReportResponse.EvidenceInfo> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "<p class=\"muted\">暂无证据记录。</p>";
        }

        StringBuilder rows = new StringBuilder("<table><thead><tr><th>证据编号</th><th>竞品</th><th>标题</th><th>来源链接</th></tr></thead><tbody>");
        for (ReportResponse.EvidenceInfo evidence : evidences) {
            rows.append("<tr>")
                    .append("<td>").append(escapeHtml(evidence.getEvidenceId())).append("</td>")
                    .append("<td>").append(escapeHtml(evidence.getCompetitorName())).append("</td>")
                    .append("<td>").append(escapeHtml(evidence.getTitle())).append("</td>")
                    .append("<td>").append(escapeHtml(evidence.getUrl())).append("</td>")
                    .append("</tr>");
        }
        rows.append("</tbody></table>");
        return rows.toString();
    }

    /**
     * 导出文件同时保留搜索恢复、降级和候选统计摘要，便于离线交付时解释来源如何被选中。
     */
    private String buildSearchAuditHtml(SearchAuditOverview overview) {
        if (overview == null || overview.getCollectorNodeCount() == null || overview.getCollectorNodeCount() <= 0) {
            return "<p class=\"muted\">暂无搜索审计记录。</p>";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("<div class=\"grid\">")
                .append(metricCard("采集节点", String.valueOf(defaultNumber(overview.getCollectorNodeCount()))))
                .append(metricCard("已记录轨迹", String.valueOf(defaultNumber(overview.getTraceRecordedCount()))))
                .append(metricCard("检查点恢复", String.valueOf(defaultNumber(overview.getCheckpointRecoveredCount()))))
                .append(metricCard("降级次数", String.valueOf(defaultNumber(overview.getDegradedCount()))))
                .append(metricCard("回退链路", String.valueOf(defaultNumber(overview.getProviderFallbackCount()))))
                .append("</div>");

        builder.append("<table><thead><tr>")
                .append("<th>节点</th><th>节点状态</th><th>竞品 / 类型</th><th>补源方式</th><th>恢复状态</th><th>候选统计</th><th>异常说明</th>")
                .append("</tr></thead><tbody>");
        for (CollectorSearchAudit collector : overview.getCollectors() == null ? List.<CollectorSearchAudit>of() : overview.getCollectors()) {
            builder.append("<tr>")
                    .append("<td>").append(escapeHtml(defaultText(collector.getNodeName(), "-"))).append("</td>")
                    .append("<td>").append(escapeHtml(collector.getNodeStatus() == null ? "-" : collector.getNodeStatus().name())).append("</td>")
                    .append("<td>").append(escapeHtml(defaultText(collector.getCompetitorName(), "-")))
                    .append(" / ")
                    .append(escapeHtml(defaultText(collector.getSourceType(), "-"))).append("</td>")
                    .append("<td>").append(escapeHtml(defaultText(collector.getSupplementMethod(),
                            Boolean.TRUE.equals(collector.getTraceRecorded()) ? "NONE" : "未记录"))).append("</td>")
                    .append("<td>").append(escapeHtml(formatRecoveryLabel(collector))).append("</td>")
                    .append("<td>").append(escapeHtml(formatCollectorCounts(collector))).append("</td>")
                    .append("<td>").append(escapeHtml(formatCollectorIssue(collector))).append("</td>")
                    .append("</tr>");
        }
        builder.append("</tbody></table>");
        return builder.toString();
    }

    private String metricCard(String label, String value) {
        return """
                <div class="metric">
                  <span class="muted">%s</span>
                  <strong>%s</strong>
                </div>
                """.formatted(escapeHtml(label), escapeHtml(value));
    }

    private String formatReviewStatus(ReviewCheckpoint review) {
        if (review == null) {
            return "未触发";
        }
        if (review.getPassed() == null) {
            return switch (review.getNodeStatus()) {
                case READY -> "等待调度";
                case DISPATCHED -> "已派发";
                case WAITING_RETRY -> "等待重试";
                case WAITING_INTERVENTION -> "等待人工处理";
                case COMPENSATED -> "已补偿";
                case PENDING -> "待执行";
                case RUNNING -> "执行中";
                case PAUSED -> "已暂停";
                case SUCCESS -> "已完成";
                case FAILED -> "失败";
                case SKIPPED -> "已跳过";
            };
        }
        return review.getPassed() ? "已通过" : "需修订";
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of(json);
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    private List<QualityIssue> parseQualityIssues(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QualityIssue>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse quality issues failed", e);
            return List.of();
        }
    }

    private List<QualityDimension> parseQualityDimensions(String json) {
        if (json == null || json.isBlank() || "null".equals(json) || "[]".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QualityDimension>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse quality dimensions failed", e);
            return List.of();
        }
    }

    private List<QualityDiagnosis> parseQualityDiagnoses(String json) {
        if (json == null || json.isBlank() || "null".equals(json) || "[]".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QualityDiagnosis>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse quality diagnoses failed", e);
            return List.of();
        }
    }

    private List<RevisionDirective> parseRevisionDirectives(String json) {
        if (json == null || json.isBlank() || "null".equals(json) || "[]".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RevisionDirective>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse revision directives failed", e);
            return List.of();
        }
    }

    private TaskNode findNode(List<TaskNode> nodes, String nodeName) {
        return nodes.stream()
                .filter(node -> nodeName.equals(node.getNodeName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 报告接口优先回流 writer/analyzer 产出的结构化 sectionEvidenceBundles；
     * 如果链路上还没有写入 bundle，则退回到 coverage 概览生成最小可用的缺口视图，
     * 保证最终接口始终能返回章节级证据说明，而不是只返回纯文本报告。
     */
    private List<SectionEvidenceBundleInfo> buildSectionEvidenceBundles(List<ReportResponse.EvidenceInfo> evidenceInfos,
                                                                        List<TaskNode> nodes,
                                                                        EvidenceCoverageOverview coverageOverview) {
        List<SectionEvidenceBundle> rawBundles = extractSectionEvidenceBundles(nodes);
        if (rawBundles.isEmpty()) {
            rawBundles = buildFallbackSectionBundles(coverageOverview);
        }
        List<SectionEvidenceBundleInfo> result = new ArrayList<>();
        for (SectionEvidenceBundle rawBundle : rawBundles) {
            SectionEvidenceBundleInfo info = evidenceQueryService.toSectionEvidenceBundleInfo(evidenceInfos, rawBundle);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    private List<SectionEvidenceBundle> extractSectionEvidenceBundles(List<TaskNode> nodes) {
        List<SectionEvidenceBundle> bundles = readSectionBundlesFromNode(findNode(nodes, "rewrite_report"));
        if (!bundles.isEmpty()) {
            return bundles;
        }
        bundles = readSectionBundlesFromNode(findNode(nodes, "write_report"));
        if (!bundles.isEmpty()) {
            return bundles;
        }
        bundles = readSectionBundlesFromNode(findNode(nodes, "analyze_competitors"));
        if (!bundles.isEmpty()) {
            return bundles;
        }
        return List.of();
    }

    private List<SectionEvidenceBundle> readSectionBundlesFromNode(TaskNode node) {
        if (node == null) {
            return List.of();
        }
        JsonNode output = readJson(node.getOutputData());
        JsonNode bundlesNode = output == null ? null : output.path("sectionEvidenceBundles");
        if (bundlesNode == null || !bundlesNode.isArray()) {
            return List.of();
        }
        List<SectionEvidenceBundle> bundles = new ArrayList<>();
        for (JsonNode item : bundlesNode) {
            bundles.add(objectMapper.convertValue(item, SectionEvidenceBundle.class).normalized());
        }
        return bundles;
    }

    private List<SectionEvidenceBundle> buildFallbackSectionBundles(EvidenceCoverageOverview coverageOverview) {
        if (coverageOverview == null || coverageOverview.getSections() == null) {
            return List.of();
        }
        List<SectionEvidenceBundle> bundles = new ArrayList<>();
        for (SectionEvidenceCoverage coverage : coverageOverview.getSections()) {
            List<String> missingFields = coverage.getMissingFields() == null ? List.of() : coverage.getMissingFields();
            bundles.add(SectionEvidenceBundle.builder()
                    .stage("REPORT")
                    .sectionType("SECTION")
                    .sectionKey(coverage.getSectionKey())
                    .sectionTitle(coverage.getSectionTitle())
                    .missingFields(missingFields)
                    .issueFlags(missingFields.isEmpty() ? List.of() : List.of("SECTION_EVIDENCE_GAP"))
                    .gapSummary(missingFields.isEmpty() ? null : "缺少字段证据：" + String.join(", ", missingFields))
                    .build()
                    .normalized());
        }
        return bundles;
    }

    /**
     * 报告级搜索审计摘要来自采集节点输出中的 searchExecutionTrace。
     * 这样不引入新表，也能把恢复、降级和补源结果提升到正式交付视图。
     */
    private SearchAuditOverview buildSearchAuditOverview(List<TaskNode> nodes) {
        List<CollectorSearchAudit> collectors = new ArrayList<>();
        List<SearchAuditSummary> searchAuditSummaries = new ArrayList<>();
        int traceRecordedCount = 0;
        int checkpointRecoveredCount = 0;
        int degradedCount = 0;
        int providerFallbackCount = 0;
        int browserBlockedCount = 0;
        int plannedCandidateCount = 0;
        int verifiedCandidateCount = 0;
        int supplementedCandidateCount = 0;
        int selectedCandidateCount = 0;

        for (TaskNode node : nodes) {
            if (node.getAgentType() != AgentType.COLLECTOR) {
                continue;
            }
            JsonNode output = readJson(node.getOutputData());
            JsonNode config = readJson(node.getNodeConfig());
            JsonNode trace = output == null ? null : output.path("searchExecutionTrace");
            boolean traceRecorded = trace != null && !trace.isMissingNode() && !trace.isNull();

            CollectorSearchAudit collector = CollectorSearchAudit.builder()
                    .nodeName(node.getNodeName())
                    .nodeStatus(node.getStatus())
                    .competitorName(readText(output, "competitor", config, "competitorName"))
                    .sourceType(readText(output, "sourceType", config, "sourceType"))
                    .traceRecorded(traceRecorded)
                    .auditMessage(buildCollectorAuditMessage(node, traceRecorded))
                    .supplementMethod(traceRecorded ? trace.path("supplementMethod").asText(null) : null)
                    .resumedFromCheckpoint(traceRecorded ? readBoolean(trace, "resumedFromCheckpoint") : null)
                    .checkpointSource(traceRecorded ? trace.path("checkpointSource").asText(null) : null)
                    .degraded(traceRecorded ? readBoolean(trace, "degraded") : null)
                    .degradationReason(traceRecorded ? trace.path("degradationReason").asText(null) : null)
                    .providerFallbackUsed(traceRecorded ? readBoolean(trace, "providerFallbackUsed") : null)
                    .fallbackDecision(traceRecorded ? trace.path("fallbackDecision").asText(null) : null)
                    .browserTraceId(traceRecorded ? trace.path("browserTraceId").asText(null) : null)
                    .browserBlockedReason(traceRecorded ? trace.path("browserBlockedReason").asText(null) : null)
                    .browserBlockedCount(traceRecorded ? readInteger(trace, "browserBlockedCount") : null)
                    .recoveryCheckpoint(traceRecorded ? trace.path("recoveryCheckpoint").asText(null) : null)
                    .plannedCandidateCount(traceRecorded ? readInteger(trace, "plannedCandidateCount") : null)
                    .verifiedCandidateCount(traceRecorded ? readInteger(trace, "verifiedCandidateCount") : null)
                    .supplementedCandidateCount(traceRecorded ? readInteger(trace, "supplementedCandidateCount") : null)
                    .selectedCandidateCount(traceRecorded ? readInteger(trace, "selectedCandidateCount") : null)
                    .selectedUrls(traceRecorded ? readStringList(trace.path("selectedUrls")) : List.of())
                    .errorMessage(node.getErrorMessage())
                    .build();
            collectors.add(collector);

            if (traceRecorded) {
                SearchAuditSummary summary = SearchAuditSummary.builder()
                        .candidateCount(readInteger(trace, "plannedCandidateCount"))
                        .selectedCount(readInteger(trace, "selectedCandidateCount"))
                        .discardedCount(readInteger(trace, "discardedCandidateCount"))
                        .attemptedCount(readInteger(trace, "attemptedCandidateCount"))
                        .degraded(readBoolean(trace, "degraded"))
                        .degradationReason(trace.path("degradationReason").asText(null))
                        .fallbackDecision(trace.path("fallbackDecision").asText(null))
                        .recoveryCheckpoint(trace.path("recoveryCheckpoint").asText(null))
                        .sourceUrls(readStringList(trace.path("selectedUrls")))
                        .build();
                searchAuditSummaries.add(summary);
                traceRecordedCount++;
                if (Boolean.TRUE.equals(collector.getResumedFromCheckpoint())) {
                    checkpointRecoveredCount++;
                }
                if (Boolean.TRUE.equals(collector.getDegraded())) {
                    degradedCount++;
                }
                if (Boolean.TRUE.equals(collector.getProviderFallbackUsed())) {
                    providerFallbackCount++;
                }
                browserBlockedCount += defaultNumber(collector.getBrowserBlockedCount());
                plannedCandidateCount += defaultNumber(collector.getPlannedCandidateCount());
                verifiedCandidateCount += defaultNumber(collector.getVerifiedCandidateCount());
                supplementedCandidateCount += defaultNumber(collector.getSupplementedCandidateCount());
                selectedCandidateCount += defaultNumber(collector.getSelectedCandidateCount());
            }
        }

        return SearchAuditOverview.builder()
                .collectorNodeCount(collectors.size())
                .traceRecordedCount(traceRecordedCount)
                .checkpointRecoveredCount(checkpointRecoveredCount)
                .degradedCount(degradedCount)
                .providerFallbackCount(providerFallbackCount)
                .browserBlockedCount(browserBlockedCount)
                .plannedCandidateCount(plannedCandidateCount)
                .verifiedCandidateCount(verifiedCandidateCount)
                .supplementedCandidateCount(supplementedCandidateCount)
                .selectedCandidateCount(selectedCandidateCount)
                .searchAuditSummary(mergeSearchAuditSummaries(searchAuditSummaries))
                .collectors(collectors)
                .build();
    }

    /**
     * 报告主路径只聚合轻量搜索审计摘要。
     * 这样可追溯性、计数和降级结论都有统一入口，不再要求下游重新解析节点大 JSON。
     */
    private SearchAuditSummary mergeSearchAuditSummaries(List<SearchAuditSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return SearchAuditSummary.builder()
                    .candidateCount(0)
                    .selectedCount(0)
                    .discardedCount(0)
                    .attemptedCount(0)
                    .sourceUrls(List.of())
                    .build();
        }
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        int candidateCount = 0;
        int selectedCount = 0;
        int discardedCount = 0;
        int attemptedCount = 0;
        for (SearchAuditSummary summary : summaries) {
            if (summary == null) {
                continue;
            }
            candidateCount += summary.getCandidateCount() == null ? 0 : summary.getCandidateCount();
            selectedCount += summary.getSelectedCount() == null ? 0 : summary.getSelectedCount();
            discardedCount += summary.getDiscardedCount() == null ? 0 : summary.getDiscardedCount();
            attemptedCount += summary.getAttemptedCount() == null ? 0 : summary.getAttemptedCount();
            if (summary.getSourceUrls() != null) {
                sourceUrls.addAll(summary.getSourceUrls());
            }
        }
        return SearchAuditSummary.builder()
                .candidateCount(candidateCount)
                .selectedCount(selectedCount)
                .discardedCount(discardedCount)
                .attemptedCount(attemptedCount)
                .sourceUrls(new ArrayList<>(sourceUrls))
                .build();
    }

    /**
     * 把结构化知识里的字段级 evidenceCoverage 汇总成章节级概览，供报告页展示和 Reviewer 强约束复用。
     */
    private EvidenceCoverageOverview buildEvidenceCoverageOverview(List<CompetitorKnowledgeInfo> knowledges) {
        if (knowledges == null || knowledges.isEmpty()) {
            return EvidenceCoverageOverview.builder()
                    .totalFields(0)
                    .traceableFields(0)
                    .missingEvidenceFields(0)
                    .emptyFields(0)
                    .statusBreakdown(Map.of())
                    .sections(List.of())
                    .competitors(List.of())
                    .build();
        }

        Map<String, SectionCoverageAccumulator> sectionAccumulators = new LinkedHashMap<>();
        List<CompetitorEvidenceCoverage> competitorSummaries = new ArrayList<>();
        Map<String, Integer> overviewStatusBreakdown = new LinkedHashMap<>();
        int totalFields = 0;
        int traceableFields = 0;
        int missingEvidenceFields = 0;
        int emptyFields = 0;

        for (CompetitorKnowledgeInfo knowledge : knowledges) {
            int competitorTotal = 0;
            int competitorTraceable = 0;
            int competitorMissing = 0;
            int competitorEmpty = 0;
            Map<String, Integer> competitorStatusBreakdown = new LinkedHashMap<>();
            LinkedHashSet<String> missingSections = new LinkedHashSet<>();
            Map<String, Object> evidenceCoverage = knowledge.getEvidenceCoverage() == null ? Map.of() : knowledge.getEvidenceCoverage();

            for (CoverageFieldDefinition definition : COVERAGE_FIELD_DEFINITIONS) {
                CoverageState coverageState = resolveCoverageState(evidenceCoverage.get(definition.fieldKey()));
                CoverageStatus status = coverageState.coarseStatus();
                String rawStatus = coverageState.rawStatus();

                SectionCoverageAccumulator section = sectionAccumulators.computeIfAbsent(
                        definition.sectionKey(),
                        key -> new SectionCoverageAccumulator(definition.sectionKey(), definition.sectionTitle())
                );
                section.totalFields++;
                section.addRawStatus(rawStatus);
                competitorTotal++;
                totalFields++;
                overviewStatusBreakdown.merge(rawStatus, 1, Integer::sum);
                competitorStatusBreakdown.merge(rawStatus, 1, Integer::sum);

                switch (status) {
                    case TRACEABLE -> {
                        section.traceableFields++;
                        competitorTraceable++;
                        traceableFields++;
                    }
                    case MISSING_EVIDENCE -> {
                        section.missingEvidenceFields++;
                        section.missingFields.add(displayMissingField(definition.sectionTitle(), knowledge.getCompetitorName()));
                        competitorMissing++;
                        missingEvidenceFields++;
                        missingSections.add(definition.sectionTitle());
                    }
                    case EMPTY -> {
                        section.emptyFields++;
                        competitorEmpty++;
                        emptyFields++;
                    }
                }
            }

            competitorSummaries.add(CompetitorEvidenceCoverage.builder()
                    .competitorName(knowledge.getCompetitorName())
                    .totalFields(competitorTotal)
                    .traceableFields(competitorTraceable)
                    .missingEvidenceFields(competitorMissing)
                    .emptyFields(competitorEmpty)
                    .statusBreakdown(competitorStatusBreakdown)
                    .missingSections(new ArrayList<>(missingSections))
                    .build());
        }

        List<SectionEvidenceCoverage> sections = sectionAccumulators.values().stream()
                .map(item -> SectionEvidenceCoverage.builder()
                        .sectionKey(item.sectionKey)
                        .sectionTitle(item.sectionTitle)
                        .totalFields(item.totalFields)
                        .traceableFields(item.traceableFields)
                        .missingEvidenceFields(item.missingEvidenceFields)
                        .emptyFields(item.emptyFields)
                        .statusBreakdown(new LinkedHashMap<>(item.statusBreakdown))
                        .missingFields(new ArrayList<>(item.missingFields))
                        .build())
                .toList();

        return EvidenceCoverageOverview.builder()
                .totalFields(totalFields)
                .traceableFields(traceableFields)
                .missingEvidenceFields(missingEvidenceFields)
                .emptyFields(emptyFields)
                .statusBreakdown(overviewStatusBreakdown)
                .sections(sections)
                .competitors(competitorSummaries)
                .build();
    }

    /**
     * Reviewer 节点原始输出是 JSON 字符串，这里统一转换成前端直接可消费的结构。
     */
    private ReviewCheckpoint toReviewCheckpoint(TaskNode node) {
        if (node == null) {
            return null;
        }
        JsonNode output = readJson(node.getOutputData());
        return ReviewCheckpoint.builder()
                .nodeName(node.getNodeName())
                .nodeStatus(node.getStatus())
                .score(output == null || output.path("score").isMissingNode() ? null : output.path("score").asInt())
                .passed(output == null || output.path("passed").isMissingNode() ? null : output.path("passed").asBoolean())
                .requiresHumanIntervention(readBoolean(output, "requiresHumanIntervention"))
                .autoRewriteAllowed(readBoolean(output, "autoRewriteAllowed"))
                .summary(output == null ? null : output.path("summary").asText(null))
                .dimensions(output == null || !output.has("dimensions")
                        ? List.of()
                        : parseQualityDimensions(output.path("dimensions").toString()))
                .diagnoses(output == null || !output.has("diagnoses")
                        ? List.of()
                        : parseQualityDiagnoses(output.path("diagnoses").toString()))
                .issues(output == null ? List.of() : parseQualityIssues(output.path("issues").toString()))
                .nextActions(output == null || !output.has("nextActions")
                        ? List.of()
                        : parseNextActions(output.path("nextActions").toString()))
                .revisionDirectives(output == null || !output.has("revisionDirectives")
                        ? List.of()
                        : parseRevisionDirectives(output.path("revisionDirectives").toString()))
                .build();
    }

    private List<ReviewNextAction> parseNextActions(String json) {
        if (json == null || json.isBlank() || "null".equals(json) || "[]".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ReviewNextAction>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse review next actions failed", e);
            return List.of();
        }
    }

    /**
     * 报告主路径优先消费显式 revisionPlan；
     * 如果 Reviewer 只返回了 diagnoses / issues / revisionDirectives，
     * 这里兜底合成一个结构化修订计划，避免前端退回“暂无结构化修订计划”。
     */
    private RevisionPlan resolveRevisionPlan(TaskNode initialReviewNode,
                                             TaskNode finalReviewNode,
                                             ReviewCheckpoint initialReview,
                                             ReviewCheckpoint finalReview,
                                             ReportDiagnosisInfo reportDiagnosis) {
        RevisionPlan explicitPlan = parseExplicitRevisionPlan(finalReviewNode);
        if (explicitPlan == null) {
            explicitPlan = parseExplicitRevisionPlan(initialReviewNode);
        }
        if (explicitPlan != null) {
            return explicitPlan.normalized();
        }

        ReviewCheckpoint latestReview = finalReview != null ? finalReview : initialReview;
        if (!hasRevisionPlanSignals(latestReview, reportDiagnosis)) {
            return null;
        }

        return synthesizeRevisionPlan(latestReview, reportDiagnosis);
    }

    private RevisionPlan parseExplicitRevisionPlan(TaskNode node) {
        if (node == null) {
            return null;
        }
        JsonNode output = readJson(node.getOutputData());
        if (output == null || !output.has("revisionPlan")) {
            return null;
        }

        JsonNode revisionPlan = output.get("revisionPlan");
        try {
            if (revisionPlan.isTextual()) {
                return objectMapper.readValue(revisionPlan.asText(), RevisionPlan.class);
            }
            return objectMapper.treeToValue(revisionPlan, RevisionPlan.class);
        } catch (JsonProcessingException e) {
            log.warn("parse revision plan failed", e);
            return null;
        }
    }

    private boolean hasRevisionPlanSignals(ReviewCheckpoint latestReview, ReportDiagnosisInfo reportDiagnosis) {
        if (latestReview == null) {
            return false;
        }
        if (Boolean.FALSE.equals(latestReview.getPassed())) {
            return true;
        }
        if (latestReview.getIssues() != null && !latestReview.getIssues().isEmpty()) {
            return true;
        }
        if (latestReview.getDiagnoses() != null && !latestReview.getDiagnoses().isEmpty()) {
            return true;
        }
        return reportDiagnosis != null
                && reportDiagnosis.getRevisionDirectives() != null
                && !reportDiagnosis.getRevisionDirectives().isEmpty();
    }

    private RevisionPlan synthesizeRevisionPlan(ReviewCheckpoint latestReview, ReportDiagnosisInfo reportDiagnosis) {
        List<RevisionPlan.RevisionItem> items = buildRevisionItems(latestReview, reportDiagnosis);
        List<RevisionDirective> directives = buildRevisionDirectives(reportDiagnosis);
        List<String> rewriteGuidelines = buildRewriteGuidelines(items, directives, reportDiagnosis);
        boolean rewriteRequired = Boolean.FALSE.equals(latestReview.getPassed()) || !items.isEmpty() || !directives.isEmpty();

        return RevisionPlan.builder()
                .rewriteRequired(rewriteRequired)
                .summary(defaultText(latestReview.getSummary(), buildRevisionPlanSummary(items, directives)))
                .items(items)
                .directives(directives)
                .rewriteGuidelines(rewriteGuidelines)
                .build()
                .normalized();
    }

    /**
     * 先优先复用 Reviewer 的 issues；
     * 如果历史节点没有 issues，再从 diagnoses / 章节修复建议回推前端可直接展示的问题清单。
     */
    private List<RevisionPlan.RevisionItem> buildRevisionItems(ReviewCheckpoint latestReview, ReportDiagnosisInfo reportDiagnosis) {
        LinkedHashMap<String, RevisionPlan.RevisionItem> merged = new LinkedHashMap<>();
        if (latestReview.getIssues() != null) {
            for (QualityIssue issue : latestReview.getIssues()) {
                if (issue == null) {
                    continue;
                }
                String suggestion = defaultText(issue.getSuggestion(), "请补齐相关证据并复核结论。");
                String key = defaultText(issue.getSection(), "未分类") + "|" + defaultText(issue.getType(), "UNKNOWN") + "|" + suggestion;
                merged.putIfAbsent(key, new RevisionPlan.RevisionItem(
                        issue.getType(),
                        defaultText(issue.getSection(), "未分类"),
                        issue.getSeverity(),
                        suggestion
                ));
            }
        }
        if (!merged.isEmpty()) {
            return new ArrayList<>(merged.values());
        }

        if (latestReview.getDiagnoses() != null) {
            for (QualityDiagnosis diagnosis : latestReview.getDiagnoses()) {
                if (diagnosis == null) {
                    continue;
                }
                QualityDiagnosis normalized = diagnosis.normalized();
                String suggestion = defaultText(normalized.getRepairSuggestion(), defaultText(normalized.getDetail(), "请补齐相关证据并复核结论。"));
                String key = defaultText(normalized.getSection(), "未分类") + "|" + defaultText(normalized.getType(), "UNKNOWN") + "|" + suggestion;
                merged.putIfAbsent(key, new RevisionPlan.RevisionItem(
                        normalized.getType(),
                        defaultText(normalized.getSection(), "未分类"),
                        normalized.getSeverity(),
                        suggestion
                ));
            }
        }
        if (!merged.isEmpty()) {
            return new ArrayList<>(merged.values());
        }

        if (reportDiagnosis != null && reportDiagnosis.getSections() != null) {
            reportDiagnosis.getSections().forEach(section -> {
                if (section == null || section.getRepairSuggestions() == null) {
                    return;
                }
                section.getRepairSuggestions().forEach(suggestion -> {
                    String normalizedSuggestion = defaultText(suggestion, "请补齐相关证据并复核结论。");
                    String sectionName = defaultText(section.getSection(), "未分类");
                    String key = sectionName + "|EVIDENCE_GAP|" + normalizedSuggestion;
                    merged.putIfAbsent(key, new RevisionPlan.RevisionItem(
                            "EVIDENCE_GAP",
                            sectionName,
                            Boolean.TRUE.equals(section.getEvidenceInsufficient()) ? "ERROR" : "WARNING",
                            normalizedSuggestion
                    ));
                });
            });
        }
        return new ArrayList<>(merged.values());
    }

    private List<RevisionDirective> buildRevisionDirectives(ReportDiagnosisInfo reportDiagnosis) {
        if (reportDiagnosis == null || reportDiagnosis.getRevisionDirectives() == null) {
            return List.of();
        }
        List<RevisionDirective> directives = new ArrayList<>();
        for (RevisionDirectiveInfo directive : reportDiagnosis.getRevisionDirectives()) {
            if (directive == null) {
                continue;
            }
            RevisionDirectiveInfo normalized = directive.normalized();
            directives.add(RevisionDirective.builder()
                    .category(normalized.getCategory())
                    .actionType(normalized.getActionType())
                    .priority(normalized.getPriority())
                    .targetNode(normalized.getTargetNode())
                    .targetSection(normalized.getTargetSection())
                    .summary(normalized.getSummary())
                    .searchFeedback(normalized.getSearchFeedback())
                    .searchQueries(normalized.getSearchQueries())
                    .sourceUrls(normalized.getSourceUrls())
                    .expectedOutcome(normalized.getExpectedOutcome())
                    .build()
                    .normalized());
        }
        return directives;
    }

    private List<String> buildRewriteGuidelines(List<RevisionPlan.RevisionItem> items,
                                                List<RevisionDirective> directives,
                                                ReportDiagnosisInfo reportDiagnosis) {
        LinkedHashSet<String> guidelines = new LinkedHashSet<>();
        for (RevisionPlan.RevisionItem item : items) {
            if (item != null && item.getSuggestion() != null && !item.getSuggestion().isBlank()) {
                guidelines.add(item.getSuggestion().trim());
            }
        }
        for (RevisionDirective directive : directives) {
            if (directive == null) {
                continue;
            }
            if (directive.getExpectedOutcome() != null && !directive.getExpectedOutcome().isBlank()) {
                guidelines.add(directive.getExpectedOutcome().trim());
            }
        }
        if (reportDiagnosis != null && reportDiagnosis.getSections() != null) {
            reportDiagnosis.getSections().forEach(section -> {
                if (section == null || section.getRepairSuggestions() == null) {
                    return;
                }
                section.getRepairSuggestions().forEach(suggestion -> {
                    if (suggestion != null && !suggestion.isBlank()) {
                        guidelines.add(suggestion.trim());
                    }
                });
            });
        }
        return new ArrayList<>(guidelines);
    }

    private String buildRevisionPlanSummary(List<RevisionPlan.RevisionItem> items, List<RevisionDirective> directives) {
        if (!directives.isEmpty() && directives.get(0).getSummary() != null && !directives.get(0).getSummary().isBlank()) {
            return directives.get(0).getSummary();
        }
        if (!items.isEmpty()) {
            return "请先处理“" + defaultText(items.get(0).getSection(), "关键章节") + "”中的修订问题。";
        }
        return "当前报告存在待处理修订问题。";
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Boolean readBoolean(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        return node.path(field).asBoolean();
    }

    private Integer readInteger(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        return node.path(field).asInt();
    }

    private String prettyJson(Map<String, Object> value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int defaultNumber(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatRecoveryLabel(CollectorSearchAudit collector) {
        if (!Boolean.TRUE.equals(collector.getTraceRecorded())) {
            return "未记录轨迹";
        }
        if (Boolean.TRUE.equals(collector.getResumedFromCheckpoint())) {
            return "已恢复(" + defaultText(collector.getCheckpointSource(), "CHECKPOINT") + ")";
        }
        return "未恢复";
    }

    private String formatCollectorCounts(CollectorSearchAudit collector) {
        if (!Boolean.TRUE.equals(collector.getTraceRecorded())) {
            return "未记录结构化候选统计";
        }
        return "规划 " + defaultNumber(collector.getPlannedCandidateCount())
                + " / 验证 " + defaultNumber(collector.getVerifiedCandidateCount())
                + " / 补源 " + defaultNumber(collector.getSupplementedCandidateCount())
                + " / 选中 " + defaultNumber(collector.getSelectedCandidateCount());
    }

    private String formatCollectorIssue(CollectorSearchAudit collector) {
        if (!Boolean.TRUE.equals(collector.getTraceRecorded())) {
            if (collector.getAuditMessage() != null && !collector.getAuditMessage().isBlank()) {
                return collector.getAuditMessage();
            }
            if (collector.getErrorMessage() != null && !collector.getErrorMessage().isBlank()) {
                return "失败：" + collector.getErrorMessage();
            }
            return "采集节点未生成结构化搜索轨迹";
        }
        if (Boolean.TRUE.equals(collector.getDegraded()) && collector.getDegradationReason() != null) {
            return "降级：" + collector.getDegradationReason();
        }
        if (collector.getBrowserBlockedReason() != null && !collector.getBrowserBlockedReason().isBlank()) {
            return "阻断：" + collector.getBrowserBlockedReason();
        }
        if (collector.getErrorMessage() != null && !collector.getErrorMessage().isBlank()) {
            return "失败：" + collector.getErrorMessage();
        }
        if (collector.getFallbackDecision() != null && !collector.getFallbackDecision().isBlank()) {
            return "决策：" + collector.getFallbackDecision();
        }
        return "无";
    }

    private String readText(JsonNode primary, String primaryField, JsonNode fallback, String fallbackField) {
        String primaryValue = primary == null ? null : primary.path(primaryField).asText(null);
        if (primaryValue != null && !primaryValue.isBlank()) {
            return primaryValue;
        }
        String fallbackValue = fallback == null ? null : fallback.path(fallbackField).asText(null);
        return (fallbackValue == null || fallbackValue.isBlank()) ? null : fallbackValue;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private String buildCollectorAuditMessage(TaskNode node, boolean traceRecorded) {
        if (traceRecorded) {
            return "已记录结构化搜索轨迹";
        }
        if (node.getStatus() == TaskNodeStatus.FAILED) {
            return "采集节点执行失败，未生成结构化搜索轨迹";
        }
        if (node.getStatus() == TaskNodeStatus.RUNNING) {
            return "采集节点仍在执行，结构化搜索轨迹尚未写入";
        }
        if (node.getStatus() == TaskNodeStatus.PENDING) {
            return "采集节点尚未开始执行";
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "采集节点已暂停，结构化搜索轨迹等待恢复后继续写入";
        }
        if (node.getStatus() == TaskNodeStatus.SKIPPED) {
            return "采集节点已跳过，未生成结构化搜索轨迹";
        }
        return "采集节点未生成结构化搜索轨迹";
    }

    private CoverageState resolveCoverageState(Object rawCoverage) {
        if (!(rawCoverage instanceof Map<?, ?> coverageMap)) {
            return new CoverageState(CoverageStatus.EMPTY, "EMPTY");
        }
        Object status = coverageMap.get("status");
        String rawStatus;
        if (status == null) {
            Object hasValue = coverageMap.get("hasValue");
            rawStatus = Boolean.TRUE.equals(hasValue) ? "MISSING_EVIDENCE" : "EMPTY";
        } else {
            rawStatus = String.valueOf(status).trim().toUpperCase(Locale.ROOT);
        }
        CoverageStatus coarseStatus = switch (rawStatus) {
            case "TRACEABLE", "STRUCTURED_BLOCK_DIRECT" -> CoverageStatus.TRACEABLE;
            case "MISSING_EVIDENCE", "PARTIAL", "LLM_REFUSED", "EVIDENCE_NOT_COVERING" -> CoverageStatus.MISSING_EVIDENCE;
            default -> CoverageStatus.EMPTY;
        };
        return new CoverageState(coarseStatus, rawStatus);
    }

    private String displayMissingField(String sectionTitle, String competitorName) {
        return defaultText(competitorName, "未知竞品") + " / " + sectionTitle;
    }

    private record CoverageFieldDefinition(String fieldKey, String sectionKey, String sectionTitle) {
    }

    /**
     * 报告页同时需要“粗粒度可视化计数”和“细粒度原始状态分布”。
     * 这里把两者绑定在同一份解析结果里，避免 overview / section / competitor 三处各自重复猜状态。
     */
    private record CoverageState(CoverageStatus coarseStatus, String rawStatus) {
    }

    private enum CoverageStatus {
        TRACEABLE,
        MISSING_EVIDENCE,
        EMPTY
    }

    private static final class SectionCoverageAccumulator {
        private final String sectionKey;
        private final String sectionTitle;
        private int totalFields;
        private int traceableFields;
        private int missingEvidenceFields;
        private int emptyFields;
        private final Map<String, Integer> statusBreakdown = new LinkedHashMap<>();
        private final LinkedHashSet<String> missingFields = new LinkedHashSet<>();

        private SectionCoverageAccumulator(String sectionKey, String sectionTitle) {
            this.sectionKey = sectionKey;
            this.sectionTitle = sectionTitle;
        }

        private void addRawStatus(String rawStatus) {
            String normalizedStatus = rawStatus == null || rawStatus.isBlank() ? "EMPTY" : rawStatus;
            statusBreakdown.merge(normalizedStatus, 1, Integer::sum);
        }
    }
}
