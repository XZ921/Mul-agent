package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.workflow.coverage.StageOneFirstReportPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 阶段1采集分支的 quorum 判定策略。
 * <p>
 * 这里的职责不是简单忽略失败 collector，而是在所有 collector 进入终态后，
 * 统一判断现有证据是否已经满足“阶段1首报可交付”的最低契约，并把缺口审计信息交给下游节点消费。
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

        /*
         * 阶段1首报的 collector quorum 必须完全委托给统一契约，
         * 这样 pricing 是否阻断、sourceUrls 红线怎么算，才不会在 workflow 层再长出第二套规则。
         */
        boolean ready = StageOneFirstReportPolicy.isQuorumReady(
                new ArrayList<>(satisfiedFamilies),
                new ArrayList<>(sourceUrls));

        /*
         * pricing 在阶段1中属于增强信息。
         * 缺失时必须进入审计，供后续 Writer/Reviewer 解释，但不能再把 extractor 卡死。
         */
        if (!satisfiedFamilies.contains("PRICING")) {
            degraded = true;
            auditFlags.add("OPTIONAL_PRICING_NOT_READY");
        }

        /*
         * 当已经具备 OFFICIAL/DOCS 这类首报主来源，却仍未达到 quorum 时，
         * 失败原因应该稳定收敛到 sourceUrls 红线，而不是再次回退成 pricing 不足。
         */
        if (!ready && StageOneFirstReportPolicy.hasPrimarySourceFamily(satisfiedFamilies)) {
            degraded = true;
            auditFlags.add("SOURCE_URLS_REDLINE_NOT_READY");
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
        String normalized = normalizeFamily(nodeName);
        return StringUtils.hasText(normalized) ? normalized : "OFFICIAL";
    }

    private String normalizeFamily(String family) {
        if (!StringUtils.hasText(family)) {
            return "UNKNOWN";
        }
        /*
         * readiness 侧必须与 DagExecutor 共享同一套 source family 归一化语义，
         * 否则一边把 PRICING 识别成合法 family，另一边却把它当成未知值，
         * 会让 quorum 判定、缺口审计和任务视图再次出现“同一次采集结果多种解释”的接缝问题。
         */
        return StageOneFirstReportPolicy.normalizeSourceScope(family);
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
