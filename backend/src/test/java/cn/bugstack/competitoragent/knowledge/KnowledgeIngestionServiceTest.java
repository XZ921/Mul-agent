package cn.bugstack.competitoragent.knowledge;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.KnowledgeIngestionRequest;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.rag.KnowledgeDocumentBuilder;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexingResult;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import cn.bugstack.competitoragent.repository.KnowledgeDomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIngestionServiceTest {

    @Mock
    private KnowledgeDomainRepository knowledgeDomainRepository;

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private OrganizationQuotaPolicy organizationQuotaPolicy;

    private TaskRetrievalIndexService taskRetrievalIndexService;

    private KnowledgeIngestionService knowledgeIngestionService;

    @BeforeEach
    void setUp() {
        taskRetrievalIndexService = mock(TaskRetrievalIndexService.class, invocation -> {
            if ("indexKnowledgeDocument".equals(invocation.getMethod().getName())) {
                KnowledgeDocument document = invocation.getArgument(0);
                if (document != null) {
                    document.setStatus("READY");
                }
                return TaskRetrievalIndexingResult.success(
                        document,
                        List.of(),
                        RetrievalIndex.builder().status("READY").build(),
                        List.of()
                );
            }
            return null;
        });

        KnowledgeDomainService knowledgeDomainService = new KnowledgeDomainService(knowledgeDomainRepository);
        knowledgeIngestionService = buildServiceWithOptionalIndexing(knowledgeDomainService);
    }

    @Test
    void shouldIngestUploadedDocumentIntoOrganizationKnowledgeDocument() {
        KnowledgeDomain domain = activeDomain(11L, "org-product-docs",
                List.of("UPLOADED_DOCUMENTS", "AUTHENTICATED_SOURCES"));
        KnowledgeIngestionRequest request = baseRequest("org-product-docs", "UPLOADED_DOCUMENTS");
        request.setSourceType("PDF");
        request.setTitle("浜у搧鍙戝竷鎵嬪唽");
        request.setUrl("https://docs.example.com/launch-guide.pdf");
        request.setSourceUrls(List.of("https://docs.example.com/launch-guide.pdf"));
        request.setContentText("  鍙戝竷鎵嬪唽瑕嗙洊浜у搧瀹氫綅銆佸畾浠蜂笌 AI 鑳藉姏銆? ");
        request.setRequestedLifecycle("ACTIVE");
        request.setRequestedTrustLevel("VERIFIED");

        when(knowledgeDomainRepository.findByDomainKey("org-product-docs"))
                .thenReturn(Optional.of(domain));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> {
                    KnowledgeDocument document = invocation.getArgument(0);
                    document.setId(101L);
                    return document;
                });

        KnowledgeDocument document = knowledgeIngestionService.ingest(request);

        assertEquals(101L, document.getId());
        assertNull(document.getTaskId());
        assertEquals("ORGANIZATION", document.getKnowledgeScope());
        assertEquals(11L, document.getKnowledgeDomainId());
        assertEquals("org-product-docs", document.getKnowledgeDomainKey());
        assertEquals("UPLOADED_DOCUMENTS", document.getSourceCategory());
        assertEquals("ACTIVE", document.getSourceLifecycle());
        assertEquals("VERIFIED", document.getTrustLevel());
        assertEquals("PDF", document.getSourceType());
        assertEquals("UPLOAD", document.getDiscoveryMethod());
        assertTrue(document.getDocumentKey().startsWith("ORG-ORG-PRODUCT-DOCS"));
        assertTrue(document.getCleanedText().contains("鍙戝竷鎵嬪唽瑕嗙洊浜у搧瀹氫綅"));
    }

    @Test
    void shouldRejectAuthenticatedSourceWithoutConnectorKey() {
        KnowledgeDomain domain = activeDomain(12L, "org-secure-connectors",
                List.of("AUTHENTICATED_SOURCES"));
        KnowledgeIngestionRequest request = baseRequest("org-secure-connectors", "AUTHENTICATED_SOURCES");
        request.setTitle("椋炰功鐭ヨ瘑搴撳悓姝?");
        request.setUrl("https://open.feishu.cn/docs/base");
        request.setSourceUrls(List.of("https://open.feishu.cn/docs/base"));
        request.setContentText("杩炴帴鍣ㄥ悓姝ュ唴瀹?");

        when(knowledgeDomainRepository.findByDomainKey("org-secure-connectors"))
                .thenReturn(Optional.of(domain));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeIngestionService.ingest(request));

        assertEquals(ResultCode.PARAM_MISSING, exception.getResultCode());
    }

    @Test
    void shouldKeepConnectorMetadataForAuthenticatedSource() {
        KnowledgeDomain domain = activeDomain(14L, "org-secure-connectors",
                List.of("AUTHENTICATED_SOURCES"));
        KnowledgeIngestionRequest request = baseRequest("org-secure-connectors", "AUTHENTICATED_SOURCES");
        request.setTitle("椋炰功鐭ヨ瘑搴撳悓姝ユ憳瑕?");
        request.setConnectorKey("feishu-docs");
        request.setUrl("https://open.feishu.cn/document/product-plan");
        request.setSourceUrls(List.of("https://open.feishu.cn/document/product-plan"));
        request.setContentText("杩炴帴鍣ㄥ悓姝ヤ骇鐗╀粛鐒堕渶瑕佷繚鐣?connectorKey 鍜屽師濮嬫潵婧愬湴鍧€銆?");

        when(knowledgeDomainRepository.findByDomainKey("org-secure-connectors"))
                .thenReturn(Optional.of(domain));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> {
                    KnowledgeDocument document = invocation.getArgument(0);
                    document.setId(102L);
                    return document;
                });

        KnowledgeDocument document = knowledgeIngestionService.ingest(request);

        assertEquals(102L, document.getId());
        assertEquals("feishu-docs", document.getConnectorKey());
        assertEquals("AUTHENTICATED_SOURCES", document.getSourceCategory());
        assertEquals("CONNECTOR", document.getSourceType());
        assertEquals("CONNECTOR", document.getDiscoveryMethod());
        assertEquals("CONNECTED_SOURCE", document.getTrustLevel());
        assertTrue(document.getDocumentKey().contains("FEISHU-DOCS"));
    }

    @Test
    void shouldKeepAiDiscoveredTrustLevelWhenRequestDoesNotOverrideIt() {
        KnowledgeDomain domain = activeDomain(13L, "org-market-watch", List.of("AI_DISCOVERED"));
        KnowledgeIngestionRequest request = baseRequest("org-market-watch", "AI_DISCOVERED");
        request.setTitle("鍏紑璇勬祴鏂囩珷");
        request.setUrl("https://news.example.com/analysis");
        request.setSourceUrls(List.of("https://news.example.com/analysis"));
        request.setContentText("AI 鍙戠幇浜嗕竴绡囧叧浜庣珵鍝佸畾浠峰拰鍔熻兘宸紓鐨勫叕寮€璇勬祴銆?");

        when(knowledgeDomainRepository.findByDomainKey("org-market-watch"))
                .thenReturn(Optional.of(domain));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeDocument document = knowledgeIngestionService.ingest(request);

        assertEquals("AI_DISCOVERY", document.getDiscoveryMethod());
        assertEquals("DISCOVERED", document.getTrustLevel());
        assertEquals("ORGANIZATION", document.getKnowledgeScope());
    }

    @Test
    void shouldBlockKnowledgeIngestionWithStructuredGovernanceDecisionWhenQuotaExceeded() {
        // Task 5.8.c 要求资料接入主链路在组织级配额不足时返回治理阻断结果，
        // 不能继续写库后再在后续流程里被动失败。
        KnowledgeDomain domain = activeDomain(16L, "org-product-docs", List.of("UPLOADED_DOCUMENTS"));
        KnowledgeIngestionRequest request = baseRequest("org-product-docs", "UPLOADED_DOCUMENTS");
        request.setTitle("组织级资料接入阻断测试");
        request.setUrl("https://docs.example.com/launch-guide.pdf");
        request.setSourceUrls(List.of("https://docs.example.com/launch-guide.pdf"));
        request.setContentText("组织级资料接入阻断测试内容");

        when(knowledgeDomainRepository.findByDomainKey("org-product-docs"))
                .thenReturn(Optional.of(domain));
        when(organizationQuotaPolicy.checkAndReserve(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(QuotaDecision.deny(
                        "BLOCKED_QUOTA_EXCEEDED",
                        "当前组织资料接入配额不足，请等待释放或稍后重试",
                        "default-organization",
                        "KNOWLEDGE",
                        "KNOWLEDGE_INGESTION",
                        1,
                        0,
                        null,
                        List.of("https://docs.example.com/launch-guide.pdf")
                ));

        KnowledgeIngestionService service = buildServiceWithOptionalGovernance(new KnowledgeDomainService(knowledgeDomainRepository));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.ingest(request));

        assertEquals("GovernanceBlockException", exception.getClass().getSimpleName());
        Object decision = readAccessor(exception, "decision");
        assertEquals("KNOWLEDGE_INGESTION", readAccessor(decision, "quotaKey"));
        assertEquals("BLOCKED_QUOTA_EXCEEDED", readAccessor(decision, "decisionCode"));
        assertEquals("当前组织资料接入配额不足，请等待释放或稍后重试", readAccessor(decision, "summary"));
    }

    @Test
    void shouldIndexOrganizationKnowledgeIntoUnifiedRetrievalChainAfterIngestion() throws Exception {
        // Task 5.3 要求组织级资料在接入后立即进入统一检索链路，
        // 否则后续 Domain / Organization RAG 只能做成旁路查询。
        Constructor<KnowledgeIngestionService> constructor = KnowledgeIngestionService.class.getConstructor(
                KnowledgeDomainService.class,
                KnowledgeDocumentRepository.class,
                KnowledgeDocumentBuilder.class,
                TaskRetrievalIndexService.class
        );

        KnowledgeDomainService knowledgeDomainService = new KnowledgeDomainService(knowledgeDomainRepository);
        KnowledgeIngestionService service = constructor.newInstance(
                knowledgeDomainService,
                knowledgeDocumentRepository,
                new KnowledgeDocumentBuilder(),
                taskRetrievalIndexService
        );

        KnowledgeDomain domain = activeDomain(15L, "org-product-docs", List.of("UPLOADED_DOCUMENTS"));
        KnowledgeIngestionRequest request = baseRequest("org-product-docs", "UPLOADED_DOCUMENTS");
        request.setTitle("缁勭粐浜у搧璧勬枡");
        request.setUrl("https://docs.example.com/launch-guide.pdf");
        request.setSourceUrls(List.of("https://docs.example.com/launch-guide.pdf"));
        request.setContentText("鍙戝竷鎵嬪唽瑕嗙洊浜у搧瀹氫綅銆佸畾浠蜂笌 AI 鑳藉姏銆?");

        when(knowledgeDomainRepository.findByDomainKey("org-product-docs"))
                .thenReturn(Optional.of(domain));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> {
                    KnowledgeDocument document = invocation.getArgument(0);
                    if (document.getId() == null) {
                        document.setId(205L);
                    }
                    return document;
                });

        KnowledgeDocument document = service.ingest(request);

        assertEquals(205L, document.getId());
        assertTrue(mockingDetails(taskRetrievalIndexService).getInvocations().stream()
                .anyMatch(invocation -> "indexKnowledgeDocument".equals(invocation.getMethod().getName())));
    }

    private KnowledgeIngestionService buildServiceWithOptionalIndexing(KnowledgeDomainService knowledgeDomainService) {
        try {
            Constructor<KnowledgeIngestionService> constructor = KnowledgeIngestionService.class.getConstructor(
                    KnowledgeDomainService.class,
                    KnowledgeDocumentRepository.class,
                    KnowledgeDocumentBuilder.class,
                    TaskRetrievalIndexService.class
            );
            return constructor.newInstance(
                    knowledgeDomainService,
                    knowledgeDocumentRepository,
                    new KnowledgeDocumentBuilder(),
                    taskRetrievalIndexService
            );
        } catch (NoSuchMethodException ignored) {
            return new KnowledgeIngestionService(
                    knowledgeDomainService,
                    knowledgeDocumentRepository,
                    new KnowledgeDocumentBuilder()
            );
        } catch (Exception e) {
            throw new IllegalStateException("failed to construct KnowledgeIngestionService for test", e);
        }
    }

    private KnowledgeIngestionService buildServiceWithOptionalGovernance(KnowledgeDomainService knowledgeDomainService) {
        try {
            Constructor<KnowledgeIngestionService> constructor = KnowledgeIngestionService.class.getConstructor(
                    KnowledgeDomainService.class,
                    KnowledgeDocumentRepository.class,
                    KnowledgeDocumentBuilder.class,
                    TaskRetrievalIndexService.class,
                    OrganizationQuotaPolicy.class
            );
            return constructor.newInstance(
                    knowledgeDomainService,
                    knowledgeDocumentRepository,
                    new KnowledgeDocumentBuilder(),
                    taskRetrievalIndexService,
                    organizationQuotaPolicy
            );
        } catch (NoSuchMethodException ignored) {
            return buildServiceWithOptionalIndexing(knowledgeDomainService);
        } catch (Exception e) {
            throw new IllegalStateException("failed to construct KnowledgeIngestionService with governance for test", e);
        }
    }

    /**
     * 测试里统一使用“启用中的知识域”，这样每条断言都能聚焦在当前要表达的接入语义上，
     * 不会被和本轮场景无关的状态字段干扰。
     */
    private KnowledgeDomain activeDomain(Long id, String domainKey, List<String> allowedSourceCategories) {
        return KnowledgeDomain.builder()
                .id(id)
                .domainKey(domainKey)
                .domainName(domainKey + "-domain")
                .allowedSourceCategories(allowedSourceCategories)
                .defaultLifecycle("ACTIVE")
                .defaultTrustLevel("CURATED")
                .status("ACTIVE")
                .build();
    }

    /**
     * 统一沉淀最小接入请求骨架，避免每条测试都重复拼接公共字段。
     * 后续若 5.2.c / 5.2.d 继续扩展请求契约，只需要在一个地方同步更新测试基线。
     */
    private KnowledgeIngestionRequest baseRequest(String domainKey, String sourceCategory) {
        KnowledgeIngestionRequest request = new KnowledgeIngestionRequest();
        request.setDomainKey(domainKey);
        request.setSourceCategory(sourceCategory);
        return request;
    }

    private Object readAccessor(Object target, String accessorName) {
        Method method = ReflectionUtils.findMethod(target.getClass(),
                "get" + Character.toUpperCase(accessorName.charAt(0)) + accessorName.substring(1));
        org.junit.jupiter.api.Assertions.assertNotNull(method,
                () -> "缺少访问器：" + target.getClass().getSimpleName() + "." + accessorName);
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target);
    }
}
