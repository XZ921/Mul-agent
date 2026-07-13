package cn.bugstack.competitoragent.orchestration;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Orchestrator LLM 输入与响应解析共用的来源证据目录。
 * 同一张有序表同时承担 Prompt allowedSourceUrls、Parser allowlist 和 evidenceState 反查，
 * 避免输入声明与输出校验使用两套 URL 事实。
 */
final class OrchestrationSourceEvidenceCatalog {

    private final LinkedHashMap<String, EvidenceState> evidenceStateBySourceUrl = new LinkedHashMap<>();

    private OrchestrationSourceEvidenceCatalog() {
    }

    static OrchestrationSourceEvidenceCatalog from(OrchestrationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("normalizedContext 不能为空");
        }
        OrchestrationSourceEvidenceCatalog catalog = new OrchestrationSourceEvidenceCatalog();
        catalog.register(context.getSourceUrls(), context.getEvidenceState());
        if (context.getAgentSuggestions() != null) {
            for (AgentSuggestion suggestion : context.getAgentSuggestions()) {
                if (suggestion != null) {
                    catalog.register(suggestion.getSourceUrls(), suggestion.getEvidenceState());
                }
            }
        }
        return catalog;
    }

    List<String> allowedSourceUrls() {
        return List.copyOf(evidenceStateBySourceUrl.keySet());
    }

    boolean contains(String url) {
        return evidenceStateBySourceUrl.containsKey(url);
    }

    EvidenceState resolveDecisionEvidenceState(List<String> selectedUrls) {
        if (selectedUrls == null || selectedUrls.isEmpty()) {
            return EvidenceState.MISSING_SOURCE;
        }
        EvidenceState resolved = null;
        for (String selectedUrl : selectedUrls) {
            EvidenceState urlState = evidenceStateBySourceUrl.get(selectedUrl);
            resolved = resolved == null ? urlState : mergeEvidenceState(resolved, urlState);
        }
        return resolved == null ? EvidenceState.MISSING_SOURCE : resolved;
    }

    static boolean isValidHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void register(List<String> urls, EvidenceState ownerState) {
        if (urls == null) {
            return;
        }
        EvidenceState normalizedOwnerState = normalizeOwnerState(ownerState);
        for (String rawUrl : urls) {
            String url = rawUrl == null || rawUrl.isBlank() ? null : rawUrl.trim();
            if (!isValidHttpUrl(url)) {
                continue;
            }
            // LinkedHashMap.merge 保留首次插入位置，状态合并不受后续 owner 顺序影响。
            evidenceStateBySourceUrl.merge(
                    url,
                    normalizedOwnerState,
                    OrchestrationSourceEvidenceCatalog::mergeEvidenceState);
        }
    }

    private static EvidenceState normalizeOwnerState(EvidenceState state) {
        return state == EvidenceState.FULL_SOURCE
                ? EvidenceState.FULL_SOURCE
                : EvidenceState.PARTIAL_SOURCE;
    }

    /** FULL 只有在所有 owner 都明确 FULL 时成立，其余组合一律保守为 PARTIAL。 */
    private static EvidenceState mergeEvidenceState(EvidenceState left, EvidenceState right) {
        return left == EvidenceState.FULL_SOURCE && right == EvidenceState.FULL_SOURCE
                ? EvidenceState.FULL_SOURCE
                : EvidenceState.PARTIAL_SOURCE;
    }
}
