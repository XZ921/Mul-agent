package cn.bugstack.competitoragent.orchestration;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单次运行期决策开始前恢复出的持久化状态。
 * checkpoint 的可靠性与内部计数分开表达，使协调器能够对不可读状态执行 fail-closed。
 */
public record OrchestrationRuntimeState(
        int currentDecisionCount,
        Map<String, Integer> dynamicBranchCountsBySection,
        Long currentPlanVersionId,
        int nextPlanVersion,
        CheckpointStateStatus checkpointStateStatus,
        List<String> sourceUrls
) {

    public static final String UNSCOPED_SECTION = "__unscoped__";

    public enum CheckpointStateStatus {
        ABSENT,
        RESTORED,
        UNREADABLE
    }

    public OrchestrationRuntimeState {
        CheckpointStateStatus normalizedStatus = checkpointStateStatus == null
                ? CheckpointStateStatus.UNREADABLE
                : checkpointStateStatus;

        // 负数计数说明 checkpoint 语义已经损坏。内部值仍归零以保证类型安全，
        // 但状态必须收紧为 UNREADABLE，后续由协调器按“自动额度已耗尽”处理。
        if (currentDecisionCount < 0) {
            currentDecisionCount = 0;
            normalizedStatus = CheckpointStateStatus.UNREADABLE;
        }
        NormalizedSectionCounts normalizedCounts = normalizeSectionCounts(dynamicBranchCountsBySection);
        if (normalizedCounts.invalid()) {
            normalizedStatus = CheckpointStateStatus.UNREADABLE;
        }
        dynamicBranchCountsBySection = normalizedCounts.counts();
        nextPlanVersion = Math.max(1, nextPlanVersion);
        checkpointStateStatus = normalizedStatus;
        sourceUrls = normalizeSourceUrls(sourceUrls);
    }

    /**
     * section key 是配额判断的稳定键；空 section 也必须进入统一桶，禁止用空值绕过分支上限。
     */
    public static String normalizeSectionKey(String section) {
        if (section == null || section.isBlank()) {
            return UNSCOPED_SECTION;
        }
        return section.trim().toLowerCase(Locale.ROOT);
    }

    private static NormalizedSectionCounts normalizeSectionCounts(Map<String, Integer> values) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        boolean invalid = false;
        if (values != null) {
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                String key = normalizeSectionKey(entry.getKey());
                Integer rawCount = entry.getValue();
                if (rawCount == null || rawCount < 0) {
                    invalid = true;
                }
                int count = Math.max(0, rawCount == null ? 0 : rawCount);
                // 同一 section 的大小写或空白别名只保留最大已持久化值，避免重复键相加造成误限流。
                normalized.merge(key, count, Math::max);
            }
        }
        return new NormalizedSectionCounts(Map.copyOf(normalized), invalid);
    }

    private static List<String> normalizeSourceUrls(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        return List.copyOf(normalized);
    }

    private record NormalizedSectionCounts(Map<String, Integer> counts, boolean invalid) {
    }
}
