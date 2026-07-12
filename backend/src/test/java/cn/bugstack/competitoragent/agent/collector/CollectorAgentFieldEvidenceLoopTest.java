package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.collection.CollectionDeadlineContext;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutionReport;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.EvidenceRepairPlan;
import cn.bugstack.competitoragent.search.EvidenceRepairState;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.tavily.FieldEvidenceQueryExecutionAudit;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectorAgentFieldEvidenceLoopTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    private final AgentContextAssembler agentContextAssembler = mock(AgentContextAssembler.class);
    private final SearchExecutionCoordinator searchExecutionCoordinator = mock(SearchExecutionCoordinator.class);
    private final CollectionExecutionCoordinator collectionExecutionCoordinator = mock(CollectionExecutionCoordinator.class);
    private final TaskRetrievalIndexService taskRetrievalIndexService = mock(TaskRetrievalIndexService.class);

    @Test
    void shouldReleaseFieldEvidenceClaimsWhenCollectorAttemptFailsBeforeRetry() {
        String claimKey = "fieldEvidence.executedFingerprints::94::bilibili-open-platform";
        String queryFingerprint = "q-retry";
        AtomicInteger searchAttempts = new AtomicInteger();
        List<CollectionDeadlineContext> searchDeadlineContexts = new ArrayList<>();
        List<CollectionDeadlineContext> collectionDeadlineContexts = new ArrayList<>();

        doAnswer(invocation -> {
            searchDeadlineContexts.add(invocation.getArgument(4));
            Map<String, Set<String>> claimRegistry = invocation.getArgument(2);
            boolean claimed = claimRegistry
                    .computeIfAbsent(claimKey, ignored -> ConcurrentHashMap.newKeySet())
                    .add(queryFingerprint);
            searchAttempts.incrementAndGet();
            if (!claimed) {
                return SearchExecutionResult.builder()
                        .selectedTargets(List.of())
                        .executionTrace(SearchExecutionTrace.builder()
                                .fieldEvidenceQueryPlannedCount(1)
                                .fieldEvidenceQueryExecutedCount(0)
                                .fieldEvidenceQuerySkippedCount(1)
                                .fieldEvidenceQuerySkipReasons(Map.of("SKIPPED_CROSS_NODE_DEDUP", 1))
                                .build())
                        .reasoningSummary("deduped by previous failed attempt")
                        .build();
            }
            SourceCandidate candidate = SourceCandidate.builder()
                    .url("https://open.bilibili.com")
                    .title("bilibili open platform")
                    .sourceType("OFFICIAL")
                    .sourceUrls(List.of("https://open.bilibili.com"))
                    .fieldEvidenceQueryFingerprint(queryFingerprint)
                    .build();
            return SearchExecutionResult.builder()
                    .sourceCandidates(List.of(candidate))
                    .selectedTargets(List.of(SearchCollectionTarget.builder()
                            .candidate(candidate)
                            .build()))
                    .executionTrace(SearchExecutionTrace.builder()
                            .fieldEvidenceQueryPlannedCount(1)
                            .fieldEvidenceQueryExecutedCount(1)
                            .fieldEvidenceQuerySkippedCount(0)
                            .tavilyFastLaneAudit(TavilyFastLaneAudit.builder()
                                    .fieldEvidenceQueryExecutions(List.of(FieldEvidenceQueryExecutionAudit.builder()
                                            .queryFingerprint(queryFingerprint)
                                            .status("SUCCESS")
                                            .build()))
                                    .build())
                            .build())
                    .reasoningSummary("claimed q-retry")
                    .build();
        }).when(searchExecutionCoordinator)
                .execute(any(CollectorNodeConfig.class), eq(94L), any(), any(), any(CollectionDeadlineContext.class));

        doAnswer(invocation -> {
            collectionDeadlineContexts.add(invocation.getArgument(6));
            return CollectionExecutionReport.builder()
                    .status("FAILED")
                    .results(List.of(CollectionExecutionResult.builder()
                            .success(false)
                            .status("FAILED")
                            .resourceLocator("https://open.bilibili.com")
                            .sourceUrls(List.of("https://open.bilibili.com"))
                            .errorMessage("NAVIGATION_SHELL")
                            .build()))
                    .build();
        }).when(collectionExecutionCoordinator)
                .execute(any(), any(), any(), eq("bilibili-open-platform"), anyList(), any(), any(CollectionDeadlineContext.class));

        when(collectionExecutionCoordinator.summarize(anyList())).thenAnswer(invocation -> CollectionExecutionReport.builder()
                .status("FAILED")
                .results(invocation.getArgument(0))
                .sourceUrls(List.of("https://open.bilibili.com"))
                .build());

        CollectorAgent agent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchExecutionCoordinator,
                collectionExecutionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        AgentContext context = contextForRetryClaimRelease();

        AgentResult firstAttempt = agent.execute(context);
        AgentResult secondAttempt = agent.execute(context);

        assertThat(firstAttempt.getStatus()).isEqualTo(TaskNodeStatus.FAILED);
        assertThat(secondAttempt.getStatus()).isEqualTo(TaskNodeStatus.FAILED);
        assertThat(context.getFieldEvidenceFingerprintClaims().getOrDefault(claimKey, Set.of()))
                .doesNotContain(queryFingerprint);
        assertThat(searchAttempts).hasValue(2);
        assertThat(searchDeadlineContexts)
                .hasSize(2)
                .allSatisfy(deadlineContext -> assertThat(deadlineContext).isNotNull());
        assertThat(collectionDeadlineContexts)
                .hasSize(2)
                .allSatisfy(deadlineContext -> assertThat(deadlineContext).isNotNull());
        verify(collectionExecutionCoordinator, times(2))
                .execute(any(), any(), any(), eq("bilibili-open-platform"), anyList(), any(), any(CollectionDeadlineContext.class));
    }

    @Test
    void shouldRunSecondRoundOnlyForUnfinishedFieldEvidencePlan() throws Exception {
        List<DimensionEvidencePlan> executedPlans = new ArrayList<>();
        List<String> executedClaimScopes = new ArrayList<>();
        List<CollectionDeadlineContext> searchDeadlineContexts = new ArrayList<>();
        List<CollectionDeadlineContext> collectionDeadlineContexts = new ArrayList<>();
        AtomicInteger collectionRounds = new AtomicInteger();

        doAnswer(invocation -> {
            CollectorNodeConfig config = invocation.getArgument(0);
            searchDeadlineContexts.add(invocation.getArgument(4));
            executedPlans.add(config.getDimensionEvidencePlan());
            executedClaimScopes.add(config.getFieldEvidenceClaimScope());
            int round = executedPlans.size();
            String url = round == 1
                    ? "https://open.bilibili.com"
                    : "https://open.bilibili.com/doc/4/feb66f99";
            SourceCandidate candidate = SourceCandidate.builder()
                    .url(url)
                    .title(round == 1 ? "open platform" : "API docs")
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .sourceUrls(List.of(url))
                    .fieldName("coreFeatures")
                    .evidencePathKey("DOCS_API_GUIDE")
                    .queryIntent("API_DOCS")
                    .fieldEvidenceQueryFingerprint("field-query-" + round)
                    .build();
            return SearchExecutionResult.builder()
                    .sourceCandidates(List.of(candidate))
                    .selectedTargets(List.of(SearchCollectionTarget.builder()
                            .candidate(candidate)
                            .build()))
                    .build();
        }).when(searchExecutionCoordinator)
                .execute(any(CollectorNodeConfig.class), eq(66L), any(), any(), any(CollectionDeadlineContext.class));

        doAnswer(invocation -> {
            collectionDeadlineContexts.add(invocation.getArgument(6));
            int round = collectionRounds.incrementAndGet();
            if (round == 1) {
                return CollectionExecutionReport.builder()
                        .status("SUCCESS")
                        .results(List.of(CollectionExecutionResult.builder()
                                .success(true)
                                .status("SUCCESS")
                                .resourceLocator("https://open.bilibili.com")
                                .sourceUrls(List.of("https://open.bilibili.com"))
                                .publicEvidenceRecoveryFieldName("coreFeatures")
                                .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                                .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                        .state(EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                                        .build())
                                .build()))
                        .build();
            }
            return CollectionExecutionReport.builder()
                    .status("SUCCESS")
                    .results(List.of(CollectionExecutionResult.builder()
                            .success(true)
                            .status("SUCCESS")
                            .resourceLocator("https://open.bilibili.com/doc/4/feb66f99")
                            .content("""
                                    bilibili open platform docs provide API access details, SDK integration guidance,
                                    auth flow, callback configuration, app management and capability onboarding steps.
                                    the page has enough body text to support the coreFeatures field judgement.
                                    """)
                            .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                            .publicEvidenceRecoveryFieldName("coreFeatures")
                            .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                            .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                    .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                                    .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                                    .build())
                            .build()))
                    .build();
        }).when(collectionExecutionCoordinator)
                .execute(any(), any(), any(), eq("bilibili-docs"), anyList(), any(), any(CollectionDeadlineContext.class));

        when(collectionExecutionCoordinator.summarize(anyList())).thenAnswer(invocation -> CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(invocation.getArgument(0))
                .sourceUrls(List.of())
                .build());
        when(evidenceRepository.save(any(EvidenceSource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CollectorAgent agent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchExecutionCoordinator,
                collectionExecutionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );

        AgentResult result = agent.execute(contextWithDimensionEvidencePlan());

        JsonNode output = objectMapper.readTree(result.getOutputData());
        JsonNode coreFeatures = findField(output.path("dimensionEvidencePlan"), "coreFeatures");

        assertThat(executedPlans).hasSize(2);
        assertThat(executedPlans.get(1).allPlannedQueries())
                .extracting(query -> query.getEvidencePathKey())
                .containsOnly("DOCS_API_GUIDE");
        assertThat(executedClaimScopes).containsExactly(null, "recollection-2");
        assertThat(searchDeadlineContexts).hasSize(2);
        assertThat(collectionDeadlineContexts).hasSize(2);
        assertThat(searchDeadlineContexts)
                .extracting(CollectionDeadlineContext::hardDeadlineEpochMillis)
                .doesNotContainNull()
                .containsOnly(searchDeadlineContexts.get(0).hardDeadlineEpochMillis());
        assertThat(searchDeadlineContexts)
                .extracting(CollectionDeadlineContext::drainGraceMillis)
                .containsOnly(searchDeadlineContexts.get(0).drainGraceMillis());
        assertThat(collectionDeadlineContexts)
                .extracting(CollectionDeadlineContext::hardDeadlineEpochMillis)
                .containsOnly(searchDeadlineContexts.get(0).hardDeadlineEpochMillis());
        assertThat(output.path("fieldEvidenceLoopRounds").asInt()).isEqualTo(2);
        assertThat(output.path("fieldEvidenceRecollectionTriggered").asBoolean()).isTrue();
        assertThat(coreFeatures.path("status").asText()).isEqualTo("SUFFICIENT");
        assertThat(coreFeatures.path("lastRepairState").asText()).isEqualTo("REPAIR_FIELD_PATH_COMPLETED");
        assertThat(output.path("sourceUrls").toString()).contains("https://open.bilibili.com/doc/4/feb66f99");
        verify(searchExecutionCoordinator, times(2))
                .execute(any(CollectorNodeConfig.class), eq(66L), any(), any(), any(CollectionDeadlineContext.class));
        verify(collectionExecutionCoordinator, times(2))
                .execute(any(), any(), any(), eq("bilibili-docs"), anyList(), any(), any(CollectionDeadlineContext.class));
    }

    private AgentContext contextWithDimensionEvidencePlan() {
        return AgentContext.builder()
                .taskId(66L)
                .taskName("Task66 field evidence loop")
                .currentNodeName("collect_sources_field_loop")
                .currentNodeConfig("""
                        {
                          "competitorName": "bilibili-docs",
                          "competitorUrls": ["https://open.bilibili.com"],
                          "sourceType": "DOCS",
                          "verifyCandidates": false,
                          "browserSearchEnabled": false,
                          "dimensionEvidencePlan": {
                            "competitorName": "bilibili-docs",
                            "maxCollectionRounds": 2,
                            "fieldCoverages": [
                              {
                                "fieldName": "coreFeatures",
                                "status": "NOT_STARTED",
                                "minimumAttemptedPaths": 1,
                                "minDistinctEvidenceCount": 1,
                                "evidencePaths": [
                                  {
                                    "pathKey": "DOCS_API_GUIDE",
                                    "sourceTypes": ["DOCS", "OFFICIAL"],
                                    "queryIntents": ["API_DOCS"],
                                    "required": true
                                  }
                                ],
                                "plannedQueries": [
                                  {
                                    "fieldName": "coreFeatures",
                                    "evidencePathKey": "DOCS_API_GUIDE",
                                    "queryIntent": "API_DOCS",
                                    "query": "bilibili open platform api docs",
                                    "queryFingerprint": "q1",
                                    "reason": "field-oriented search"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """)
                .build();
    }

    private AgentContext contextForRetryClaimRelease() {
        return AgentContext.builder()
                .taskId(94L)
                .taskName("Task94 retry claim release")
                .currentNodeName("collect_sources_02_01")
                .currentNodeConfig("""
                        {
                          "competitorName": "bilibili-open-platform",
                          "competitorUrls": ["https://open.bilibili.com"],
                          "sourceType": "OFFICIAL",
                          "verifyCandidates": false,
                          "browserSearchEnabled": false
                        }
                        """)
                .build();
    }

    private JsonNode findField(JsonNode dimensionEvidencePlan, String fieldName) {
        for (JsonNode field : dimensionEvidencePlan.path("fieldCoverages")) {
            if (fieldName.equals(field.path("fieldName").asText())) {
                return field;
            }
        }
        throw new AssertionError("field not found: " + fieldName);
    }
}
