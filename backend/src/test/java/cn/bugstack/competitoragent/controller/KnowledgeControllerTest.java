package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.knowledge.KnowledgeDocumentQueryService;
import cn.bugstack.competitoragent.knowledge.KnowledgeDomainService;
import cn.bugstack.competitoragent.knowledge.KnowledgeIngestionService;
import cn.bugstack.competitoragent.model.dto.KnowledgeDocumentResponse;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeControllerTest {

    private final KnowledgeDomainService knowledgeDomainService = mock(KnowledgeDomainService.class);
    private final KnowledgeIngestionService knowledgeIngestionService = mock(KnowledgeIngestionService.class);
    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService = mock(KnowledgeDocumentQueryService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(
                knowledgeDomainService,
                knowledgeIngestionService,
                knowledgeDocumentQueryService
        )).build();
    }

    @Test
    void shouldExposeActiveKnowledgeDomainsAndDomainDocuments() throws Exception {
        when(knowledgeDomainService.listActiveDomains()).thenReturn(List.of(
                KnowledgeDomain.builder()
                        .id(7L)
                        .domainKey("org-product-docs")
                        .domainName("组织产品资料")
                        .allowedSourceCategories(List.of("UPLOADED_DOCUMENTS", "USER_PROVIDED"))
                        .defaultLifecycle("ACTIVE")
                        .defaultTrustLevel("CURATED")
                        .status("ACTIVE")
                        .build()
        ));
        when(knowledgeDocumentQueryService.listByDomainKey("org-product-docs")).thenReturn(List.of(
                KnowledgeDocumentResponse.builder()
                        .id(11L)
                        .documentKey("ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE")
                        .knowledgeDomainKey("org-product-docs")
                        .sourceCategory("UPLOADED_DOCUMENTS")
                        .sourceUrls(List.of("https://docs.example.com/launch-guide.pdf"))
                        .traceSummary("来源已回指到 https://docs.example.com/launch-guide.pdf，尚未发现后续任务消费记录")
                        .build()
        ));

        mockMvc.perform(get("/api/knowledge/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domainKey").value("org-product-docs"))
                .andExpect(jsonPath("$.data[0].allowedSourceCategories[0]").value("UPLOADED_DOCUMENTS"));

        mockMvc.perform(get("/api/knowledge/domains/org-product-docs/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentKey").value("ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE"))
                .andExpect(jsonPath("$.data[0].traceSummary").value("来源已回指到 https://docs.example.com/launch-guide.pdf，尚未发现后续任务消费记录"));
    }

    @Test
    void shouldAcceptIngestionRequestAndReturnReadableSummary() throws Exception {
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(18L)
                .knowledgeDomainId(7L)
                .knowledgeDomainKey("org-product-docs")
                .knowledgeScope("ORGANIZATION")
                .evidenceId("ORG-org-product-docs-launch-guide")
                .documentKey("ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE")
                .sourceCategory("UPLOADED_DOCUMENTS")
                .sourceType("UPLOAD")
                .sourceLifecycle("ACTIVE")
                .trustLevel("VERIFIED")
                .title("组织产品资料")
                .url("https://docs.example.com/launch-guide.pdf")
                .sourceUrls(List.of("https://docs.example.com/launch-guide.pdf"))
                .build();
        when(knowledgeIngestionService.ingest(any())).thenReturn(document);
        when(knowledgeDocumentQueryService.toResponse(document)).thenReturn(KnowledgeDocumentResponse.builder()
                .id(18L)
                .knowledgeDomainKey("org-product-docs")
                .sourceCategory("UPLOADED_DOCUMENTS")
                .sourceUrls(List.of("https://docs.example.com/launch-guide.pdf"))
                .consumedTaskIds(List.of(88L))
                .traceSummary("来源已回指到 https://docs.example.com/launch-guide.pdf，并已进入 task-88 的证据消费链路")
                .build());

        String payload = """
                {
                  "domainKey": "org-product-docs",
                  "sourceCategory": "UPLOADED_DOCUMENTS",
                  "sourceType": "PDF",
                  "title": "组织产品资料",
                  "url": "https://docs.example.com/launch-guide.pdf",
                  "sourceUrls": ["https://docs.example.com/launch-guide.pdf"],
                  "contentText": "发布手册覆盖产品定位与定价。"
                }
                """;

        mockMvc.perform(post("/api/knowledge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(18))
                .andExpect(jsonPath("$.data.knowledgeDomainKey").value("org-product-docs"))
                .andExpect(jsonPath("$.data.sourceCategory").value("UPLOADED_DOCUMENTS"))
                .andExpect(jsonPath("$.data.consumedTaskIds[0]").value(88))
                .andExpect(jsonPath("$.data.traceSummary").value("来源已回指到 https://docs.example.com/launch-guide.pdf，并已进入 task-88 的证据消费链路"));
    }
}
