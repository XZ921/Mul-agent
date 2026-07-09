package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.capability.AgentCapabilityRegistry;
import cn.bugstack.competitoragent.agent.capability.SpringAgentCapabilityRegistry;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.AnalyzerSuggestionAssembler;
import cn.bugstack.competitoragent.orchestration.DecisionExecutorAdapter;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyService;
import cn.bugstack.competitoragent.orchestration.ExtractorSuggestionAssembler;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionAdapter;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.orchestration.WriterSuggestionAssembler;
import cn.bugstack.competitoragent.report.EvidenceQueryService;
import cn.bugstack.competitoragent.report.ReportDiagnosisAssembler;
import cn.bugstack.competitoragent.report.ReportService;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
import cn.bugstack.competitoragent.task.SharedNodeOutputProjector;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import cn.bugstack.competitoragent.workflow.CollectorEvidenceReadiness;
import cn.bugstack.competitoragent.workflow.DagExecutor;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.runtime.DynamicPlanAppender;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeEventEmitter;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeStateRefresher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闃舵 1 闄嶇骇濂戠害闆嗘垚娴嬭瘯銆?
 * 杩欓噷涓嶅啀鍙湅鍗曚釜 policy 鎴栧崟涓?service锛岃€屾槸鎶?collector quorum銆?
 * 鎶ュ憡 degraded ready 浠ュ強 task view 鍙煡鐪嬭涔夋斁鍒板悓涓€鏉￠摼璺笅楠岃瘉銆?
 */
class StageOneDegradedContractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldUnifyStageOneDegradedContractAcrossDagReportAndTaskView() throws Exception {
        Long taskId = 1801L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();
        TaskNode official = completedCollector(taskId, 18011L, "collect_sources_01_01", "OFFICIAL",
                List.of(
                        "https://www.notion.so/product/ai",
                        "https://notion.so/customers",
                        "https://www.notion.so/security"
                ), TaskNodeStatus.SUCCESS, true);
        TaskNode pricing = completedCollector(taskId, 18012L, "collect_sources_01_03", "PRICING",
                List.of("https://www.notion.so/pricing"), TaskNodeStatus.SUCCESS_DEGRADED, false);
        TaskNode review = completedCollector(taskId, 18013L, "collect_sources_01_04", "REVIEW",
                List.of(
                        "https://www.g2.com/products/notion-ai/reviews",
                        "https://www.capterra.com/p/221913/Notion/reviews/"
                ), TaskNodeStatus.SUCCESS, true);
        TaskNode extractor = extractorNode(taskId, 18014L,
                List.of("collect_sources_01_01", "collect_sources_01_03", "collect_sources_01_04"));
        List<TaskNode> nodes = List.of(official, pricing, review, extractor);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        stubDagRepositories(taskRepository, nodeRepository, task, nodes);

        AgentContext context = AgentContext.builder().taskId(taskId).taskName("stage1-degraded-positive").build();
        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new QuorumAwareExtractorAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, context);

        CollectorEvidenceReadiness readiness = collectorReadiness(context);
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.degraded()).isTrue();
        assertThat(readiness.reason()).isEqualTo("STAGE1_COLLECTOR_QUORUM_READY");
        assertThat(readiness.missingFamilies()).contains("PRICING", "DOCS");
        assertThat(readiness.auditFlags()).contains("OPTIONAL_PRICING_NOT_READY");
        assertThat(readiness.sourceUrls()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(extractor.getStatus()).isEqualTo(TaskNodeStatus.SUCCESS);
        assertThat(task.getStatus()).isEqualTo(AnalysisTaskStatus.SUCCESS);

        TaskResponse taskResponse = newTaskNodeViewAssembler().toTaskResponse(task, nodes);
        assertThat(taskResponse.getCanViewReport()).isTrue();
        assertThat(taskResponse.getCanViewDraftReport()).isFalse();

        ReportService reportService = newReportService(nodeRepository);
        List<ReportResponse.EvidenceInfo> evidenceInfos = List.of(
                traceableEvidence("E1801-1", "https://www.notion.so/product/ai", "OFFICIAL", "www.notion.so"),
                traceableEvidence("E1801-2", "https://notion.so/customers", "OFFICIAL", "notion.so"),
                traceableEvidence("E1801-3", "https://www.notion.so/security", "OFFICIAL", "www.notion.so"),
                traceableEvidence("E1801-4", "https://www.g2.com/products/notion-ai/reviews", "REVIEW", "www.g2.com"),
                traceableEvidence("E1801-5", "https://www.capterra.com/p/221913/Notion/reviews/", "REVIEW", "www.capterra.com")
        );
        stubReportDependencies(reportService, nodeRepository, taskId, nodes,
                degradedCandidateReport(taskId, evidenceInfos.size(), "https://www.notion.so/pricing"),
                evidenceInfos);

        ReportResponse reportResponse = reportService.getReport(taskId);

        assertThat(reportResponse.getDeliverySummary()).isNotNull();
        assertThat(reportResponse.getDeliverySummary().getReadyForDelivery()).isTrue();
        assertThat(reportResponse.getDeliverySummary().getDeliveryStatus()).isEqualTo("DEGRADED_READY");
        assertThat(reportResponse.getDeliverySummary().getEvidenceGapCount()).isZero();
        assertThat(reportResponse.getSourceUrls()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void shouldKeepStageOneContractClosedWhenTraceableSourceRedlineFails() throws Exception {
        Long taskId = 1802L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();
        TaskNode official = completedCollector(taskId, 18021L, "collect_sources_01_01", "OFFICIAL",
                List.of(
                        "https://www.linear.app",
                        "https://linear.app/features"
                ), TaskNodeStatus.SUCCESS, true);
        TaskNode pricing = completedCollector(taskId, 18022L, "collect_sources_01_03", "PRICING",
                List.of("https://www.linear.app/pricing"), TaskNodeStatus.SUCCESS, true);
        TaskNode review = completedCollector(taskId, 18023L, "collect_sources_01_04", "REVIEW",
                List.of(
                        "https://linear.app/reviews",
                        "https://www.linear.app/customers"
                ), TaskNodeStatus.SUCCESS, true);
        TaskNode extractor = extractorNode(taskId, 18024L,
                List.of("collect_sources_01_01", "collect_sources_01_03", "collect_sources_01_04"));
        List<TaskNode> nodes = List.of(official, pricing, review, extractor);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        stubDagRepositories(taskRepository, nodeRepository, task, nodes);

        AgentContext context = AgentContext.builder().taskId(taskId).taskName("stage1-degraded-negative").build();
        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new QuorumAwareExtractorAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, context);

        CollectorEvidenceReadiness readiness = collectorReadiness(context);
        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.reason()).isEqualTo("STAGE1_COLLECTOR_QUORUM_NOT_READY");
        assertThat(readiness.auditFlags()).contains("SOURCE_URLS_REDLINE_NOT_READY");
        assertThat(extractor.getStatus()).isEqualTo(TaskNodeStatus.SKIPPED);
        assertThat(task.getStatus()).isEqualTo(AnalysisTaskStatus.FAILED);

        TaskResponse taskResponse = newTaskNodeViewAssembler().toTaskResponse(task, nodes);
        assertThat(taskResponse.getCanViewReport()).isFalse();
        assertThat(taskResponse.getCanViewDraftReport()).isFalse();

        ReportService reportService = newReportService(nodeRepository);
        List<ReportResponse.EvidenceInfo> evidenceInfos = List.of(
                traceableEvidence("E1802-1", "https://www.linear.app", "OFFICIAL", "www.linear.app"),
                traceableEvidence("E1802-2", "https://linear.app/features", "OFFICIAL", "linear.app"),
                traceableEvidence("E1802-3", "https://www.linear.app/pricing", "PRICING", "www.linear.app"),
                traceableEvidence("E1802-4", "https://linear.app/reviews", "REVIEW", "linear.app"),
                traceableEvidence("E1802-5", "https://www.linear.app/customers", "REVIEW", "www.linear.app")
        );
        stubReportDependencies(reportService, nodeRepository, taskId, nodes,
                degradedCandidateReport(taskId, evidenceInfos.size(), "https://www.linear.app/pricing"),
                evidenceInfos);

        ReportResponse reportResponse = reportService.getReport(taskId);

        assertThat(reportResponse.getDeliverySummary()).isNotNull();
        assertThat(reportResponse.getDeliverySummary().getReadyForDelivery()).isFalse();
        assertThat(reportResponse.getDeliverySummary().getDeliveryStatus()).isEqualTo("NEEDS_EVIDENCE");
    }

    /**
     * 杩欓噷鏄惧紡鏋勯€犲彧鏈夊寮哄瓧娈电己鍙ｇ殑鎶ュ憡锛?
     * 鐢ㄦ潵楠岃瘉 stage1 degraded ready 鐪熸鍙彈鏍稿績瀛楁鍜屽彲杩芥函绾㈢嚎绾︽潫銆?
     */
    private Report degradedCandidateReport(Long taskId, int evidenceCount, String deferredSourceUrl) {
        return Report.builder()
                .id(taskId)
                .taskId(taskId)
                .title("stage1 degraded candidate")
                .content("# Report")
                .summary("summary")
                .qualityScore(65)
                .qualityPassed(false)
                .qualityIssues("""
                        [
                          {
                            "type":"missing_evidence",
                            "section":"pricing",
                            "severity":"WARNING",
                            "level":"WARNING",
                            "evidenceBasis":"pricing evidence is deferred in stage1",
                            "sourceUrls":["%s"],
                            "suggestion":"supplement pricing evidence later"
                          }
                        ]
                        """.formatted(deferredSourceUrl))
                .evidenceCount(evidenceCount)
                .build();
    }

    private void stubDagRepositories(AnalysisTaskRepository taskRepository,
                                     TaskNodeRepository nodeRepository,
                                     AnalysisTask task,
                                     List<TaskNode> nodes) {
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId())).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubReportDependencies(ReportService reportService,
                                        TaskNodeRepository nodeRepository,
                                        Long taskId,
                                        List<TaskNode> nodes,
                                        Report report,
                                        List<ReportResponse.EvidenceInfo> evidenceInfos) throws Exception {
        ReportRepository reportRepository = readField(reportService, "reportRepository", ReportRepository.class);
        CompetitorKnowledgeRepository knowledgeRepository =
                readField(reportService, "knowledgeRepository", CompetitorKnowledgeRepository.class);
        EvidenceQueryService evidenceQueryService =
                readField(reportService, "evidenceQueryService", EvidenceQueryService.class);

        when(reportRepository.findByTaskId(taskId)).thenReturn(Optional.of(report));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(taskId)).thenReturn(List.of());
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(evidenceQueryService.listTaskEvidence(taskId)).thenReturn(evidenceInfos);
    }

    private ReportService newReportService(TaskNodeRepository nodeRepository) {
        ReportRepository reportRepository = mock(ReportRepository.class);
        EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
        CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
        EvidenceQueryService evidenceQueryService = mock(EvidenceQueryService.class);
        ReportDiagnosisAssembler reportDiagnosisAssembler =
                new ReportDiagnosisAssembler(objectMapper, new EvidenceQueryService(mock(EvidenceSourceRepository.class), objectMapper));
        return new ReportService(
                reportRepository,
                evidenceRepository,
                knowledgeRepository,
                nodeRepository,
                evidenceQueryService,
                reportDiagnosisAssembler,
                objectMapper
        );
    }

    private TaskNodeViewAssembler newTaskNodeViewAssembler() {
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);
        when(taskRecoveryService.getTaskSnapshotOrRebuild(anyLong())).thenReturn(Optional.empty());
        return new TaskNodeViewAssembler(
                mock(AiCallAuditRecordRepository.class),
                mock(TaskPlanRepository.class),
                taskRecoveryService,
                objectMapper
        );
    }

    private CollectorEvidenceReadiness collectorReadiness(AgentContext context) throws Exception {
        return objectMapper.readValue(context.getSharedOutput("collector_evidence_readiness"), CollectorEvidenceReadiness.class);
    }

    private ReportResponse.EvidenceInfo traceableEvidence(String evidenceId,
                                                          String url,
                                                          String sourceType,
                                                          String domain) {
        return new ReportResponse.EvidenceInfo(
                evidenceId,
                "Traceable source",
                url,
                "snippet",
                "stage1 competitor",
                null,
                sourceType,
                "SEARCH",
                domain,
                null,
                null,
                0.9,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );
    }

    private TaskNode extractorNode(Long taskId, Long nodeId, List<String> dependsOn) {
        return TaskNode.builder()
                .id(nodeId)
                .taskId(taskId)
                .nodeName("extract_schema")
                .displayName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn(toJsonArray(dependsOn))
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(4)
                .build();
    }

    private TaskNode completedCollector(Long taskId,
                                        Long nodeId,
                                        String nodeName,
                                        String sourceType,
                                        List<String> sourceUrls,
                                        TaskNodeStatus status,
                                        boolean readyForQuorum) {
        return TaskNode.builder()
                .id(nodeId)
                .taskId(taskId)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(status)
                .executionOrder(collectorExecutionOrder(sourceType))
                .outputData("""
                        {
                          "sourceType": "%s",
                          "readyForQuorum": %s,
                          "sourceUrls": %s,
                          "degradationReasons": %s
                        }
                        """.formatted(
                        sourceType,
                        readyForQuorum,
                        toJsonArray(sourceUrls),
                        status == TaskNodeStatus.SUCCESS_DEGRADED || status == TaskNodeStatus.FAILED
                                ? "[\"HARD_DEADLINE_REACHED\"]"
                                : "[]"))
                .build();
    }

    private int collectorExecutionOrder(String sourceType) {
        return switch (sourceType) {
            case "OFFICIAL" -> 0;
            case "DOCS" -> 1;
            case "PRICING" -> 2;
            case "REVIEW" -> 3;
            default -> 0;
        };
    }

    private String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .toList()
                .toString();
    }

    private TaskExecutionLockService allowingNodeLockService() {
        TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);
        when(lockService.tryAcquireNodeExecutionLock(any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(lockService.releaseNodeExecutionLock(any(), any(), any())).thenReturn(Boolean.TRUE);
        return lockService;
    }

    private DagExecutor newDagExecutor(TaskNodeRepository nodeRepository,
                                       AnalysisTaskRepository taskRepository,
                                       List<Agent> agents,
                                       TaskSnapshotCacheService snapshotCacheService,
                                       TaskExecutionLockService lockService) {
        TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        return new DagExecutor(
                nodeRepository,
                taskRepository,
                registryOf(agents),
                objectMapper,
                snapshotCacheService,
                lockService,
                taskEventPublisher,
                agentLogService,
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                new RuntimeStateRefresher(taskRepository, nodeRepository, snapshotCacheService, taskEventPublisher),
                new RuntimeEventEmitter(taskEventPublisher, agentLogService, objectMapper),
                new DynamicPlanAppender(
                        taskRepository,
                        nodeRepository,
                        mock(DynamicTaskGraphService.class),
                        mock(TaskPlanRepository.class),
                        objectMapper,
                        mock(OrchestrationDecisionService.class),
                        mock(DecisionPolicyService.class),
                        mock(DecisionExecutorAdapter.class),
                        mock(OrchestrationTraceService.class)),
                mock(TaskQuotaCoordinator.class),
                new ExtractorSuggestionAssembler(objectMapper),
                new AnalyzerSuggestionAssembler(objectMapper),
                new WriterSuggestionAssembler(objectMapper),
                new OrchestrationDecisionService(new OrchestrationDecisionAdapter()),
                mock(OrchestrationTraceService.class),
                List.<SharedNodeOutputProjector>of()
        );
    }

    private AgentCapabilityRegistry registryOf(List<Agent> agents) {
        return new SpringAgentCapabilityRegistry(agents);
    }

    private <T> T readField(Object target, String fieldName, Class<T> type) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    /**
     * 鐢ㄨ交閲?Extractor 鏇夸唬鐪熷疄鎶藉彇閫昏緫锛屼笓娉ㄩ獙璇?DagExecutor 鏄惁鐪熺殑鎶?stage1 collector readiness
     * 褰撴垚鍞竴鏀捐濂戠害銆?
     */
    private static final class QuorumAwareExtractorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.EXTRACTOR;
        }

        @Override
        public String getName() {
            return "quorum-aware-extractor";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            String readiness = context.getSharedOutput("collector_evidence_readiness");
            return AgentResult.builder()
                    .status(readiness == null || !readiness.contains("STAGE1_COLLECTOR_QUORUM_READY")
                            ? TaskNodeStatus.FAILED
                            : TaskNodeStatus.SUCCESS)
                    .outputData("{\"sourceUrls\":[\"https://www.example.com\"]}")
                    .errorMessage(readiness == null ? "missing collector evidence readiness" : null)
                    .build();
        }
    }
}
