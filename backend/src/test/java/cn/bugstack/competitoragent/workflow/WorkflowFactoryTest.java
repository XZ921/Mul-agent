package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.orchestration.CollaborationGoalAssembler;
import cn.bugstack.competitoragent.orchestration.CollaborationPlanService;
import cn.bugstack.competitoragent.orchestration.CollaborationTraceService;
import cn.bugstack.competitoragent.orchestration.InitialPlanReviewService;
import cn.bugstack.competitoragent.repository.AnalysisSchemaRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProperties;
import cn.bugstack.competitoragent.source.HeuristicSourceDiscoveryService;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class WorkflowFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptTemplateService promptTemplateService = new PromptTemplateService(objectMapper);

    @Test
    void shouldDelegatePlanConstructionToDedicatedCollaborators() {
        List<String> fieldTypeNames = Arrays.stream(WorkflowFactory.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .toList();
        List<String> builderFieldTypeNames = Arrays.stream(ExecutionPlanDefinitionBuilder.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .toList();
        List<String> methodNames = Arrays.stream(WorkflowFactory.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertTrue(fieldTypeNames.contains(ExecutionPlanDefinitionBuilder.class.getSimpleName()));
        assertTrue(fieldTypeNames.contains(WorkflowPlanAssembler.class.getSimpleName()));
        assertTrue(builderFieldTypeNames.contains(CollectorPlanTemplateFactory.class.getSimpleName()));
        assertFalse(methodNames.contains("buildCollectorNodeConfig"));
        assertFalse(methodNames.contains("buildPlanInternal"));
        assertFalse(methodNames.contains("buildFormalStages"));
    }

    @Test
    void shouldEmbedSourceCandidatesIntoCollectorNodeConfig() throws Exception {
        WorkflowFactory workflowFactory = buildWorkflowFactory(buildBrowserProperties());
        AnalysisTask task = AnalysisTask.builder()
                .taskName("test")
                .subjectProduct("subject")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "产品文档")))
                .build();

        WorkflowPlan plan = workflowFactory.buildPlan(task);
        WorkflowPlan.WorkflowPlanNode collectNode = plan.getNodes().stream()
                .filter(node -> node.getNodeName().startsWith("collect_sources"))
                .findFirst()
                .orElseThrow();

        JsonNode config = objectMapper.readTree(collectNode.getNodeConfig());
        assertTrue(config.has("sourceCandidates"));
        assertFalse(config.get("sourceCandidates").isEmpty());
        assertEquals("official", config.path("sourceFamilyKey").asText());
        assertEquals("PRIMARY_VERTICAL", config.path("sourceFamilyRole").asText());
        assertTrue(objectMapper.convertValue(config.path("primaryTools"), new TypeReference<List<String>>() {
        }).contains("WEB_SCRAPER"));
        assertTrue(objectMapper.convertValue(config.path("auxiliaryTools"), new TypeReference<List<String>>() {
        }).contains("PUBLIC_SEARCH"));
        assertTrue(objectMapper.convertValue(config.path("queryTemplates"), new TypeReference<List<String>>() {
        }).contains("search-docs-primary"));
        assertTrue(config.has("discoveryNotes"));
        assertTrue(config.has("searchRuntimePolicy"));
        assertTrue(config.has("searchExecutionPlan"));
        assertTrue(config.path("browserSearchEnabled").asBoolean());
        assertEquals("HYBRID", config.path("searchMode").asText());
        /**
         * 当前正式规划策略会优先复用 collector 全局上限；
         * 只有没有显式上限时，才会退回到 planned url / candidate 数量。
         */
        assertEquals(1, config.path("maxSearchResults").asInt());
        assertEquals(List.of("PLANNED", "BROWSER", "HTTP"),
                objectMapper.convertValue(config.path("searchFallbackOrder"), new TypeReference<List<String>>() {
                }));
        assertTrue(config.path("verifyResultPage").asBoolean());
        assertTrue(config.path("searchRuntimePolicy").path("verifyResultPage").asBoolean());
        assertEquals(3, config.path("searchRuntimePolicy").path("maxOpenResultPages").asInt());
        SearchExecutionPlan executionPlan = objectMapper.treeToValue(config.get("searchExecutionPlan"), SearchExecutionPlan.class);
        assertNotNull(executionPlan);
        assertFalse(executionPlan.getSteps().isEmpty());
        assertEquals(List.of("PLANNED", "BROWSER", "HTTP"), executionPlan.getFallbackOrder());
    }

    @Test
    void shouldDisableBrowserRuntimeSearchInCollectorNodeConfigWhenGlobalBrowserSearchDisabled() throws Exception {
        SearchBrowserProperties searchBrowserProperties = buildBrowserProperties();
        searchBrowserProperties.setEnabled(false);
        searchBrowserProperties.setVerifyResultPage(false);
        WorkflowFactory workflowFactory = buildWorkflowFactory(searchBrowserProperties);

        AnalysisTask task = AnalysisTask.builder()
                .taskName("test")
                .subjectProduct("subject")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网")))
                .build();

        WorkflowPlan plan = workflowFactory.buildPlan(task);
        WorkflowPlan.WorkflowPlanNode collectNode = plan.getNodes().stream()
                .filter(node -> node.getNodeName().startsWith("collect_sources"))
                .findFirst()
                .orElseThrow();

        JsonNode config = objectMapper.readTree(collectNode.getNodeConfig());
        assertFalse(config.path("browserSearchEnabled").asBoolean());
        assertEquals("HTTP_ONLY", config.path("searchMode").asText());
        assertFalse(config.path("verifyResultPage").asBoolean());
        assertFalse(config.path("searchRuntimePolicy").path("verifyResultPage").asBoolean());
    }

    @Test
    void shouldEmbedApprovedCollaborationPlanIntoExistingWorkflowTemplate() throws Exception {
        WorkflowFactory workflowFactory = buildWorkflowFactory(buildBrowserProperties());
        AnalysisTask task = AnalysisTask.builder()
                .id(88L)
                .taskName("协作规划任务")
                .subjectProduct("企业级 RAG 知识库")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
                .analysisDimensions(objectMapper.writeValueAsString(List.of("pricing")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页")))
                .build();

        WorkflowPlan plan = workflowFactory.buildPlan(task);

        assertThat(plan.getNodes()).anySatisfy(node -> assertThat(node.getNodeName()).isEqualTo("extract_schema"));
        assertThat(plan.getNodes()).anySatisfy(node -> assertThat(node.getNodeName()).isEqualTo("quality_check_final"));
        assertThat(plan.getNodes()).noneSatisfy(node -> assertThat(node.getNodeName()).startsWith("role-"));

        WorkflowPlan.WorkflowPlanNode extractNode = plan.getNodes().stream()
                .filter(node -> "extract_schema".equals(node.getNodeName()))
                .findFirst()
                .orElseThrow();
        JsonNode extractConfig = objectMapper.readTree(extractNode.getNodeConfig());
        assertThat(extractConfig.path("collaborationPlanId").asText()).isEqualTo("cp-task-88-v1");
        assertThat(extractConfig.path("collaborationRoleId").asText()).isEqualTo("role-extractor-01");
        assertThat(extractConfig.path("orchestratorCheckpoints").toString()).contains("after_extract_schema");
    }

    @Test
    void shouldInsertCitationNodesIntoWorkflowTemplateBeforeReviewer() throws Exception {
        WorkflowFactory workflowFactory = buildWorkflowFactory(buildBrowserProperties());
        AnalysisTask task = AnalysisTask.builder()
                .id(99L)
                .taskName("citation workflow")
                .subjectProduct("企业级 RAG 知识库")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
                .analysisDimensions(objectMapper.writeValueAsString(List.of("pricing", "security")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页")))
                .build();

        WorkflowPlan plan = workflowFactory.buildPlan(task);

        WorkflowPlan.WorkflowPlanNode writeNode = findNode(plan, "write_report");
        WorkflowPlan.WorkflowPlanNode citationNode = findNode(plan, "citation_check");
        WorkflowPlan.WorkflowPlanNode qualityNode = findNode(plan, "quality_check");
        WorkflowPlan.WorkflowPlanNode rewriteNode = findNode(plan, "rewrite_report");
        WorkflowPlan.WorkflowPlanNode citationRevisionNode = findNode(plan, "citation_check_revision");
        WorkflowPlan.WorkflowPlanNode finalQualityNode = findNode(plan, "quality_check_final");

        assertThat(citationNode.getAgentType()).isEqualTo("CITATION");
        assertThat(citationNode.getDependsOn()).containsExactly("write_report");
        assertThat(qualityNode.getDependsOn()).containsExactly("citation_check");
        assertThat(citationRevisionNode.getAgentType()).isEqualTo("CITATION");
        assertThat(citationRevisionNode.getDependsOn()).containsExactly("rewrite_report");
        assertThat(finalQualityNode.getDependsOn()).containsExactly("citation_check_revision");
        assertThat(writeNode.getExecutionOrder()).isLessThan(citationNode.getExecutionOrder());
        assertThat(citationNode.getExecutionOrder()).isLessThan(qualityNode.getExecutionOrder());
        assertThat(rewriteNode.getExecutionOrder()).isLessThan(citationRevisionNode.getExecutionOrder());
        assertThat(citationRevisionNode.getExecutionOrder()).isLessThan(finalQualityNode.getExecutionOrder());

        JsonNode citationConfig = objectMapper.readTree(citationNode.getNodeConfig());
        assertThat(citationConfig.path("sourceNode").asText()).isEqualTo("write_report");
        assertThat(citationConfig.path("mode").asText()).isEqualTo("initial");
        assertThat(citationConfig.path("minCoverageRate").asDouble()).isEqualTo(0.85d);
        assertThat(citationConfig.path("trustPolicy").asText()).isEqualTo("official-first");

        assertThat(plan.getStages()).anySatisfy(stage -> {
            if ("DELIVER".equals(stage.getStageCode())) {
                assertThat(stage.getSummary()).contains("引用");
                assertThat(stage.getDetail()).contains("Citation");
            }
        });
    }

    @Test
    void shouldDeduplicateDuplicateUrlsAcrossSourcePlans() throws Exception {
        SourceDiscoveryService sourceDiscoveryService = (competitorName, providedUrls, requestedScopes) -> List.of(
                SourcePlan.builder()
                        .sourceType("OFFICIAL")
                        .urls(List.of("https://www.notion.so/pricing"))
                        .notes("官网入口")
                        .candidates(List.of(SourceCandidate.builder()
                                .url("https://www.notion.so/pricing")
                                .title("Notion Pricing")
                                .sourceType("OFFICIAL")
                                .discoveryMethod("CONFIG")
                                .reason("官网入口")
                                .domain("www.notion.so")
                                .relevanceScore(0.95)
                                .freshnessScore(0.60)
                                .qualityScore(0.90)
                                .build()))
                        .build(),
                SourcePlan.builder()
                        .sourceType("PRICING")
                        .urls(List.of("https://www.notion.so/pricing"))
                        .notes("定价入口")
                        .candidates(List.of(SourceCandidate.builder()
                                .url("https://www.notion.so/pricing")
                                .title("Pricing")
                                .sourceType("PRICING")
                                .discoveryMethod("SEARCH")
                                .reason("定价补源")
                                .domain("www.notion.so")
                                .relevanceScore(0.94)
                                .freshnessScore(0.62)
                                .qualityScore(0.88)
                                .build()))
                        .build()
        );
        WorkflowFactory workflowFactory = buildWorkflowFactory(buildBrowserProperties(), sourceDiscoveryService);

        AnalysisTask task = AnalysisTask.builder()
                .taskName("test")
                .subjectProduct("subject")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so/pricing")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页面")))
                .build();

        WorkflowPlan plan = workflowFactory.buildPlan(task);
        List<WorkflowPlan.WorkflowPlanNode> collectNodes = plan.getNodes().stream()
                .filter(node -> node.getNodeName().startsWith("collect_sources"))
                .toList();

        assertEquals(1, collectNodes.size());
        JsonNode config = objectMapper.readTree(collectNodes.get(0).getNodeConfig());
        assertEquals(1, config.path("sourceCandidates").size());
        assertEquals(1, config.path("competitorUrls").size());
        assertTrue(collectNodes.get(0).getNotes().contains("官网入口"));
    }

    @Test
    void shouldBuildPreviewPlanWithoutInvokingLiveSearchDiscovery() throws Exception {
        PreviewAwareSourceDiscoveryService previewDiscoveryService = new PreviewAwareSourceDiscoveryService();
        WorkflowFactory workflowFactory = buildWorkflowFactory(buildBrowserProperties(), previewDiscoveryService);

        AnalysisTask task = AnalysisTask.builder()
                .taskName("preview")
                .subjectProduct("subject")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of()))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "产品文档")))
                .build();

        WorkflowPlan plan = workflowFactory.buildPreviewPlan(task);
        WorkflowPlan.WorkflowPlanNode collectNode = plan.getNodes().stream()
                .filter(node -> node.getNodeName().startsWith("collect_sources"))
                .findFirst()
                .orElseThrow();

        JsonNode config = objectMapper.readTree(collectNode.getNodeConfig());
        assertFalse(previewDiscoveryService.liveDiscoveryCalled);
        assertTrue(previewDiscoveryService.previewDiscoveryCalled);
        assertEquals(0, config.path("sourceCandidates").size());
        assertTrue(config.path("discoveryNotes").asText().contains("执行阶段"));
    }

    @Test
    void shouldWritePlanVersionMetadataIntoCreatedNodes() throws Exception {
        SearchBrowserProperties searchBrowserProperties = buildBrowserProperties();
        TaskNodeRepository nodeRepository = Mockito.mock(TaskNodeRepository.class);
        DynamicTaskGraphService dynamicTaskGraphService = Mockito.mock(DynamicTaskGraphService.class);
        when(nodeRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
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

        WorkflowFactory workflowFactory = buildWorkflowFactory(
                nodeRepository,
                searchBrowserProperties,
                new HeuristicSourceDiscoveryService(buildSearchSourceProvider(), new SourceCandidateRanker()),
                dynamicTaskGraphService
        );

        AnalysisTask task = AnalysisTask.builder()
                .id(88L)
                .taskName("create workflow")
                .subjectProduct("subject")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
                .sourceScope(objectMapper.writeValueAsString(List.of("瀹樼綉")))
                .build();

        List<TaskNode> savedNodes = workflowFactory.createWorkflow(task);

        assertEquals(31L, task.getCurrentPlanVersionId());
        assertEquals(1, task.getCurrentPlanVersion());
        assertFalse(savedNodes.isEmpty());
        assertTrue(savedNodes.stream().allMatch(node -> Long.valueOf(31L).equals(node.getPlanVersionId())));
        assertTrue(savedNodes.stream().allMatch(node -> "root".equals(node.getBranchKey())));
        assertTrue(savedNodes.stream().noneMatch(TaskNode::isDynamicNode));
    }

    private SearchBrowserProperties buildBrowserProperties() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setVerifyResultPage(true);
        return properties;
    }

    private WorkflowFactory buildWorkflowFactory(SearchBrowserProperties searchBrowserProperties) {
        return buildWorkflowFactory(
                Mockito.mock(TaskNodeRepository.class),
                searchBrowserProperties,
                new HeuristicSourceDiscoveryService(buildSearchSourceProvider(), new SourceCandidateRanker()),
                Mockito.mock(DynamicTaskGraphService.class)
        );
    }

    private WorkflowFactory buildWorkflowFactory(SearchBrowserProperties searchBrowserProperties,
                                                 SourceDiscoveryService sourceDiscoveryService) {
        return buildWorkflowFactory(
                Mockito.mock(TaskNodeRepository.class),
                searchBrowserProperties,
                sourceDiscoveryService,
                Mockito.mock(DynamicTaskGraphService.class)
        );
    }

    private WorkflowFactory buildWorkflowFactory(TaskNodeRepository nodeRepository,
                                                 SearchBrowserProperties searchBrowserProperties,
                                                 SourceDiscoveryService sourceDiscoveryService,
                                                 DynamicTaskGraphService dynamicTaskGraphService) {
        SearchProperties searchProperties = new SearchProperties();
        searchProperties.setMode("HYBRID");
        CollectorProperties collectorProperties = new CollectorProperties();
        collectorProperties.setMaxPagesPerCompetitor(5);
        CollectorPlanTemplateFactory collectorPlanTemplateFactory = new CollectorPlanTemplateFactory(
                promptTemplateService,
                searchBrowserProperties,
                searchProperties,
                collectorProperties,
                new SearchPolicyResolver()
        );
        ExecutionPlanDefinitionBuilder executionPlanDefinitionBuilder = new ExecutionPlanDefinitionBuilder(
                Mockito.mock(AnalysisSchemaRepository.class),
                sourceDiscoveryService,
                new SourceCandidateRanker(),
                objectMapper,
                collectorPlanTemplateFactory
        );
        CollaborationGoalAssembler collaborationGoalAssembler = new CollaborationGoalAssembler(objectMapper);
        CollaborationPlanService collaborationPlanService = new CollaborationPlanService();
        InitialPlanReviewService initialPlanReviewService = new InitialPlanReviewService();
        TaskWorkflowEventRepository taskWorkflowEventRepository = Mockito.mock(TaskWorkflowEventRepository.class);
        when(taskWorkflowEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CollaborationTraceService collaborationTraceService = new CollaborationTraceService(taskWorkflowEventRepository, objectMapper);
        return new WorkflowFactory(
                nodeRepository,
                new WorkflowPlanValidator(),
                objectMapper,
                dynamicTaskGraphService,
                executionPlanDefinitionBuilder,
                new WorkflowPlanAssembler(),
                collaborationGoalAssembler,
                collaborationPlanService,
                initialPlanReviewService,
                collaborationTraceService
        );
    }

    private SearchSourceProvider buildSearchSourceProvider() {
        return (competitorName, requestedScopes) -> List.of(
                SourceCandidate.builder()
                        .url("https://docs.notion.so/getting-started")
                        .title("Notion AI Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("SEARCH")
                        .reason("搜索补源命中文档入口")
                        .domain("docs.notion.so")
                        .relevanceScore(0.92)
                        .freshnessScore(0.78)
                        .qualityScore(0.90)
                        .build()
        );
    }

    private WorkflowPlan.WorkflowPlanNode findNode(WorkflowPlan plan, String nodeName) {
        return plan.getNodes().stream()
                .filter(node -> nodeName.equals(node.getNodeName()))
                .findFirst()
                .orElseThrow();
    }

    private static class PreviewAwareSourceDiscoveryService implements SourceDiscoveryService {

        private boolean liveDiscoveryCalled;
        private boolean previewDiscoveryCalled;

        @Override
        public List<SourcePlan> discover(String competitorName, List<String> providedUrls, List<String> requestedScopes) {
            liveDiscoveryCalled = true;
            throw new IllegalStateException("preview should not invoke live discovery");
        }

        @Override
        public List<SourcePlan> discoverForPreview(String competitorName,
                                                   List<String> providedUrls,
                                                   List<String> requestedScopes) {
            previewDiscoveryCalled = true;
            return List.of(SourcePlan.builder()
                    .sourceType("DOCS")
                    .urls(List.of())
                    .notes("未提供可靠官网 URL，执行阶段将补源")
                    .candidates(List.of())
                    .build());
        }
    }
}
