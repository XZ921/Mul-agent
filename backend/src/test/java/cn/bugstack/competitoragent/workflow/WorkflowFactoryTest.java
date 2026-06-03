package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.repository.AnalysisSchemaRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchProperties;
import cn.bugstack.competitoragent.source.HeuristicSourceDiscoveryService;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptTemplateService promptTemplateService = new PromptTemplateService(objectMapper);

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
        assertTrue(config.has("discoveryNotes"));
        assertTrue(config.has("searchRuntimePolicy"));
        assertTrue(config.has("searchExecutionPlan"));
        assertTrue(config.path("browserSearchEnabled").asBoolean());
        assertEquals("HYBRID", config.path("searchMode").asText());
        assertEquals(1, config.path("maxSearchResults").asInt());
        assertTrue(config.path("verifyResultPage").asBoolean());
        assertTrue(config.path("searchRuntimePolicy").path("verifyResultPage").asBoolean());
        assertEquals(3, config.path("searchRuntimePolicy").path("maxOpenResultPages").asInt());
        SearchExecutionPlan executionPlan = objectMapper.treeToValue(config.get("searchExecutionPlan"), SearchExecutionPlan.class);
        assertNotNull(executionPlan);
        assertFalse(executionPlan.getSteps().isEmpty());
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

    private SearchBrowserProperties buildBrowserProperties() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setVerifyResultPage(true);
        return properties;
    }

    private WorkflowFactory buildWorkflowFactory(SearchBrowserProperties searchBrowserProperties) {
        return buildWorkflowFactory(
                searchBrowserProperties,
                new HeuristicSourceDiscoveryService(buildSearchSourceProvider(), new SourceCandidateRanker())
        );
    }

    private WorkflowFactory buildWorkflowFactory(SearchBrowserProperties searchBrowserProperties,
                                                 SourceDiscoveryService sourceDiscoveryService) {
        SearchProperties searchProperties = new SearchProperties();
        searchProperties.setMode("HYBRID");
        CollectorProperties collectorProperties = new CollectorProperties();
        collectorProperties.setMaxPagesPerCompetitor(5);
        return new WorkflowFactory(
                Mockito.mock(TaskNodeRepository.class),
                Mockito.mock(AnalysisSchemaRepository.class),
                sourceDiscoveryService,
                new SourceCandidateRanker(),
                new WorkflowPlanValidator(),
                objectMapper,
                promptTemplateService,
                searchBrowserProperties,
                searchProperties,
                collectorProperties
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
