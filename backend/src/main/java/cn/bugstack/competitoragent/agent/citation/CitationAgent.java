package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.workflow.contract.CitationCheckResult;
import cn.bugstack.competitoragent.workflow.contract.CitationClaim;
import cn.bugstack.competitoragent.workflow.contract.CitationIssue;
import cn.bugstack.competitoragent.workflow.contract.CitationSourceTrustFinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Citation Agent。
 * 负责在 Reviewer 之前核查报告正文里的证据编号、引用覆盖和来源可信度。
 * 本实现只做可复现规则判断，不调用外部抓取，也不调用 LLM。
 */
@Slf4j
@Component
public class CitationAgent extends BaseAgent {

    private static final double DEFAULT_MIN_COVERAGE_RATE = 0.85d;

    private final EvidenceSourceRepository evidenceSourceRepository;
    private final ObjectMapper objectMapper;
    private final CitationClaimExtractor citationClaimExtractor;
    private final CitationSourceTrustPolicy citationSourceTrustPolicy;

    public CitationAgent(AgentExecutionLogRepository logRepository,
                         AgentContextAssembler agentContextAssembler,
                         EvidenceSourceRepository evidenceSourceRepository,
                         ObjectMapper objectMapper,
                         CitationClaimExtractor citationClaimExtractor,
                         CitationSourceTrustPolicy citationSourceTrustPolicy) {
        super(logRepository, agentContextAssembler);
        this.evidenceSourceRepository = evidenceSourceRepository;
        this.objectMapper = objectMapper;
        this.citationClaimExtractor = citationClaimExtractor;
        this.citationSourceTrustPolicy = citationSourceTrustPolicy;
    }

    @Override
    public AgentType getType() {
        return AgentType.CITATION;
    }

    @Override
    public String getName() {
        return "引用核查智能体";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        // Citation 节点只消费固定 DAG 指定的 sourceNode，默认回退到 write_report，
        // 这样首稿和重写稿都能复用同一套核查逻辑，而不会把节点名硬编码散落在业务分支里。
        String sourceNode = resolveSourceNode(context.getCurrentNodeConfig());
        double minCoverageRate = resolveMinCoverageRate(context.getCurrentNodeConfig());

        String rawWriterOutput = context.getSharedOutput(sourceNode);
        if (rawWriterOutput == null || rawWriterOutput.isBlank()) {
            return AgentResult.failed("缺少待核查报告输出：" + sourceNode);
        }

        WriterOutputSnapshot writerOutputSnapshot = readWriterOutput(rawWriterOutput);
        List<EvidenceSource> taskEvidences = evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        Map<String, EvidenceSource> evidenceById = buildEvidenceById(taskEvidences);

        List<CitationClaim> resolvedClaims = new ArrayList<>();
        List<CitationIssue> citationIssues = new ArrayList<>();
        List<CitationSourceTrustFinding> trustFindings = evaluateTrustFindings(taskEvidences);

        int sensitiveClaims = 0;
        int supportedSensitiveClaims = 0;
        int issueIndex = 1;

        for (CitationClaim extractedClaim : citationClaimExtractor.extract(writerOutputSnapshot.reportContent())) {
            List<String> resolvedSourceUrls = new ArrayList<>();
            boolean allEvidenceKnown = !extractedClaim.getEvidenceIds().isEmpty();

            // 证据编号查找必须只基于当前任务内已落库的 EvidenceSource，
            // 一旦编号不存在，就按 UNKNOWN_EVIDENCE_ID 明确落成 CitationIssue，而不是猜测引用可能来自别处。
            for (String evidenceId : extractedClaim.getEvidenceIds()) {
                EvidenceSource evidenceSource = evidenceById.get(evidenceId);
                if (evidenceSource == null) {
                    allEvidenceKnown = false;
                    citationIssues.add(buildUnknownEvidenceIssue(issueIndex++, extractedClaim, evidenceId));
                    continue;
                }
                if (hasText(evidenceSource.getUrl())) {
                    resolvedSourceUrls.add(evidenceSource.getUrl().trim());
                }
            }

            CitationClaim resolvedClaim = extractedClaim.toBuilder()
                    .sourceUrls(distinctTexts(resolvedSourceUrls))
                    .issueFlags(resolveClaimIssueFlags(extractedClaim, resolvedSourceUrls))
                    .build()
                    .normalized();
            resolvedClaims.add(resolvedClaim);

            if (resolvedClaim.isTraceabilitySensitive()) {
                sensitiveClaims++;
            }

            // 敏感判断句没有证据编号且也没有显式降级时，必须直接落成 MISSING_CITATION。
            // 这里不能因为 Writer 传入了 fallback sourceUrls 就放过，否则“段落有来源但句子没引用”会被静默混过去。
            if (resolvedClaim.isTraceabilitySensitive()
                    && resolvedClaim.getEvidenceIds().isEmpty()
                    && !resolvedClaim.isExplicitlyDowngraded()) {
                citationIssues.add(buildMissingCitationIssue(issueIndex++, resolvedClaim));
                continue;
            }

            if (resolvedClaim.isTraceabilitySensitive()
                    && !resolvedClaim.getEvidenceIds().isEmpty()
                    && allEvidenceKnown) {
                supportedSensitiveClaims++;
            }
        }

        // 来源可信度规则只依赖 EvidenceSource 元数据本身，
        // 一旦命中 LOW_TRUST，就把风险显式转成 CitationIssue，交给后续 Orchestrator 决定是阻断还是重写。
        for (CitationSourceTrustFinding trustFinding : trustFindings) {
            if ("LOW_TRUST".equals(trustFinding.getTrustTier())) {
                citationIssues.add(buildLowTrustIssue(issueIndex++, trustFinding));
            }
        }

        double citationCoverageRate = sensitiveClaims == 0
                ? 1.0d
                : ((double) supportedSensitiveClaims) / sensitiveClaims;

        List<String> resultSourceUrls = collectResultSourceUrls(writerOutputSnapshot, taskEvidences, resolvedClaims, trustFindings);
        String citationRiskSeverity = resolveRiskSeverity(citationIssues, citationCoverageRate, minCoverageRate);
        String citationEvidenceState = resolveEvidenceState(citationIssues, citationCoverageRate, minCoverageRate, resultSourceUrls);

        CitationCheckResult result = CitationCheckResult.builder()
                .checkedSourceNode(sourceNode)
                .citationEvidenceState(citationEvidenceState)
                .citationRiskSeverity(citationRiskSeverity)
                .citationCoverageRate(citationCoverageRate)
                .claims(resolvedClaims)
                .citationIssues(citationIssues)
                .sourceCredibilityFindings(trustFindings)
                .sourceUrls(resultSourceUrls)
                .evidenceState(citationEvidenceState)
                .issueFlags(collectIssueFlags(citationIssues, trustFindings))
                .build()
                .normalized();

        try {
            String outputJson = objectMapper.writeValueAsString(result);
            return AgentResult.success(
                    outputJson,
                    "引用核查完成，风险等级=" + result.getCitationRiskSeverity() + "，覆盖率=" + result.getCitationCoverageRate()
            );
        } catch (Exception e) {
            log.error("citation result serialize failed, taskId={}, nodeName={}",
                    context.getTaskId(), context.getCurrentNodeName(), e);
            return AgentResult.failed("引用核查结果序列化失败：" + e.getMessage());
        }
    }

    private String resolveSourceNode(String currentNodeConfig) {
        JsonNode config = parseJsonNode(currentNodeConfig);
        if (config == null) {
            return "write_report";
        }
        String sourceNode = config.path("sourceNode").asText(null);
        return hasText(sourceNode) ? sourceNode.trim() : "write_report";
    }

    private double resolveMinCoverageRate(String currentNodeConfig) {
        JsonNode config = parseJsonNode(currentNodeConfig);
        if (config == null || !config.has("minCoverageRate")) {
            return DEFAULT_MIN_COVERAGE_RATE;
        }
        return config.path("minCoverageRate").asDouble(DEFAULT_MIN_COVERAGE_RATE);
    }

    private WriterOutputSnapshot readWriterOutput(String rawWriterOutput) {
        JsonNode outputNode = parseJsonNode(rawWriterOutput);
        if (outputNode == null || !outputNode.isObject()) {
            return new WriterOutputSnapshot(rawWriterOutput, List.of(), null);
        }

        String reportContent = outputNode.path("content").asText(rawWriterOutput);
        LinkedHashSet<String> fallbackSourceUrls = new LinkedHashSet<>(readStringList(outputNode.path("sourceUrls")));
        for (JsonNode gapNode : iterable(outputNode.path("sectionCitationGaps"))) {
            fallbackSourceUrls.addAll(readStringList(gapNode.path("sourceUrls")));
        }
        String writerEvidenceState = outputNode.path("writerEvidenceState").asText(null);
        return new WriterOutputSnapshot(reportContent, new ArrayList<>(fallbackSourceUrls), writerEvidenceState);
    }

    private Map<String, EvidenceSource> buildEvidenceById(List<EvidenceSource> taskEvidences) {
        Map<String, EvidenceSource> evidenceById = new LinkedHashMap<>();
        for (EvidenceSource taskEvidence : taskEvidences == null ? List.<EvidenceSource>of() : taskEvidences) {
            if (taskEvidence == null || !hasText(taskEvidence.getEvidenceId())) {
                continue;
            }
            evidenceById.put(taskEvidence.getEvidenceId().trim(), taskEvidence);
        }
        return evidenceById;
    }

    private List<CitationSourceTrustFinding> evaluateTrustFindings(List<EvidenceSource> taskEvidences) {
        List<CitationSourceTrustFinding> findings = new ArrayList<>();
        for (EvidenceSource taskEvidence : taskEvidences == null ? List.<EvidenceSource>of() : taskEvidences) {
            if (taskEvidence != null) {
                findings.add(citationSourceTrustPolicy.evaluate(taskEvidence));
            }
        }
        return List.copyOf(findings);
    }

    private CitationIssue buildMissingCitationIssue(int issueIndex, CitationClaim claim) {
        return CitationIssue.builder()
                .issueId("ci-" + issueIndex)
                .issueType("MISSING_CITATION")
                .severity("ERROR")
                .targetSection(defaultIfBlank(claim.getSectionKey(), "report"))
                .claimId(claim.getClaimId())
                .summary(defaultIfBlank(claim.getSectionTitle(), "报告章节") + " 中存在敏感判断句，但未附带有效引用。")
                .sourceUrls(List.of())
                .evidenceState("MISSING_SOURCE")
                .suggestedQueries(List.of(defaultIfBlank(claim.getSectionKey(), "report") + " official evidence"))
                .issueFlags(List.of("MISSING_SOURCE_URL"))
                .build()
                .normalized();
    }

    private CitationIssue buildUnknownEvidenceIssue(int issueIndex, CitationClaim claim, String evidenceId) {
        return CitationIssue.builder()
                .issueId("ci-" + issueIndex)
                .issueType("UNKNOWN_EVIDENCE_ID")
                .severity("ERROR")
                .targetSection(defaultIfBlank(claim.getSectionKey(), "report"))
                .claimId(claim.getClaimId())
                .evidenceId(evidenceId)
                .summary("报告引用了未知证据编号 " + evidenceId + "，当前任务内无法回指来源。")
                .sourceUrls(List.of())
                .evidenceState("MISSING_SOURCE")
                .suggestedQueries(List.of(evidenceId + " official evidence"))
                .issueFlags(List.of("UNKNOWN_EVIDENCE_ID", "MISSING_SOURCE_URL"))
                .build()
                .normalized();
    }

    private CitationIssue buildLowTrustIssue(int issueIndex, CitationSourceTrustFinding trustFinding) {
        List<String> sourceUrls = trustFinding.getSourceUrls() == null ? List.of() : trustFinding.getSourceUrls();
        return CitationIssue.builder()
                .issueId("ci-" + issueIndex)
                .issueType("LOW_SOURCE_TRUST")
                .severity("HIGH")
                .targetSection("report")
                .evidenceId(trustFinding.getEvidenceId())
                .summary("证据 " + defaultIfBlank(trustFinding.getEvidenceId(), "unknown")
                        + " 的来源可信度较低，需要补充更稳定的官方来源。")
                .sourceUrls(sourceUrls)
                .evidenceState(sourceUrls.isEmpty() ? "MISSING_SOURCE" : "PARTIAL_SOURCE")
                .suggestedQueries(List.of(defaultIfBlank(trustFinding.getSourceDomain(), "official") + " official docs"))
                .issueFlags(mergeFlags(List.of("LOW_SOURCE_TRUST"), trustFinding.getIssueFlags()))
                .build()
                .normalized();
    }

    private List<String> resolveClaimIssueFlags(CitationClaim claim, List<String> resolvedSourceUrls) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (!hasAnyText(resolvedSourceUrls)) {
            issueFlags.add("MISSING_SOURCE_URL");
        }
        if (claim.isTraceabilitySensitive() && claim.getEvidenceIds().isEmpty()) {
            issueFlags.add("UNKNOWN_EVIDENCE_ID");
        }
        return new ArrayList<>(issueFlags);
    }

    private String resolveRiskSeverity(List<CitationIssue> citationIssues,
                                       double citationCoverageRate,
                                       double minCoverageRate) {
        if (citationIssues.stream().anyMatch(issue ->
                "MISSING_CITATION".equals(issue.getIssueType())
                        || "UNKNOWN_EVIDENCE_ID".equals(issue.getIssueType()))) {
            return "ERROR";
        }
        if (citationIssues.stream().anyMatch(issue -> "LOW_SOURCE_TRUST".equals(issue.getIssueType()))
                || citationCoverageRate < minCoverageRate) {
            return "HIGH";
        }
        return "NONE";
    }

    private String resolveEvidenceState(List<CitationIssue> citationIssues,
                                        double citationCoverageRate,
                                        double minCoverageRate,
                                        List<String> resultSourceUrls) {
        if (citationIssues.stream().anyMatch(issue -> "MISSING_SOURCE".equals(issue.getEvidenceState()))) {
            return "MISSING_SOURCE";
        }
        if (citationIssues.stream().anyMatch(issue -> "PARTIAL_SOURCE".equals(issue.getEvidenceState()))
                || citationCoverageRate < minCoverageRate) {
            return "PARTIAL_SOURCE";
        }
        return resultSourceUrls.isEmpty() ? "MISSING_SOURCE" : "FULL_SOURCE";
    }

    private List<String> collectResultSourceUrls(WriterOutputSnapshot writerOutputSnapshot,
                                                 List<EvidenceSource> taskEvidences,
                                                 List<CitationClaim> resolvedClaims,
                                                 List<CitationSourceTrustFinding> trustFindings) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        sourceUrls.addAll(writerOutputSnapshot.fallbackSourceUrls());
        for (EvidenceSource taskEvidence : taskEvidences == null ? List.<EvidenceSource>of() : taskEvidences) {
            if (taskEvidence != null && hasText(taskEvidence.getUrl())) {
                sourceUrls.add(taskEvidence.getUrl().trim());
            }
        }
        for (CitationClaim resolvedClaim : resolvedClaims) {
            sourceUrls.addAll(resolvedClaim.getSourceUrls());
        }
        for (CitationSourceTrustFinding trustFinding : trustFindings) {
            sourceUrls.addAll(trustFinding.getSourceUrls());
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> collectIssueFlags(List<CitationIssue> citationIssues,
                                           List<CitationSourceTrustFinding> trustFindings) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (CitationIssue citationIssue : citationIssues) {
            issueFlags.addAll(citationIssue.getIssueFlags());
        }
        for (CitationSourceTrustFinding trustFinding : trustFindings) {
            issueFlags.addAll(trustFinding.getIssueFlags());
        }
        return new ArrayList<>(issueFlags);
    }

    private JsonNode parseJsonNode(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            log.debug("citation agent failed to parse json, fallback to raw text");
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        }
        return distinctTexts(values);
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node::elements : List.<JsonNode>of();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasAnyText(List<String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private String defaultIfBlank(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private List<String> distinctTexts(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> mergeFlags(List<String> first, List<String> second) {
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        flags.addAll(first == null ? List.of() : first);
        flags.addAll(second == null ? List.of() : second);
        return new ArrayList<>(flags);
    }

    private record WriterOutputSnapshot(String reportContent,
                                        List<String> fallbackSourceUrls,
                                        String writerEvidenceState) {
    }
}
