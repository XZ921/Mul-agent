package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.TaskRecoveryAdvice;
import cn.bugstack.competitoragent.model.dto.TaskReplayResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.orchestration.AgentSuggestion;
import cn.bugstack.competitoragent.orchestration.CollaborationGoalAssembler;
import cn.bugstack.competitoragent.orchestration.CollaborationPlanService;
import cn.bugstack.competitoragent.orchestration.CollaborationTraceService;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import cn.bugstack.competitoragent.orchestration.ExtractorSuggestionAssembler;
import cn.bugstack.competitoragent.orchestration.InitialPlanReviewService;
import cn.bugstack.competitoragent.orchestration.OrchestrationContext;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionAdapter;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AnalysisSchemaRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProperties;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.RecoveryCheckpointService;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import cn.bugstack.competitoragent.task.TaskReplayProjectionService;
import cn.bugstack.competitoragent.workflow.CollectorPlanTemplateFactory;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.ExecutionPlanDefinitionBuilder;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import cn.bugstack.competitoragent.workflow.WorkflowPlanAssembler;
import cn.bugstack.competitoragent.workflow.WorkflowPlanValidator;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationPlanningSmokeTest {

    @Test
    void shouldRunP2CollaborationPlanningWithoutExternalInfrastructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AnalysisTask task = AnalysisTask.builder()
                .id(88L)
                .taskName("P2 协作规划 Smoke")
                .subjectProduct("企业级 RAG 知识库")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
                .analysisDimensions(objectMapper.writeValueAsString(List.of("pricing")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页")))
                .build();

        List<TaskWorkflowEvent> workflowEvents = new ArrayList<>();
        TaskWorkflowEventRepository workflowEventRepository = mock(TaskWorkflowEventRepository.class);
        when(workflowEventRepository.save(any(TaskWorkflowEvent.class))).thenAnswer(invocation -> {
            TaskWorkflowEvent event = invocation.getArgument(0);
            workflowEvents.add(event);
            return event;
        });
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(nodeRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DynamicTaskGraphService dynamicTaskGraphService = mock(DynamicTaskGraphService.class);
        when(dynamicTaskGraphService.ensureInitialPlan(anyLong(), any(WorkflowPlan.class)))
                .thenReturn(TaskPlan.builder()
                        .id(31L)
                        .taskId(88L)
                        .planVersion(1)
                        .branchKey("root")
                        .planType("INITIAL")
                        .active(true)
                        .planSnapshot("{}")
                        .build());

        WorkflowFactory workflowFactory = new WorkflowFactory(
                nodeRepository,
                new WorkflowPlanValidator(),
                objectMapper,
                dynamicTaskGraphService,
                executionPlanDefinitionBuilder(objectMapper),
                new WorkflowPlanAssembler(),
                new CollaborationGoalAssembler(objectMapper),
                new CollaborationPlanService(),
                new InitialPlanReviewService(),
                new CollaborationTraceService(workflowEventRepository, objectMapper)
        );

        List<TaskNode> nodes = workflowFactory.createWorkflow(task);

        assertThat(nodes).anySatisfy(node -> assertThat(node.getNodeName()).isEqualTo("extract_schema"));
        assertThat(nodes).anySatisfy(node -> assertThat(node.getNodeName()).isEqualTo("quality_check_final"));
        assertThat(workflowEvents).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo(WorkflowEventType.COLLABORATION_PLAN_RECORDED);
            assertThat(event.getPayload()).contains("cp-task-88-v1");
            assertThat(event.getSourceUrls()).contains("https://www.notion.so");
        });

        TaskReplayResponse replay = replayFromEvents(objectMapper, workflowEvents);
        assertThat(replay.getTimeline()).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("COLLABORATION_PLAN_RECORDED");
            assertThat(event.getSummary()).contains("协作计划");
        });
        assertThat(replay.getSourceUrls()).contains("https://www.notion.so");

        ExtractorSuggestionAssembler suggestionAssembler = new ExtractorSuggestionAssembler(objectMapper);
        List<AgentSuggestion> suggestions = suggestionAssembler.fromExtractorOutput(
                88L,
                "extract_schema",
                Map.of(
                        "sourceUrls", List.of(),
                        "issueFlags", List.of("NO_BUSINESS_FIELDS_EXTRACTED"),
                        "evidenceCoverage", Map.of()));

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);

        List<OrchestrationDecision> decisions = new OrchestrationDecisionService(new OrchestrationDecisionAdapter())
                .decide(OrchestrationContext.builder()
                        .taskId(88L)
                        .triggerNodeName("extract_schema")
                        .agentSuggestions(suggestions)
                        .sourceUrls(List.of())
                        .evidenceState(EvidenceState.MISSING_SOURCE)
                        .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    }

    private ExecutionPlanDefinitionBuilder executionPlanDefinitionBuilder(ObjectMapper objectMapper) {
        SearchBrowserProperties searchBrowserProperties = new SearchBrowserProperties();
        searchBrowserProperties.setEnabled(false);
        SearchProperties searchProperties = new SearchProperties();
        searchProperties.setMode("HTTP_ONLY");
        CollectorProperties collectorProperties = new CollectorProperties();
        collectorProperties.setMaxPagesPerCompetitor(1);
        CollectorPlanTemplateFactory collectorPlanTemplateFactory = new CollectorPlanTemplateFactory(
                new PromptTemplateService(objectMapper),
                searchBrowserProperties,
                searchProperties,
                collectorProperties,
                new SearchPolicyResolver()
        );
        SourceDiscoveryService sourceDiscoveryService = (competitorName, providedUrls, requestedScopes) -> List.of(
                SourcePlan.builder()
                        .sourceType("OFFICIAL")
                        .urls(providedUrls == null || providedUrls.isEmpty()
                                ? List.of("https://www.notion.so")
                                : providedUrls)
                        .sourceUrls(providedUrls == null || providedUrls.isEmpty()
                                ? List.of("https://www.notion.so")
                                : providedUrls)
                        .notes("P2 smoke 本地规则来源")
                        .candidates(List.of())
                        .build()
        );
        return new ExecutionPlanDefinitionBuilder(
                mock(AnalysisSchemaRepository.class),
                sourceDiscoveryService,
                new SourceCandidateRanker(),
                objectMapper,
                collectorPlanTemplateFactory
        );
    }

    private TaskReplayResponse replayFromEvents(ObjectMapper objectMapper, List<TaskWorkflowEvent> workflowEvents) {
        TaskPlan activePlan = TaskPlan.builder()
                .id(31L)
                .taskId(88L)
                .planVersion(1)
                .branchKey("root")
                .active(true)
                .build();
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(88L)).thenReturn(List.of(activePlan));
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(88L)).thenReturn(Optional.of(activePlan));
        when(taskWorkflowEventRepository.findAll()).thenReturn(workflowEvents);
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(88L)).thenReturn(List.of());
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(88L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(88L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(88L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(88L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("P2 smoke")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        return new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        ).getTaskReplay(88L);
    }
}
