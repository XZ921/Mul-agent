package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 目标覆盖门禁判定器。
 * 它只读取父决策指定的 gapKey 和上游 Extractor 输出，确定补采/重跑是否真正关闭目标缺口；
 * 不发起采集、不调用大模型，也不重新解释 Reviewer 诊断，保证 Writer 之前的硬门禁可审计、可复现。
 */
public class TargetCoverageGateEvaluator {

    private final ObjectMapper objectMapper;

    public TargetCoverageGateEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    public AgentResult evaluate(String gateConfig, List<String> upstreamOutputs) {
        JsonNode config = readJson(gateConfig);
        String expectedGapKey = resolveExpectedGapKey(config);
        if (!StringUtils.hasText(expectedGapKey)) {
            return waitingResult(config, null, List.of(), "target coverage gate 缺少 gapKey，无法自动判定目标覆盖收益");
        }

        LinkedHashSet<String> closedGapKeys = new LinkedHashSet<>();
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (String upstreamOutput : upstreamOutputs == null ? List.<String>of() : upstreamOutputs) {
            JsonNode output = readJson(upstreamOutput);
            collectSourceUrls(sourceUrls, output);
            collectClosedGapKeys(closedGapKeys, output, expectedGapKey);
        }

        String normalizedExpectedGapKey = normalizeGapKey(expectedGapKey);
        boolean closed = closedGapKeys.stream()
                .map(this::normalizeGapKey)
                .anyMatch(normalizedExpectedGapKey::equals);
        if (!closed) {
            return waitingResult(config, expectedGapKey, new ArrayList<>(sourceUrls),
                    "targetCoverageDelta=0，指定 gapKey 未关闭，进入 WAITING_INTERVENTION");
        }
        return successResult(config, expectedGapKey, new ArrayList<>(sourceUrls));
    }

    private AgentResult successResult(JsonNode config, String gapKey, List<String> sourceUrls) {
        Map<String, Object> payload = basePayload(config, gapKey, sourceUrls);
        payload.put("targetCoverageGate", "PASSED");
        payload.put("targetCoverageDelta", 1);
        payload.put("closedGapKeys", List.of(gapKey));
        payload.put("openGapKeys", List.of());
        return AgentResult.builder()
                .status(TaskNodeStatus.SUCCESS)
                .outputData(writeJson(payload))
                .outputSummary("目标覆盖门禁通过，gapKey=" + gapKey)
                .build();
    }

    private AgentResult waitingResult(JsonNode config, String gapKey, List<String> sourceUrls, String reason) {
        Map<String, Object> payload = basePayload(config, gapKey, sourceUrls);
        payload.put("targetCoverageGate", "FAILED");
        payload.put("targetCoverageDelta", 0);
        payload.put("closedGapKeys", List.of());
        payload.put("openGapKeys", StringUtils.hasText(gapKey) ? List.of(gapKey) : List.of());
        payload.put("reason", reason);
        return AgentResult.builder()
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .outputData(writeJson(payload))
                .outputSummary("目标覆盖门禁未通过，等待人工处理")
                .errorMessage(reason)
                .build();
    }

    private Map<String, Object> basePayload(JsonNode config, String gapKey, List<String> sourceUrls) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", text(config, "decisionId"));
        payload.put("mutationId", text(config, "mutationId"));
        payload.put("gapKey", gapKey);
        payload.put("competitor", text(config, "competitor"));
        payload.put("targetField", text(config, "targetField"));
        payload.put("requiredSourceType", text(config, "requiredSourceType"));
        payload.put("sourceUrls", sourceUrls == null ? List.of() : sourceUrls);
        return payload;
    }

    private void collectClosedGapKeys(LinkedHashSet<String> closedGapKeys, JsonNode output, String expectedGapKey) {
        if (output == null || output.isMissingNode() || output.isNull()) {
            return;
        }
        addTextArray(closedGapKeys, output.path("closedGapKeys"));
        addTextArray(closedGapKeys, output.path("targetCoverage").path("closedGapKeys"));
        addEvidenceCoverageGapKeys(closedGapKeys, output.path("evidenceCoverage"), expectedGapKey);
        if (matchesExpectedGap(output, expectedGapKey) && hasUsableSourceUrls(output)) {
            closedGapKeys.add(expectedGapKey);
        }
    }

    private void addEvidenceCoverageGapKeys(LinkedHashSet<String> closedGapKeys, JsonNode evidenceCoverage, String expectedGapKey) {
        if (evidenceCoverage == null || evidenceCoverage.isMissingNode() || evidenceCoverage.isNull()) {
            return;
        }
        addTextArray(closedGapKeys, evidenceCoverage.path("closedGapKeys"));
        if (evidenceCoverage.isObject()) {
            evidenceCoverage.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (isCoverageStatusClosed(value.path("status").asText(null))
                        || isCoverageStatusClosed(value.path("coverageStatus").asText(null))) {
                    closedGapKeys.add(entry.getKey());
                }
            });
        }
        if (matchesExpectedGap(evidenceCoverage, expectedGapKey) && hasUsableSourceUrls(evidenceCoverage)) {
            closedGapKeys.add(expectedGapKey);
        }
    }

    private boolean matchesExpectedGap(JsonNode node, String expectedGapKey) {
        String explicitGapKey = text(node, "gapKey");
        if (StringUtils.hasText(explicitGapKey)) {
            return normalizeGapKey(expectedGapKey).equals(normalizeGapKey(explicitGapKey));
        }
        String competitor = text(node, "competitor");
        String targetField = text(node, "targetField");
        String requiredSourceType = text(node, "requiredSourceType");
        if (!StringUtils.hasText(competitor)
                || !StringUtils.hasText(targetField)
                || !StringUtils.hasText(requiredSourceType)) {
            return false;
        }
        String derivedGapKey = normalizeGapPart(competitor) + "|"
                + normalizeGapPart(targetField) + "|"
                + normalizeGapPart(requiredSourceType);
        return normalizeGapKey(expectedGapKey).equals(derivedGapKey);
    }

    private boolean hasUsableSourceUrls(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return false;
        }
        String evidenceState = text(node, "evidenceState");
        if ("MISSING_SOURCE".equalsIgnoreCase(evidenceState)) {
            return false;
        }
        return !readStringList(node.path("sourceUrls")).isEmpty();
    }

    private void collectSourceUrls(LinkedHashSet<String> sourceUrls, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        sourceUrls.addAll(readStringList(node.path("sourceUrls")));
        sourceUrls.addAll(readStringList(node.path("targetCoverage").path("sourceUrls")));
        JsonNode evidenceCoverage = node.path("evidenceCoverage");
        if (evidenceCoverage.isObject()) {
            evidenceCoverage.fields().forEachRemaining(entry -> sourceUrls.addAll(readStringList(entry.getValue().path("sourceUrls"))));
        }
    }

    private String resolveExpectedGapKey(JsonNode config) {
        String gapKey = text(config, "gapKey");
        if (StringUtils.hasText(gapKey)) {
            return gapKey;
        }
        String competitor = text(config, "competitor");
        String targetField = text(config, "targetField");
        String requiredSourceType = text(config, "requiredSourceType");
        if (!StringUtils.hasText(competitor)
                || !StringUtils.hasText(targetField)
                || !StringUtils.hasText(requiredSourceType)) {
            return null;
        }
        return normalizeGapPart(competitor) + "|" + normalizeGapPart(targetField) + "|" + normalizeGapPart(requiredSourceType);
    }

    private void addTextArray(LinkedHashSet<String> target, JsonNode node) {
        target.addAll(readStringList(node));
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (StringUtils.hasText(item.asText(null))) {
                    values.add(item.asText().trim());
                }
            });
            return values;
        }
        if (StringUtils.hasText(node.asText(null))) {
            return List.of(node.asText().trim());
        }
        return List.of();
    }

    private boolean isCoverageStatusClosed(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("SUFFICIENT")
                || normalized.equals("PASSED")
                || normalized.equals("CLOSED")
                || normalized.equals("ACCEPT_FIELD_EVIDENCE");
    }

    private JsonNode readJson(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("serialize target coverage gate result failed", exception);
        }
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !StringUtils.hasText(fieldName)) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeGapKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String[] parts = value.trim().split("\\|");
        if (parts.length != 3) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
        return normalizeGapPart(parts[0]) + "|" + normalizeGapPart(parts[1]) + "|" + normalizeGapPart(parts[2]);
    }

    private String normalizeGapPart(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }
}
