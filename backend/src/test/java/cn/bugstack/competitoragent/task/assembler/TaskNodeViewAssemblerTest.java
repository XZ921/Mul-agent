package cn.bugstack.competitoragent.task.assembler;

import cn.bugstack.competitoragent.model.dto.CollectorNodeInsightResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskNodeViewAssemblerTest {

    private final AiCallAuditRecordRepository aiCallAuditRecordRepository = mock(AiCallAuditRecordRepository.class);
    private final TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    private final TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskNodeViewAssembler assembler = new TaskNodeViewAssembler(
            aiCallAuditRecordRepository,
            taskPlanRepository,
            taskRecoveryService,
            objectMapper
    );

    @Test
    void shouldExposeSearchReplayFactsInCollectorInsight() {
        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(anyLong())).thenReturn(List.of());
        when(aiCallAuditRecordRepository.findTopByTaskIdAndNodeNameOrderByCreatedAtDesc(anyLong(), anyString()))
                .thenReturn(Optional.<AiCallAuditRecord>empty());

        TaskNode collectNode = TaskNode.builder()
                .id(1L)
                .taskId(42L)
                .nodeName("collect_sources_docs")
                .displayName("Collect Docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .nodeConfig("""
                        {
                          "competitorName":"Example",
                          "sourceType":"DOCS",
                          "sourceScope":["DOCS"],
                          "competitorUrls":["https://www.example.com"],
                          "sourceCandidates":[{"url":"https://docs.example.com/reference","sourceType":"DOCS","sourceUrls":["https://docs.example.com/reference"]}],
                          "searchMode":"HYBRID",
                          "searchQueries":["Example docs"],
                          "browserSearchEnabled":true,
                          "verifyResultPage":true,
                          "minVerifiedCandidates":1
                        }
                        """)
                .outputData("""
                        {
                          "competitor":"Example",
                          "sourceType":"DOCS",
                          "selectedTargets":[],
                          "successCollected":1,
                          "totalCollected":1,
                          "sourceUrls":["https://docs.example.com/reference"],
                          "searchAudit":{
                            "attemptedTargets":[{"candidate":{"url":"https://docs.example.com/reference","sourceUrls":["https://docs.example.com/reference"]}}],
                            "discardedCandidates":[{"url":"https://www.example.com/login","sourceType":"DOCS","selectionStage":"DISCARDED","selectionReason":"LOW_SIGNAL_UTILITY_PAGE","sourceUrls":["https://www.example.com/login"]}],
                            "replayTimeline":[{"stepCode":"SELECT_TARGETS","status":"SUCCESS","sourceUrls":["https://docs.example.com/reference"]}],
                            "sourceUrls":["https://docs.example.com/reference"]
                          }
                        }
                        """)
                .build();
        AnalysisTask task = AnalysisTask.builder()
                .id(42L)
                .taskName("search replay facts")
                .subjectProduct("Example Product")
                .competitorNames("[\"Example\"]")
                .status(AnalysisTaskStatus.SUCCESS)
                .build();

        TaskNodeResponse response = assembler.toNodeResponse(task, collectNode, List.of(collectNode));

        CollectorNodeInsightResponse insight = response.getCollectorInsight();
        assertNotNull(insight);
        assertNotNull(insight.getSearchAudit());
        assertEquals(1, insight.getAttemptedTargets().size());
        assertEquals(1, insight.getDiscardedCandidates().size());
        assertEquals("SELECT_TARGETS", insight.getSearchReplayTimeline().get(0).getStepCode());
        assertEquals(insight.getSearchAudit().getAttemptedTargets(), insight.getAttemptedTargets());
        assertEquals(insight.getSearchAudit().getDiscardedCandidates(), insight.getDiscardedCandidates());
        assertEquals(insight.getSearchAudit().getReplayTimeline(), insight.getSearchReplayTimeline());
    }
}
