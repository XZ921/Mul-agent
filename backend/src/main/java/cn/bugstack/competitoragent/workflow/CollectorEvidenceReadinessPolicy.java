package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 阶段1采集分支 quorum 策略。
 * <p>
 * 这里不是忽略失败 collector，而是在所有 collector 都进入终态后，
 * 判断已有证据是否足够交付首版抽取。失败、跳过、降级节点仍会进入
 * degraded / missingFamilies / auditFlags，供后续 Writer 和 Reviewer 写入降级说明。
 */
@Component
@RequiredArgsConstructor
public class CollectorEvidenceReadinessPolicy {

    private static final String WAITING_COLLECTOR_TERMINAL_STATUS = "WAITING_COLLECTOR_TERMINAL_STATUS";
    private static final String STAGE1_COLLECTOR_QUORUM_READY = "STAGE1_COLLECTOR_QUORUM_READY";
    private static final String STAGE1_COLLECTOR_QUORUM_NOT_READY = "STAGE1_COLLECTOR_QUORUM_NOT_READY";
    private static final Set<String> TERMINAL_FAMILIES = Set.of("OFFICIAL", "PRICING", "DOCS", "REVIEW");

    private final ObjectMapper objectMapper;

    public CollectorEvidenceReadiness evaluate(List<TaskNode> collectorNodes) {
        List<TaskNode> collectors = collectorNodes == null ? List.of() : collectorNodes.stream()
                .filter(node -> node != null && node.getAgentType() == AgentType.COLLECTOR)
                .toList();
        LinkedHashSet<String> satisfiedFamilies = new LinkedHashSet<>();
        LinkedHashSet<String> missingFamilies = new LinkedHashSet<>();
        LinkedHashSet<String> auditFlags = new LinkedHashSet<>();
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        boolean degraded = false;
        boolean waiting = false;

        for (TaskNode node : collectors) {
            CollectorNodeEvidence evidence = readCollectorNodeEvidence(node);
            if (!isTerminalStatus(node.getStatus())) {
                waiting = true;
                missingFamilies.add(evidence.family());
                continue;
            }
            sourceUrls.addAll(evidence.sourceUrls());
            if (node.getStatus() == TaskNodeStatus.SUCCESS_DEGRADED
                    || node.getStatus() == TaskNodeStatus.FAILED
                    || node.getStatus() == TaskNodeStatus.SKIPPED
                    || !evidence.degradationReasons().isEmpty()) {
                degraded = true;
                auditFlags.addAll(evidence.degradationReasons());
            }
            if (isReusableCollectorStatus(node.getStatus()) && evidence.readyForQuorum() && !evidence.sourceUrls().isEmpty()) {
                satisfiedFamilies.add(evidence.family());
            } else {
                missingFamilies.add(evidence.family());
            }
        }

        if (waiting) {
            return new CollectorEvidenceReadiness(
                    false,
                    degraded,
                    WAITING_COLLECTOR_TERMINAL_STATUS,
                    new ArrayList<>(satisfiedFamilies),
                    new ArrayList<>(missingFamilies),
                    new ArrayList<>(auditFlags),
                    new ArrayList<>(sourceUrls));
        }

        boolean hasOfficial = satisfiedFamilies.contains("OFFICIAL");
        boolean hasPricing = satisfiedFamilies.contains("PRICING");
        boolean hasDocsOrReview = satisfiedFamilies.contains("DOCS") || satisfiedFamilies.contains("REVIEW");
        boolean sourceQuotaReady = sourceUrls.size() >= 5 && distinctDomainCount(sourceUrls) >= 2;
        boolean ready = hasPricing && hasDocsOrReview && sourceQuotaReady && hasOfficial;

        if (!hasOfficial && hasPricing && hasDocsOrReview && sourceQuotaReady) {
            degraded = true;
            auditFlags.add("OFFICIAL_FAILED_DEGRADED_QUORUM");
            ready = true;
        }

        for (String family : TERMINAL_FAMILIES) {
            if (!satisfiedFamilies.contains(family)) {
                missingFamilies.add(family);
            }
        }

        return new CollectorEvidenceReadiness(
                ready,
                degraded || !missingFamilies.isEmpty(),
                ready ? STAGE1_COLLECTOR_QUORUM_READY : STAGE1_COLLECTOR_QUORUM_NOT_READY,
                new ArrayList<>(satisfiedFamilies),
                new ArrayList<>(missingFamilies),
                new ArrayList<>(auditFlags),
                new ArrayList<>(sourceUrls));
    }

    private CollectorNodeEvidence readCollectorNodeEvidence(TaskNode node) {
        JsonNode output = readJson(node == null ? null : node.getOutputData());
        String family = normalizeFamily(firstNonBlank(
                output == null ? null : output.path("sourceType").asText(null),
                inferFamilyFromNodeName(node == null ? null : node.getNodeName())));
        boolean readyForQuorum = output != null && output.path("readyForQuorum").asBoolean(false);
        List<String> sourceUrls = readStringList(output == null ? null : output.path("sourceUrls"));
        List<String> degradationReasons = readStringList(output == null ? null : output.path("degradationReasons"));
        return new CollectorNodeEvidence(family, readyForQuorum, sourceUrls, degradationReasons);
    }

    private boolean isTerminalStatus(TaskNodeStatus status) {
        return status == TaskNodeStatus.SUCCESS
                || status == TaskNodeStatus.SUCCESS_DEGRADED
                || status == TaskNodeStatus.FAILED
                || status == TaskNodeStatus.SKIPPED
                || status == TaskNodeStatus.COMPENSATED;
    }

    private boolean isReusableCollectorStatus(TaskNodeStatus status) {
        return status == TaskNodeStatus.SUCCESS
                || status == TaskNodeStatus.SUCCESS_DEGRADED
                || status == TaskNodeStatus.COMPENSATED;
    }

    private JsonNode readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<List<String>>() {
            }).stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private String inferFamilyFromNodeName(String nodeName) {
        if (!StringUtils.hasText(nodeName)) {
            return "UNKNOWN";
        }
        String normalized = nodeName.toUpperCase(Locale.ROOT);
        if (normalized.contains("DOC")) {
            return "DOCS";
        }
        if (normalized.contains("PRICE")) {
            return "PRICING";
        }
        if (normalized.contains("REVIEW")) {
            return "REVIEW";
        }
        return "OFFICIAL";
    }

    private String normalizeFamily(String family) {
        if (!StringUtils.hasText(family)) {
            return "UNKNOWN";
        }
        String normalized = family.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("DOC")) {
            return "DOCS";
        }
        if (normalized.contains("PRICE")) {
            return "PRICING";
        }
        if (normalized.contains("REVIEW")) {
            return "REVIEW";
        }
        if (normalized.contains("OFFICIAL") || normalized.contains("HOME") || normalized.contains("ROOT")) {
            return "OFFICIAL";
        }
        return normalized;
    }

    private int distinctDomainCount(Set<String> urls) {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (String url : urls) {
            try {
                String host = URI.create(url).getHost();
                if (StringUtils.hasText(host)) {
                    domains.add(host.toLowerCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
                // URL 解析失败时不计入域名 quorum，避免无效来源撑过红线。
            }
        }
        return domains.size();
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private record CollectorNodeEvidence(
            String family,
            boolean readyForQuorum,
            List<String> sourceUrls,
            List<String> degradationReasons) {
    }
}
