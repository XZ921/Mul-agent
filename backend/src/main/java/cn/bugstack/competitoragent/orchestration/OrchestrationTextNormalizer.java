package cn.bugstack.competitoragent.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 编排契约的包内文本归一化工具。
 * 它只处理空值、大小写、去重和证据状态边界，不承载任何业务决策。
 */
final class OrchestrationTextNormalizer {

    private OrchestrationTextNormalizer() {
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    static String upperOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized.toUpperCase(Locale.ROOT);
    }

    static List<String> normalizeDistinctList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String item = blankToNull(value);
                if (item != null) {
                    normalized.add(item);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    static Map<String, Object> normalizeObjectMap(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (values != null && !values.isEmpty()) {
            normalized.putAll(values);
        }
        return normalized;
    }

    static EvidenceState resolveEvidenceState(EvidenceState explicitState, List<String> normalizedSourceUrls) {
        if (explicitState != null) {
            return explicitState;
        }
        return normalizedSourceUrls == null || normalizedSourceUrls.isEmpty()
                ? EvidenceState.MISSING_SOURCE
                : EvidenceState.FULL_SOURCE;
    }

    static double clampConfidence(Double value, double defaultValue) {
        double resolved = value == null ? defaultValue : value;
        return Math.max(0.0d, Math.min(1.0d, resolved));
    }
}
