package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 字段证据 query 执行闸门。
 * 它的职责不是重新规划 query，而是在 provider 执行前把 planned query 收敛成
 * “每字段最多 N 条、第三方至少保留 M 条、每节点最多 K 条”的可执行快照。
 */
@Component
public class FieldEvidenceQueryExecutionGate {

    private static final Comparator<FieldEvidenceQuery> FIELD_EVIDENCE_QUERY_COMPARATOR = Comparator
            .comparing(FieldEvidenceQueryExecutionGate::resolvePriority)
            .thenComparing(FieldEvidenceQueryExecutionGate::resolveFingerprint, Comparator.nullsLast(String::compareTo))
            .thenComparing(FieldEvidenceQuery::getQuery, Comparator.nullsLast(String::compareTo));

    /**
     * 按字段配额和节点级 fail-safe 生成最终可执行计划。
     * 第一层先在字段内部保住第三方名额，避免 OFFICIAL/DOCS 把 REVIEW/NEWS/OPEN_WEB 全部饿死；
     * 第二层再用轮转裁剪全局数量，避免重新退化回“全局 priority top-N”。
     */
    public FieldEvidenceQueryExecutionPlan resolve(List<FieldEvidenceQuery> planned,
                                                   int maxPerField,
                                                   int minThirdPartyPerField,
                                                   int maxPerNode) {
        List<FieldEvidenceQuery> ordered = sortQueries(planned);
        if (ordered.isEmpty()) {
            return FieldEvidenceQueryExecutionPlan.empty();
        }

        Map<String, List<FieldEvidenceQuery>> byField = ordered.stream()
                .collect(Collectors.groupingBy(
                        query -> defaultText(query == null ? null : query.getFieldName(), "unknown"),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<FieldEvidenceQuery> executable = new ArrayList<>();
        List<FieldEvidenceQuery> skipped = new ArrayList<>();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        int normalizedMaxPerField = Math.max(0, maxPerField);
        int normalizedThirdPartyReserve = Math.max(0, minThirdPartyPerField);
        int normalizedMaxPerNode = Math.max(0, maxPerNode);

        for (List<FieldEvidenceQuery> fieldQueries : byField.values()) {
            FieldSlice slice = selectFieldQueries(fieldQueries, normalizedMaxPerField, normalizedThirdPartyReserve);
            executable.addAll(slice.executable());
            skipped.addAll(slice.skipped());
            increment(skipReasons, "SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", slice.skipped().size());
        }

        if (executable.size() > normalizedMaxPerNode) {
            List<FieldEvidenceQuery> retained = retainByFieldRoundRobin(executable, normalizedMaxPerNode);
            List<FieldEvidenceQuery> overflow = resolveOverflow(executable, retained);
            executable = retained;
            skipped.addAll(overflow);
            increment(skipReasons, "SKIPPED_NODE_QUERY_CAP_EXHAUSTED", overflow.size());
        }

        return new FieldEvidenceQueryExecutionPlan(
                ordered,
                executable,
                skipped,
                skipReasons,
                countBy(executable, FieldEvidenceQuery::getFieldName, "unknown"),
                countBy(executable, FieldEvidenceQuery::getSourceType, "UNKNOWN")
        );
    }

    /**
     * 字段内选择分两步：
     * 1. 先锁定需要保留的第三方 query；
     * 2. 再按原始 priority 顺序补齐剩余名额。
     * 这样既保住第三方覆盖，也不会打乱字段内优先级。
     */
    private FieldSlice selectFieldQueries(List<FieldEvidenceQuery> fieldQueries,
                                          int maxPerField,
                                          int minThirdPartyPerField) {
        if (fieldQueries == null || fieldQueries.isEmpty()) {
            return new FieldSlice(List.of(), List.of());
        }
        if (maxPerField <= 0) {
            return new FieldSlice(List.of(), List.copyOf(fieldQueries));
        }

        int thirdPartyReserve = Math.min(
                maxPerField,
                Math.min(minThirdPartyPerField, (int) fieldQueries.stream().filter(this::isThirdPartyQuery).count())
        );
        LinkedHashSet<Integer> selectedIndexes = new LinkedHashSet<>();

        for (int index = 0; index < fieldQueries.size() && selectedIndexes.size() < thirdPartyReserve; index++) {
            if (isThirdPartyQuery(fieldQueries.get(index))) {
                selectedIndexes.add(index);
            }
        }
        for (int index = 0; index < fieldQueries.size() && selectedIndexes.size() < maxPerField; index++) {
            selectedIndexes.add(index);
        }

        List<FieldEvidenceQuery> executable = new ArrayList<>();
        List<FieldEvidenceQuery> skipped = new ArrayList<>();
        for (int index = 0; index < fieldQueries.size(); index++) {
            if (selectedIndexes.contains(index)) {
                executable.add(fieldQueries.get(index));
            } else {
                skipped.add(fieldQueries.get(index));
            }
        }
        return new FieldSlice(executable, skipped);
    }

    /**
     * 全局 cap 只负责 fail-safe，不重新回退成纯 priority top-N。
     * 这里按字段轮转保留 query，让多个字段都能至少留下一轮执行机会。
     */
    private List<FieldEvidenceQuery> retainByFieldRoundRobin(List<FieldEvidenceQuery> executable,
                                                             int maxPerNode) {
        if (executable == null || executable.isEmpty() || maxPerNode <= 0) {
            return List.of();
        }

        Map<String, Queue<FieldEvidenceQuery>> byField = new LinkedHashMap<>();
        for (FieldEvidenceQuery query : executable) {
            String fieldName = defaultText(query == null ? null : query.getFieldName(), "unknown");
            byField.computeIfAbsent(fieldName, ignored -> new ArrayDeque<>()).add(query);
        }

        List<FieldEvidenceQuery> retained = new ArrayList<>();
        while (retained.size() < maxPerNode) {
            boolean pickedAny = false;
            for (Queue<FieldEvidenceQuery> queue : byField.values()) {
                FieldEvidenceQuery next = queue.poll();
                if (next == null) {
                    continue;
                }
                retained.add(next);
                pickedAny = true;
                if (retained.size() >= maxPerNode) {
                    break;
                }
            }
            if (!pickedAny) {
                break;
            }
        }
        return retained;
    }

    private List<FieldEvidenceQuery> resolveOverflow(List<FieldEvidenceQuery> executable,
                                                     List<FieldEvidenceQuery> retained) {
        IdentityHashMap<FieldEvidenceQuery, Integer> retainedCounter = new IdentityHashMap<>();
        for (FieldEvidenceQuery query : retained) {
            retainedCounter.merge(query, 1, Integer::sum);
        }

        List<FieldEvidenceQuery> overflow = new ArrayList<>();
        for (FieldEvidenceQuery query : executable) {
            Integer remaining = retainedCounter.get(query);
            if (remaining == null || remaining <= 0) {
                overflow.add(query);
                continue;
            }
            if (remaining == 1) {
                retainedCounter.remove(query);
            } else {
                retainedCounter.put(query, remaining - 1);
            }
        }
        return overflow;
    }

    private List<FieldEvidenceQuery> sortQueries(List<FieldEvidenceQuery> planned) {
        if (planned == null || planned.isEmpty()) {
            return List.of();
        }
        return planned.stream()
                .filter(query -> query != null && StringUtils.hasText(query.getQueryFingerprint()))
                .sorted(FIELD_EVIDENCE_QUERY_COMPARATOR)
                .toList();
    }

    private Map<String, Integer> countBy(List<FieldEvidenceQuery> queries,
                                         Function<FieldEvidenceQuery, String> classifier,
                                         String fallbackValue) {
        if (queries == null || queries.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (FieldEvidenceQuery query : queries) {
            if (query == null) {
                continue;
            }
            counters.merge(defaultText(classifier.apply(query), fallbackValue), 1, Integer::sum);
        }
        return counters;
    }

    private void increment(Map<String, Integer> counters, String key, int delta) {
        if (counters == null || !StringUtils.hasText(key) || delta <= 0) {
            return;
        }
        counters.merge(key, delta, Integer::sum);
    }

    private boolean isThirdPartyQuery(FieldEvidenceQuery query) {
        if (query == null || !StringUtils.hasText(query.getSourceType())) {
            return false;
        }
        String normalizedSourceType = query.getSourceType().trim().toUpperCase(Locale.ROOT);
        return "REVIEW".equals(normalizedSourceType)
                || "NEWS".equals(normalizedSourceType)
                || "OPEN_WEB".equals(normalizedSourceType);
    }

    private static Integer resolvePriority(FieldEvidenceQuery query) {
        return query == null || query.getPriority() == null ? Integer.MAX_VALUE : query.getPriority();
    }

    private static String resolveFingerprint(FieldEvidenceQuery query) {
        return query == null ? null : query.getQueryFingerprint();
    }

    private String defaultText(String value, String fallbackValue) {
        return StringUtils.hasText(value) ? value.trim() : fallbackValue;
    }

    private record FieldSlice(List<FieldEvidenceQuery> executable, List<FieldEvidenceQuery> skipped) {
    }
}
