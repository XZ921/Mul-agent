package cn.bugstack.competitoragent.memory;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.MemoryReuseRecord;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 负责把知识上下文、可复用记忆和任务即时上下文融合为统一输入。
 * <p>
 * Task 5.4.c 在 5.4.b 的基础上继续补齐：
 * 1. 读侧复用结果必须携带版本来源和失效边界；
 * 2. 短期记忆在计划重跑后不能继续污染新上下文；
 * 3. 复用审计记录要同步带上复用说明和边界字段。
 */
@Service
public class MemoryFusionService {

    private final MemorySnapshotRepository memorySnapshotRepository;
    private final CompetitorKnowledgeRepository competitorKnowledgeRepository;
    private final MemoryReuseRecordRepository memoryReuseRecordRepository;
    private final MemoryReusePolicy memoryReusePolicy;
    private final ObjectMapper objectMapper;

    @Autowired
    public MemoryFusionService(MemorySnapshotRepository memorySnapshotRepository,
                               CompetitorKnowledgeRepository competitorKnowledgeRepository,
                               MemoryReuseRecordRepository memoryReuseRecordRepository,
                               MemoryReusePolicy memoryReusePolicy,
                               ObjectMapper objectMapper) {
        this.memorySnapshotRepository = memorySnapshotRepository;
        this.competitorKnowledgeRepository = competitorKnowledgeRepository;
        this.memoryReuseRecordRepository = memoryReuseRecordRepository;
        this.memoryReusePolicy = memoryReusePolicy;
        this.objectMapper = objectMapper;
    }

    public MemoryFusionService(MemorySnapshotRepository memorySnapshotRepository,
                               CompetitorKnowledgeRepository competitorKnowledgeRepository,
                               MemoryReuseRecordRepository memoryReuseRecordRepository,
                               ObjectMapper objectMapper) {
        this(memorySnapshotRepository,
                competitorKnowledgeRepository,
                memoryReuseRecordRepository,
                new MemoryReusePolicy(),
                objectMapper);
    }

    public MemoryFusionService(MemorySnapshotRepository memorySnapshotRepository,
                               CompetitorKnowledgeRepository competitorKnowledgeRepository,
                               MemoryReuseRecordRepository memoryReuseRecordRepository) {
        this(memorySnapshotRepository,
                competitorKnowledgeRepository,
                memoryReuseRecordRepository,
                new MemoryReusePolicy(),
                new ObjectMapper());
    }

    /**
     * 在既有知识上下文上补齐“可复用记忆”和“任务即时上下文”。
     */
    public TaskRagContextBundle fuse(AgentContext context, TaskRagContextBundle knowledgeContext) {
        if (context == null || knowledgeContext == null) {
            return knowledgeContext;
        }

        List<TaskRagContextBundle.ReusableMemoryItem> reusableMemoryItems = new ArrayList<>();
        reusableMemoryItems.addAll(loadShortTermMemories(context));
        reusableMemoryItems.addAll(loadDomainMemories());

        List<TaskRagContextBundle.RuntimeContextItem> runtimeContextItems = loadRuntimeContext(context);

        knowledgeContext.setReusableMemoryItems(reusableMemoryItems);
        knowledgeContext.setRuntimeContextItems(runtimeContextItems);

        persistReuseRecords(context, reusableMemoryItems);
        return knowledgeContext;
    }

    /**
     * 读取同任务内已经沉淀的短期记忆快照。
     * <p>
     * 如果快照声明“计划重跑后失效”，则必须校验版本来源是否与当前任务版本一致，
     * 否则宁可不复用，也不能把旧计划的结论带进新上下文。
     */
    private List<TaskRagContextBundle.ReusableMemoryItem> loadShortTermMemories(AgentContext context) {
        List<TaskRagContextBundle.ReusableMemoryItem> items = new ArrayList<>();
        if (memorySnapshotRepository == null || context.getTaskId() == null) {
            return items;
        }

        List<MemorySnapshot> snapshots = memorySnapshotRepository.findByTaskIdOrderByIdDesc(context.getTaskId());
        List<MemoryReuseRecord> reuseRecords = loadTaskReuseRecords(context.getTaskId());
        if (snapshots == null) {
            return items;
        }

        for (MemorySnapshot snapshot : snapshots) {
            if (!isReusableShortTermSnapshot(context, snapshot)) {
                continue;
            }

            items.add(TaskRagContextBundle.ReusableMemoryItem.builder()
                    .memoryLayer("SHORT_TERM")
                    .sourceObjectType("MEMORY_SNAPSHOT")
                    .sourceNodeName(snapshot.getNodeName())
                    .sourceRecordId(snapshot.getId())
                    .sourceTaskId(snapshot.getTaskId())
                    .summary(snapshot.getSummary())
                    .versionSource(firstNonBlank(snapshot.getVersionSource(), "UNSPECIFIED"))
                    .invalidationScope(firstNonBlank(snapshot.getInvalidationScope(), "MANUAL_REVIEW"))
                    .invalidationReason(firstNonBlank(snapshot.getInvalidationReason(), "NOT_EVALUATED"))
                    .reuseReason(resolveReuseReason(
                            snapshot.getInvalidationScope(),
                            "同计划版本内可复用，计划重跑后失效",
                            "当前任务短期记忆可作为补充上下文，但仍需回指 sourceUrls"
                    ))
                    .sourceUrls(defaultList(snapshot.getSourceUrls()))
                    .build());
            /**
             * 如果这条短期记忆最初是经由正式写回链路沉淀的，
             * 则优先恢复写回审计里记录的原始复用说明，避免读侧退化成通用模板文案。
             */
            items.get(items.size() - 1).setReuseReason(resolveSnapshotReuseReason(snapshot, reuseRecords));

            if (items.size() >= 3) {
                break;
            }
        }
        return items;
    }

    /**
     * 读取领域层可复用知识。
     * 领域知识默认允许跨任务复用，但仍必须显式带上版本来源和失效边界。
     */
    private List<TaskRagContextBundle.ReusableMemoryItem> loadDomainMemories() {
        List<TaskRagContextBundle.ReusableMemoryItem> items = new ArrayList<>();
        if (competitorKnowledgeRepository == null) {
            return items;
        }

        List<CompetitorKnowledge> knowledgeList = competitorKnowledgeRepository.findByMemoryLayerOrderByIdAsc("DOMAIN");
        if (knowledgeList == null) {
            return items;
        }

        for (CompetitorKnowledge knowledge : knowledgeList) {
            if (knowledge == null || !StringUtils.hasText(knowledge.getSummary())) {
                continue;
            }
            items.add(TaskRagContextBundle.ReusableMemoryItem.builder()
                    .memoryLayer("DOMAIN")
                    .sourceObjectType("COMPETITOR_KNOWLEDGE")
                    .sourceNodeName(knowledge.getCompetitorName())
                    .sourceRecordId(knowledge.getId())
                    .sourceTaskId(knowledge.getTaskId())
                    .summary(knowledge.getSummary())
                    .versionSource(firstNonBlank(knowledge.getVersionSource(), "UNSPECIFIED"))
                    .invalidationScope(firstNonBlank(knowledge.getInvalidationScope(), "MANUAL_REVIEW"))
                    .invalidationReason(firstNonBlank(knowledge.getInvalidationReason(), "NOT_EVALUATED"))
                    .reuseReason(resolveReuseReason(
                            knowledge.getInvalidationScope(),
                            "可作为跨任务领域背景复用，领域资料刷新后重新评估",
                            "可作为跨任务背景参考，但仍需回指 sourceUrls 和知识来源"
                    ))
                    .sourceUrls(parseSourceUrls(knowledge.getSourceUrls()))
                    .build());

            if (items.size() >= 3) {
                break;
            }
        }
        return items;
    }

    /**
     * 任务即时上下文只消费当前任务已经确认的节点输出，
     * 明确告诉下游 Agent：这部分是现场信息，不是知识也不是历史记忆。
     */
    private List<TaskRagContextBundle.RuntimeContextItem> loadRuntimeContext(AgentContext context) {
        List<TaskRagContextBundle.RuntimeContextItem> items = new ArrayList<>();
        Map<String, String> sharedState = context.getSharedState();
        if (sharedState == null || sharedState.isEmpty()) {
            return items;
        }

        for (Map.Entry<String, String> entry : sharedState.entrySet()) {
            if (!StringUtils.hasText(entry.getValue())) {
                continue;
            }
            items.add(TaskRagContextBundle.RuntimeContextItem.builder()
                    .sourceNodeName(entry.getKey())
                    .summary(entry.getValue())
                    .build());
        }
        return items;
    }

    /**
     * 只要当前真的复用了记忆，就把版本来源、失效边界和复用说明同步落到审计记录。
     */
    private void persistReuseRecords(AgentContext context, List<TaskRagContextBundle.ReusableMemoryItem> items) {
        if (memoryReuseRecordRepository == null || context.getTaskId() == null || items == null || items.isEmpty()) {
            return;
        }

        for (TaskRagContextBundle.ReusableMemoryItem item : items) {
            try {
                memoryReuseRecordRepository.save(MemoryReuseRecord.builder()
                        .taskId(context.getTaskId())
                        .consumerNodeName(firstNonBlank(context.getCurrentNodeName(), "UNKNOWN"))
                        .sourceMemoryLayer(firstNonBlank(item.getMemoryLayer(), "UNKNOWN"))
                        .sourceObjectType(firstNonBlank(item.getSourceObjectType(), "UNKNOWN"))
                        .sourceRecordId(item.getSourceRecordId())
                        .sourceTaskId(item.getSourceTaskId())
                        .sourceSummary(item.getSummary())
                        .sourceUrls(defaultList(item.getSourceUrls()))
                        .reuseReason(firstNonBlank(item.getReuseReason(), "当前复用未提供结构化说明"))
                        .versionSource(firstNonBlank(item.getVersionSource(), "UNSPECIFIED"))
                        .invalidationScope(firstNonBlank(item.getInvalidationScope(), "MANUAL_REVIEW"))
                        .build());
            } catch (Exception ignored) {
                // 当前阶段不让留痕失败阻断主链路；
                // 但复用说明一旦能写入，就必须尽量带齐版本和失效边界。
            }
        }
    }

    /**
     * `CompetitorKnowledge.sourceUrls` 仍以 JSON 字符串存储，
     * 这里做最小解析，确保融合结果继续满足 sourceUrls 可追溯要求。
     */
    private List<String> parseSourceUrls(String sourceUrls) {
        if (!StringUtils.hasText(sourceUrls)) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(sourceUrls, new TypeReference<List<String>>() {
            });
            return defaultList(values);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 只在快照显式声明“任务重跑后失效”且版本来源不一致时拒绝复用；
     * 对历史遗留的无版本快照仍保持向后兼容，不额外扩大本轮范围。
     */
    private boolean isReusableShortTermSnapshot(AgentContext context, MemorySnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (!"SHORT_TERM".equalsIgnoreCase(snapshot.getMemoryLayer())) {
            return false;
        }
        if (!StringUtils.hasText(snapshot.getSummary())) {
            return false;
        }
        if (safe(snapshot.getNodeName()).equals(safe(context.getCurrentNodeName()))) {
            return false;
        }

        String invalidationScope = firstNonBlank(snapshot.getInvalidationScope(), "MANUAL_REVIEW");
        if (!"TASK_RERUN".equalsIgnoreCase(invalidationScope)) {
            return true;
        }

        if (context.getPlanVersionId() == null && !StringUtils.hasText(context.getBranchKey())) {
            return true;
        }

        String snapshotVersionSource = firstNonBlank(snapshot.getVersionSource(), "UNSPECIFIED");
        if ("UNSPECIFIED".equalsIgnoreCase(snapshotVersionSource)) {
            return true;
        }

        String currentVersionSource = memoryReusePolicy.buildVersionSource(context.getPlanVersionId(), context.getBranchKey());
        return snapshotVersionSource.equalsIgnoreCase(currentVersionSource);
    }

    private String resolveReuseReason(String invalidationScope, String governedReason, String fallbackReason) {
        return "TASK_RERUN".equalsIgnoreCase(invalidationScope) || "DOMAIN_REFRESH".equalsIgnoreCase(invalidationScope)
                ? governedReason
                : fallbackReason;
    }

    /**
     * 写回链路会把原始复用说明沉淀到 MemoryReuseRecord。
     * 这里读取同任务下匹配当前快照的 WRITEBACK_AUDIT 记录，保证后续复用时还能解释最初的治理判断。
     */
    private String resolveSnapshotReuseReason(MemorySnapshot snapshot, List<MemoryReuseRecord> reuseRecords) {
        String fallbackReason = resolveReuseReason(
                snapshot == null ? null : snapshot.getInvalidationScope(),
                "同计划版本内可复用，计划重跑后失效",
                "当前任务短期记忆可作为补充上下文，但仍需回指 sourceUrls"
        );
        if (snapshot == null || reuseRecords == null || reuseRecords.isEmpty()) {
            return fallbackReason;
        }

        for (int index = reuseRecords.size() - 1; index >= 0; index--) {
            MemoryReuseRecord record = reuseRecords.get(index);
            if (!isWritebackAuditForSnapshot(record, snapshot)) {
                continue;
            }
            if (StringUtils.hasText(record.getReuseReason())) {
                return record.getReuseReason();
            }
        }
        return fallbackReason;
    }

    /**
     * 只回放正式写回时留下的来源说明，不把后续消费阶段追加的复用记录反向当成“原始原因”。
     */
    private boolean isWritebackAuditForSnapshot(MemoryReuseRecord record, MemorySnapshot snapshot) {
        if (record == null || snapshot == null || snapshot.getId() == null) {
            return false;
        }
        if (!snapshot.getId().equals(record.getSourceRecordId())) {
            return false;
        }
        return "WRITEBACK_AUDIT".equalsIgnoreCase(firstNonBlank(record.getSourceObjectType(), ""));
    }

    private List<MemoryReuseRecord> loadTaskReuseRecords(Long taskId) {
        if (memoryReuseRecordRepository == null || taskId == null) {
            return List.of();
        }
        try {
            List<MemoryReuseRecord> records = memoryReuseRecordRepository.findByTaskIdOrderByIdAsc(taskId);
            return records == null ? List.of() : records;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }
}
