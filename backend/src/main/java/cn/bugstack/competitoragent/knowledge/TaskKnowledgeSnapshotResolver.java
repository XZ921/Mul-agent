package cn.bugstack.competitoragent.knowledge;

import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 任务级知识快照解析器。
 * <p>
 * rerun / revision patch 之后，同一 taskId 下可能沉淀多条同竞品的 TASK 快照；
 * 如果下游继续把历史快照全部一起消费，就会把旧 coverage 缺口重新带回 analyzer / reviewer / report。
 * 因此这里统一收敛为“当前任务有效快照”：
 * 1. 优先只消费 snapshotScope=TASK 的记录，避免 DOMAIN 写回结果反向污染任务现场；
 * 2. 同一竞品如果存在多条 TASK 快照，只保留最新的一条。
 */
public final class TaskKnowledgeSnapshotResolver {

    private static final String TASK_SCOPE = "TASK";

    private TaskKnowledgeSnapshotResolver() {
    }

    public static List<CompetitorKnowledge> resolveCurrentTaskSnapshots(List<CompetitorKnowledge> knowledges) {
        if (knowledges == null || knowledges.isEmpty()) {
            return List.of();
        }

        List<CompetitorKnowledge> normalized = new ArrayList<>();
        for (CompetitorKnowledge knowledge : knowledges) {
            if (knowledge != null) {
                normalized.add(knowledge);
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<CompetitorKnowledge> taskScoped = new ArrayList<>();
        for (CompetitorKnowledge knowledge : normalized) {
            if (TASK_SCOPE.equalsIgnoreCase(safeText(knowledge.getSnapshotScope()))) {
                taskScoped.add(knowledge);
            }
        }

        List<CompetitorKnowledge> effectiveSnapshots = taskScoped.isEmpty() ? normalized : taskScoped;
        LinkedHashMap<String, CompetitorKnowledge> latestByCompetitor = new LinkedHashMap<>();
        for (CompetitorKnowledge knowledge : effectiveSnapshots) {
            String competitorKey = normalizeCompetitorName(knowledge.getCompetitorName());
            CompetitorKnowledge existing = latestByCompetitor.get(competitorKey);
            if (existing == null || isNewerSnapshot(existing, knowledge)) {
                latestByCompetitor.put(competitorKey, knowledge);
            }
        }
        return new ArrayList<>(latestByCompetitor.values());
    }

    /**
     * 同一竞品出现多份任务快照时，优先取更晚落库的那份。
     * 当前主排序信号使用自增 id；若测试桩未设置 id，则退化为“后出现者覆盖前者”。
     */
    private static boolean isNewerSnapshot(CompetitorKnowledge existing, CompetitorKnowledge candidate) {
        Long existingId = existing == null ? null : existing.getId();
        Long candidateId = candidate == null ? null : candidate.getId();
        if (existingId != null && candidateId != null) {
            return candidateId >= existingId;
        }
        return true;
    }

    private static String normalizeCompetitorName(String competitorName) {
        String value = safeText(competitorName);
        return value.isBlank() ? "UNKNOWN" : value;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
