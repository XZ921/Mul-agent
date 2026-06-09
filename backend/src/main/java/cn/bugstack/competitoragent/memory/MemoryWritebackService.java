package cn.bugstack.competitoragent.memory;

import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.MemoryReuseRecord;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 负责把满足治理条件的结论正式写回记忆层。
 * <p>
 * 这里显式依赖 {@link MemoryReusePolicy}，确保写回层级、版本来源、
 * 失效规则和复用说明都通过统一策略生成，而不是散落在不同调用方里。
 */
@Service
public class MemoryWritebackService {

    private final MemorySnapshotRepository memorySnapshotRepository;
    private final CompetitorKnowledgeRepository competitorKnowledgeRepository;
    private final MemoryReuseRecordRepository memoryReuseRecordRepository;
    private final MemoryReusePolicy memoryReusePolicy;

    @Autowired
    public MemoryWritebackService(MemorySnapshotRepository memorySnapshotRepository,
                                  CompetitorKnowledgeRepository competitorKnowledgeRepository,
                                  MemoryReuseRecordRepository memoryReuseRecordRepository,
                                  MemoryReusePolicy memoryReusePolicy) {
        this.memorySnapshotRepository = memorySnapshotRepository;
        this.competitorKnowledgeRepository = competitorKnowledgeRepository;
        this.memoryReuseRecordRepository = memoryReuseRecordRepository;
        this.memoryReusePolicy = memoryReusePolicy;
    }

    /**
     * 为了保持当前阶段测试和最小接入成本，提供一个轻量构造器，
     * 由服务内部创建默认策略对象。
     */
    public MemoryWritebackService(MemorySnapshotRepository memorySnapshotRepository,
                                  CompetitorKnowledgeRepository competitorKnowledgeRepository,
                                  MemoryReuseRecordRepository memoryReuseRecordRepository) {
        this(memorySnapshotRepository,
                competitorKnowledgeRepository,
                memoryReuseRecordRepository,
                new MemoryReusePolicy());
    }

    /**
     * 按正式策略执行一次记忆写回。
     */
    public WritebackResult writeback(WritebackRequest request) {
        MemoryReusePolicy.PolicyDecision decision = memoryReusePolicy.decide(request);
        if (!decision.isWritebackEnabled()) {
            return WritebackResult.builder()
                    .policyDecision(decision)
                    .build();
        }

        MemorySnapshot savedSnapshot = memorySnapshotRepository.save(buildSnapshot(request, decision));
        CompetitorKnowledge savedKnowledge = null;
        if (decision.isPromoteToDomainKnowledge()) {
            savedKnowledge = competitorKnowledgeRepository.save(buildDomainKnowledge(request, decision));
        }
        MemoryReuseRecord savedRecord = memoryReuseRecordRepository.save(buildAuditRecord(request, decision, savedSnapshot));

        return WritebackResult.builder()
                .policyDecision(decision)
                .memorySnapshot(savedSnapshot)
                .competitorKnowledge(savedKnowledge)
                .memoryReuseRecord(savedRecord)
                .build();
    }

    /**
     * 短期或任务级写回统一先沉淀成 MemorySnapshot，
     * 这样后续复用、回放和失效判断都可以沿用同一个载体。
     */
    private MemorySnapshot buildSnapshot(WritebackRequest request, MemoryReusePolicy.PolicyDecision decision) {
        return MemorySnapshot.builder()
                .taskId(request.getTaskId())
                .planVersionId(request.getPlanVersionId())
                .branchKey(request.getBranchKey())
                .nodeName(firstNonBlank(request.getNodeName(), "UNKNOWN"))
                .snapshotType(firstNonBlank(request.getSnapshotType(), "TASK_RAG"))
                .memoryLayer(decision.getTargetMemoryLayer())
                .queryText(request.getQueryText())
                .summary(request.getSummary())
                .gapSummary(request.getGapSummary())
                .sourceUrls(normalizeSourceUrls(request.getSourceUrls()))
                .issueFlags(normalizeIssueFlags(request.getIssueFlags()))
                .versionSource(decision.getVersionSource())
                .invalidationScope(decision.getInvalidationScope())
                .invalidationReason(decision.getInvalidationReason())
                .contextPayload(request.getContextPayload())
                .build();
    }

    /**
     * 只有明确属于领域记忆的高质量结论才允许提升到 CompetitorKnowledge，
     * 避免把任务现场结论直接污染跨任务知识层。
     */
    private CompetitorKnowledge buildDomainKnowledge(WritebackRequest request, MemoryReusePolicy.PolicyDecision decision) {
        return CompetitorKnowledge.builder()
                .taskId(request.getTaskId())
                .competitorName(firstNonBlank(request.getCompetitorName(), "UNKNOWN"))
                .officialUrl(request.getOfficialUrl())
                .summary(request.getSummary())
                .memoryLayer("DOMAIN")
                .positioning(request.getPositioning())
                .targetUsers(firstNonBlank(request.getTargetUsers(), "[]"))
                .coreFeatures(firstNonBlank(request.getCoreFeatures(), "[]"))
                .pricing(firstNonBlank(request.getPricing(), "{}"))
                .strengths(firstNonBlank(request.getStrengths(), "[]"))
                .weaknesses(firstNonBlank(request.getWeaknesses(), "[]"))
                .sources(firstNonBlank(request.getSources(), "[]"))
                .sourceUrls(toJsonArray(normalizeSourceUrls(request.getSourceUrls())))
                .evidenceCoverage(firstNonBlank(request.getEvidenceCoverage(), "{}"))
                .versionSource(decision.getVersionSource())
                .invalidationScope(decision.getInvalidationScope())
                .invalidationReason(decision.getInvalidationReason())
                .extractedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 审计记录需要显式带上复用说明、版本来源和失效边界，
     * 这样后续就能解释“为什么这条记忆当时允许复用，以及什么时候应该停用”。
     */
    private MemoryReuseRecord buildAuditRecord(WritebackRequest request,
                                               MemoryReusePolicy.PolicyDecision decision,
                                               MemorySnapshot savedSnapshot) {
        return MemoryReuseRecord.builder()
                .taskId(request.getTaskId())
                .consumerNodeName(firstNonBlank(request.getNodeName(), "UNKNOWN"))
                .sourceMemoryLayer(decision.getTargetMemoryLayer())
                .sourceObjectType("WRITEBACK_AUDIT")
                .sourceRecordId(savedSnapshot == null ? 0L : savedSnapshot.getId())
                .sourceTaskId(request.getTaskId())
                .sourceSummary(request.getSummary())
                .sourceUrls(normalizeSourceUrls(request.getSourceUrls()))
                .reuseReason(decision.getReuseReason())
                .versionSource(decision.getVersionSource())
                .invalidationScope(decision.getInvalidationScope())
                .build();
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (sourceUrls != null) {
            for (String sourceUrl : sourceUrls) {
                if (StringUtils.hasText(sourceUrl)) {
                    normalized.add(sourceUrl.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> normalizeIssueFlags(List<String> issueFlags) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (issueFlags != null) {
            for (String issueFlag : issueFlags) {
                if (StringUtils.hasText(issueFlag)) {
                    normalized.add(issueFlag.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 当前实体仍以 JSON 文本保存 sourceUrls，
     * 这里做最小序列化，避免为了 5.4.c 再引入额外对象转换器。
     */
    private String toJsonArray(List<String> sourceUrls) {
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < sourceUrls.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(sourceUrls.get(index).replace("\"", "\\\"")).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WritebackRequest {
        private Long taskId;
        private Long planVersionId;
        private String branchKey;
        private String nodeName;
        private String competitorName;
        private String officialUrl;
        private String snapshotType;
        private String queryText;
        private String summary;
        private String gapSummary;
        private List<String> sourceUrls;
        private List<String> issueFlags;
        private String contextPayload;
        private String writebackCategory;
        private String qualitySignal;
        private String reuseReason;
        private String positioning;
        private String targetUsers;
        private String coreFeatures;
        private String pricing;
        private String strengths;
        private String weaknesses;
        private String sources;
        private String evidenceCoverage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WritebackResult {
        private MemoryReusePolicy.PolicyDecision policyDecision;
        private MemorySnapshot memorySnapshot;
        private CompetitorKnowledge competitorKnowledge;
        private MemoryReuseRecord memoryReuseRecord;
    }
}
