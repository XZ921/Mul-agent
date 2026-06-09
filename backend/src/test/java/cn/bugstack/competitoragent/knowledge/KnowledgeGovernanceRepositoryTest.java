package cn.bugstack.competitoragent.knowledge;

import cn.bugstack.competitoragent.model.entity.ConnectorSyncRecord;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import cn.bugstack.competitoragent.repository.ConnectorSyncRecordRepository;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import cn.bugstack.competitoragent.repository.KnowledgeDomainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class KnowledgeGovernanceRepositoryTest {

    @Autowired
    private KnowledgeDomainRepository knowledgeDomainRepository;

    @Autowired
    private ConnectorSyncRecordRepository connectorSyncRecordRepository;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Test
    void shouldPersistKnowledgeDomainAndConnectorSyncRecord() {
        KnowledgeDomain savedDomain = knowledgeDomainRepository.save(buildKnowledgeDomain());

        connectorSyncRecordRepository.save(buildConnectorSyncRecord(savedDomain.getId()));

        KnowledgeDomain reloadedDomain = knowledgeDomainRepository.findByDomainKey("org-product-docs").orElseThrow();
        List<ConnectorSyncRecord> records = connectorSyncRecordRepository.findByKnowledgeDomainIdOrderByIdAsc(savedDomain.getId());

        assertNotNull(savedDomain.getId());
        assertEquals("ACTIVE", reloadedDomain.getStatus());
        assertEquals("CURATED", reloadedDomain.getDefaultTrustLevel());
        assertEquals(List.of("UPLOADED_DOCUMENTS", "AUTHENTICATED_SOURCES"), reloadedDomain.getAllowedSourceCategories());
        assertEquals(1, records.size());
        assertEquals("PENDING", records.get(0).getSyncStatus());
        assertTrue(records.get(0).getSourceUrls().isEmpty());
    }

    @Test
    void shouldQueryDomainDocumentsAndTaskConsumersBySourceUrl() {
        knowledgeDocumentRepository.save(buildOrganizationKnowledgeDocument());
        knowledgeDocumentRepository.save(buildTaskKnowledgeDocument());

        List<KnowledgeDocument> domainDocuments =
                knowledgeDocumentRepository.findByKnowledgeDomainKeyOrderByIdAsc("org-product-docs");
        List<KnowledgeDocument> consumerDocuments =
                knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc(
                        "https://docs.example.com/launch-guide.pdf"
                );

        assertEquals(1, domainDocuments.size());
        assertEquals("ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE",
                domainDocuments.get(0).getDocumentKey());
        assertEquals(1, consumerDocuments.size());
        assertEquals(88L, consumerDocuments.get(0).getTaskId());
        assertEquals("T0088-COLLECT-001", consumerDocuments.get(0).getEvidenceId());
    }

    @Test
    void shouldMatchTaskConsumersByExactSourceUrlInsteadOfSubstring() {
        knowledgeDocumentRepository.save(buildOrganizationKnowledgeDocument());
        knowledgeDocumentRepository.save(buildTaskKnowledgeDocument());
        knowledgeDocumentRepository.save(buildSubstringTaskKnowledgeDocument());

        List<KnowledgeDocument> consumerDocuments =
                knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc(
                        "https://docs.example.com/launch-guide.pdf"
                );

        /**
         * 5.2.c 的 sourceUrls 回指要回答的是“这条来源是否真的被后续任务消费过”，
         * 因此这里必须按 URL 完整值精确命中，而不是把同前缀或同子串的其他链接也误算进消费链路。
         */
        assertEquals(1, consumerDocuments.size());
        assertEquals(88L, consumerDocuments.get(0).getTaskId());
        assertEquals("T0088-COLLECT-001", consumerDocuments.get(0).getEvidenceId());
    }

    /**
     * 测试数据显式体现“组织知识域允许上传资料和受控连接器资料进入”，
     * 这样后续有人读测试时能直接看懂当前数据模型想表达的业务边界。
     */
    private KnowledgeDomain buildKnowledgeDomain() {
        return KnowledgeDomain.builder()
                .domainKey("org-product-docs")
                .domainName("组织产品资料")
                .description("沉淀组织级产品文档、发布说明与内部资料。")
                .allowedSourceCategories(List.of("UPLOADED_DOCUMENTS", "AUTHENTICATED_SOURCES"))
                .build();
    }

    /**
     * 同步记录这里只保留“定义过一次同步”的最小信息，
     * 故意不加入 Task 5.8 才会出现的运行时占位或配额字段，避免测试越界。
     */
    private ConnectorSyncRecord buildConnectorSyncRecord(Long knowledgeDomainId) {
        return ConnectorSyncRecord.builder()
                .knowledgeDomainId(knowledgeDomainId)
                .connectorKey("feishu-drive")
                .connectorType("DOCUMENT")
                .connectorLabel("飞书云文档")
                .requestPayload("{\"folder\":\"product\"}")
                .sourceUrls(null)
                .build();
    }

    /**
     * 组织级资料要保留知识域、sourceUrls 和可信度这些治理字段，
     * 这样后续查询摘要才能直接说明“这份资料属于哪个域、源头在哪里”。
     */
    private KnowledgeDocument buildOrganizationKnowledgeDocument() {
        return KnowledgeDocument.builder()
                .taskId(null)
                .competitorName("Feishu")
                .evidenceId("ORG-org-product-docs-launch-guide")
                .documentKey("ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE")
                .knowledgeScope("ORGANIZATION")
                .knowledgeDomainId(7L)
                .knowledgeDomainKey("org-product-docs")
                .sourceType("UPLOAD")
                .sourceCategory("UPLOADED_DOCUMENTS")
                .discoveryMethod("UPLOAD")
                .sourceDomain("docs.example.com")
                .sourceLifecycle("ACTIVE")
                .trustLevel("VERIFIED")
                .title("组织产品资料")
                .url("https://docs.example.com/launch-guide.pdf")
                .snippet("发布手册")
                .cleanedText("发布手册覆盖产品定位与定价。")
                .sourceUrls(List.of(
                        "https://docs.example.com/launch-guide.pdf",
                        "https://docs.example.com/pricing"
                ))
                .issueFlags(List.of())
                .documentVersion(1)
                .status("READY")
                .collectedAt(LocalDateTime.of(2026, 6, 6, 12, 0))
                .build();
    }

    /**
     * 任务级知识文档复用同一来源链接，
     * 这样才能证明 5.2.c 的“sourceUrls -> 后续任务消费链路”查询在真实仓储上是可工作的。
     */
    private KnowledgeDocument buildTaskKnowledgeDocument() {
        return KnowledgeDocument.builder()
                .taskId(88L)
                .competitorName("Feishu")
                .evidenceId("T0088-COLLECT-001")
                .documentKey("TASK-88-T0088-COLLECT-001")
                .knowledgeScope("TASK")
                .knowledgeDomainId(null)
                .knowledgeDomainKey(null)
                .sourceType("DOCS")
                .sourceCategory("USER_PROVIDED")
                .discoveryMethod("CONFIG")
                .sourceDomain("docs.example.com")
                .sourceLifecycle("ACTIVE")
                .trustLevel("USER_CONFIRMED")
                .title("Feishu launch guide")
                .url("https://docs.example.com/launch-guide.pdf")
                .snippet("任务采集到的手册")
                .cleanedText("任务级知识也引用了同一份发布手册。")
                .sourceUrls(List.of("https://docs.example.com/launch-guide.pdf"))
                .issueFlags(List.of())
                .documentVersion(1)
                .status("READY")
                .collectedAt(LocalDateTime.of(2026, 6, 6, 12, 30))
                .build();
    }

    /**
     * 这条任务级文档故意使用“包含目标 URL 子串、但并非同一条链接”的来源，
     * 用来锁定仓储查询不能再依赖 JSON 文本 LIKE 产生误命中。
     */
    private KnowledgeDocument buildSubstringTaskKnowledgeDocument() {
        return KnowledgeDocument.builder()
                .taskId(99L)
                .competitorName("Feishu")
                .evidenceId("T0099-COLLECT-001")
                .documentKey("TASK-99-T0099-COLLECT-001")
                .knowledgeScope("TASK")
                .knowledgeDomainId(null)
                .knowledgeDomainKey(null)
                .sourceType("DOCS")
                .sourceCategory("USER_PROVIDED")
                .discoveryMethod("CONFIG")
                .sourceDomain("docs.example.com")
                .sourceLifecycle("ACTIVE")
                .trustLevel("USER_CONFIRMED")
                .title("Feishu launch guide archive")
                .url("https://docs.example.com/launch-guide.pdf-archive")
                .snippet("任务采集到的归档页")
                .cleanedText("这份任务级知识引用的是归档页，不应被视为 launch-guide.pdf 的消费记录。")
                .sourceUrls(List.of("https://docs.example.com/launch-guide.pdf-archive"))
                .issueFlags(List.of())
                .documentVersion(1)
                .status("READY")
                .collectedAt(LocalDateTime.of(2026, 6, 6, 13, 0))
                .build();
    }
}
