package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.ConversationSessionRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.FormDraftRepository;
import cn.bugstack.competitoragent.repository.IntentDecisionRepository;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import cn.bugstack.competitoragent.repository.RecoveryCheckpointRepository;
import cn.bugstack.competitoragent.repository.ReportExportRecordRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
import cn.bugstack.competitoragent.model.entity.ConversationSession;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务附属数据统一清理服务。
 * <p>
 * 当前工程里与 taskId 明确绑定的数据分散在多张表里，
 * 如果删除任务时只清理主表和少量派生结果，后续就会留下孤儿记录，
 * 进而污染回放、恢复、审计和 RAG 读取。
 * 因此这里统一收口“按 taskId 删除明确归属当前任务的数据”。
 */
@Service
@RequiredArgsConstructor
public class TaskArtifactCleanupService {

    private final ReportRepository reportRepository;
    private final ReportExportRecordRepository reportExportRecordRepository;
    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final ConversationSessionRepository conversationSessionRepository;
    private final FormDraftRepository formDraftRepository;
    private final IntentDecisionRepository intentDecisionRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final RetrievalIndexRepository retrievalIndexRepository;
    private final RetrievalChunkRepository retrievalChunkRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final MemorySnapshotRepository memorySnapshotRepository;
    private final MemoryReuseRecordRepository memoryReuseRecordRepository;
    private final AgentExecutionLogRepository logRepository;
    private final AiCallAuditRecordRepository aiCallAuditRecordRepository;
    private final TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository;
    private final WorkflowDeadLetterRecordRepository workflowDeadLetterRecordRepository;
    private final RecoveryCheckpointRepository recoveryCheckpointRepository;
    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final TaskPlanRepository taskPlanRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final DynamicTaskGraphService dynamicTaskGraphService;

    /**
     * 删除明确归属当前任务的附属数据。
     * 这里只处理 taskId 级归属清晰的数据，不主动清理会话等跨任务语义仍可能复用的对象，
     * 避免把“删除任务”误扩展成“删除用户会话历史”。
     */
    public void cleanupTaskArtifacts(Long taskId) {
        if (taskId == null) {
            return;
        }

        /*
         * 对话域对象也显式挂在 taskId 上。
         * 这里先取出会话主键，再按 taskId 和 conversationSessionId 双重清理，
         * 兼容历史数据里 taskId 为空、但会话仍归属当前任务的情况。
         */
        List<Long> conversationSessionIds = conversationSessionRepository.findByTaskId(taskId).stream()
                .map(ConversationSession::getId)
                .toList();
        formDraftRepository.deleteByTaskId(taskId);
        intentDecisionRepository.deleteByTaskId(taskId);
        if (!conversationSessionIds.isEmpty()) {
            formDraftRepository.deleteByConversationSessionIdIn(conversationSessionIds);
            intentDecisionRepository.deleteByConversationSessionIdIn(conversationSessionIds);
        }
        conversationSessionRepository.deleteByTaskId(taskId);

        reportRepository.deleteByTaskId(taskId);
        reportExportRecordRepository.deleteByTaskId(taskId);

        knowledgeRepository.deleteByTaskId(taskId);
        knowledgeDocumentRepository.deleteByTaskId(taskId);
        retrievalIndexRepository.deleteByTaskId(taskId);
        retrievalChunkRepository.deleteByTaskId(taskId);

        evidenceRepository.deleteByTaskId(taskId);
        memorySnapshotRepository.deleteByTaskId(taskId);
        memoryReuseRecordRepository.deleteByTaskId(taskId);

        logRepository.deleteByTaskId(taskId);
        aiCallAuditRecordRepository.deleteByTaskId(taskId);
        taskNodeExecutionAttemptRepository.deleteByTaskId(taskId);
        workflowDeadLetterRecordRepository.deleteByTaskId(taskId);

        recoveryCheckpointRepository.deleteByTaskId(taskId);
        taskWorkflowEventRepository.deleteByTaskId(taskId);
        taskPlanRepository.deleteByTaskId(taskId);
    }

    /**
     * 清理节点重跑时需要失效的派生产物。
     * <p>
     * 这里延续 phase2 之前已经验证过的清理语义，
     * 只是把实现位置从运行时命令服务迁到统一清理服务，避免 task 用例继续直连多种仓储。
     */
    public void cleanupNodeArtifacts(Long taskId, String nodeName) {
        if (taskId == null || nodeName == null || nodeName.isBlank()) {
            return;
        }

        List<TaskNode> nodes = taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            return;
        }

        TaskNode targetNode = nodes.stream()
                .filter(node -> nodeName.equals(node.getNodeName()))
                .findFirst()
                .orElse(null);
        if (targetNode == null) {
            return;
        }

        List<TaskNode> affectedNodes = dynamicTaskGraphService.calculateAffectedNodes(nodes, targetNode);
        if (affectedNodes.isEmpty()) {
            return;
        }

        Set<String> affectedNodeNames = new HashSet<>();
        for (TaskNode affectedNode : affectedNodes) {
            affectedNodeNames.add(affectedNode.getNodeName());
        }

        if (targetNode.getNodeName().startsWith("collect_sources")) {
            evidenceRepository.deleteByTaskIdAndEvidenceIdStartingWith(
                    taskId,
                    buildEvidencePrefix(taskId, targetNode.getNodeName()));
        }

        if (affectedNodeNames.contains("extract_schema")) {
            knowledgeRepository.deleteByTaskId(taskId);
            reportRepository.deleteByTaskId(taskId);
            return;
        }

        if (affectedNodeNames.contains("analyze_competitors") || "write_report".equals(targetNode.getNodeName())) {
            reportRepository.deleteByTaskId(taskId);
        }
    }

    private String buildEvidencePrefix(Long taskId, String nodeName) {
        long safeTaskId = taskId == null ? 0L : taskId;
        String safeNodeName = nodeName == null || nodeName.isBlank()
                ? "NODE"
                : nodeName.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
        return String.format("T%04d-%s-", safeTaskId % 10000, safeNodeName);
    }
}
