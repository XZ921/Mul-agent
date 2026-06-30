package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutionReport;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.EvidenceRepairPlan;
import cn.bugstack.competitoragent.search.EvidenceRepairState;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
    void shouldRunSecondRoundOnlyForUnfinishedFieldEvidencePlan() throws Exception {
        List<CollectorNodeConfig> executedConfigs = new ArrayList<>();
        doAnswer(invocation -> {
            CollectorNodeConfig config = invocation.getArgument(0);
            executedConfigs.add(config);
            int round = executedConfigs.size();
            SourceCandidate candidate = SourceCandidate.builder()
                    .url(round == 1
                            ? "https://open.bilibili.com"
                            : "https://open.bilibili.com/doc/4/feb66f99")
                    .title(round == 1 ? "开放平台入口" : "API 文档")
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .sourceUrls(List.of(round == 1
                            ? "https://open.bilibili.com"
                            : "https://open.bilibili.com/doc/4/feb66f99"))
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
        }).when(searchExecutionCoordinator).execute(any(CollectorNodeConfig.class), any());

        when(collectionExecutionCoordinator.execute(any(), any(), any(), eq("哔哩哔哩"), anyList(), any()))
                .thenReturn(CollectionExecutionReport.builder()
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
                        .build())
                .thenReturn(CollectionExecutionReport.builder()
                        .status("SUCCESS")
                        .results(List.of(CollectionExecutionResult.builder()
                                .success(true)
                                .status("SUCCESS")
                                .resourceLocator("https://open.bilibili.com/doc/4/feb66f99")
                                .content("""
                                        哔哩哔哩开放平台开发者文档提供 API 接入说明、SDK 集成指南、授权流程、回调配置、
                                        应用管理和能力开通步骤。该文档面向开发者解释如何通过开放平台调用内容、账号和互动能力，
                                        并提供接口参数、错误码、示例代码和接入注意事项，足以支撑 coreFeatures 字段判断。
                                        """)
                                .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                                .publicEvidenceRecoveryFieldName("coreFeatures")
                                .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                                .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                        .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                                        .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                                        .build())
                                .build()))
                        .build());
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
        assertThat(executedConfigs).hasSize(2);
        assertThat(executedConfigs.get(1).getDimensionEvidencePlan().allPlannedQueries())
                .extracting(query -> query.getEvidencePathKey())
                .containsOnly("DOCS_API_GUIDE");
        assertThat(output.path("fieldEvidenceLoopRounds").asInt()).isEqualTo(2);
        assertThat(output.path("fieldEvidenceRecollectionTriggered").asBoolean()).isTrue();
        assertThat(coreFeatures.path("status").asText()).isEqualTo("SUFFICIENT");
        assertThat(coreFeatures.path("lastRepairState").asText()).isEqualTo("REPAIR_FIELD_PATH_COMPLETED");
        assertThat(output.path("sourceUrls").toString()).contains("https://open.bilibili.com/doc/4/feb66f99");
    }

    private AgentContext contextWithDimensionEvidencePlan() {
        return AgentContext.builder()
                .taskId(66L)
                .taskName("Task66 field evidence loop")
                .currentNodeName("collect_sources_field_loop")
                .currentNodeConfig("""
                        {
                          "competitorName": "哔哩哔哩",
                          "competitorUrls": ["https://open.bilibili.com"],
                          "sourceType": "DOCS",
                          "verifyCandidates": false,
                          "browserSearchEnabled": false,
                          "dimensionEvidencePlan": {
                            "competitorName": "哔哩哔哩",
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
                                    "query": "哔哩哔哩 开放平台 API 官方文档",
                                    "queryFingerprint": "q1",
                                    "reason": "字段定向查询"
                                  }
                                ]
                              }
                            ]
                          }
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
