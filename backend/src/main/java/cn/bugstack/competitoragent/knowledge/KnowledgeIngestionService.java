package cn.bugstack.competitoragent.knowledge;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.KnowledgeIngestionRequest;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import cn.bugstack.competitoragent.rag.KnowledgeDocumentBuilder;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexingResult;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 组织级资料统一接入服务。
 * <p>
 * 它负责把“用户上传 / 受控连接器 / AI 发现”三类资料收口成同一份 `KnowledgeDocument`，
 * 并在入库时显式补齐知识域、生命周期和可信度这些后续治理必须依赖的元数据。
 */
@Service
public class KnowledgeIngestionService {

    private final KnowledgeDomainService knowledgeDomainService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeDocumentBuilder knowledgeDocumentBuilder;
    private final TaskRetrievalIndexService taskRetrievalIndexService;
    private final OrganizationQuotaPolicy organizationQuotaPolicy;

    public KnowledgeIngestionService(KnowledgeDomainService knowledgeDomainService,
                                     KnowledgeDocumentRepository knowledgeDocumentRepository,
                                     KnowledgeDocumentBuilder knowledgeDocumentBuilder) {
        this(knowledgeDomainService, knowledgeDocumentRepository, knowledgeDocumentBuilder, null, null);
    }

    /**
     * 运行时需要显式告诉 Spring 使用完整构造器完成依赖注入，
     * 否则在存在测试便捷构造器的前提下，容器可能退回去寻找不存在的无参构造函数。
     */
    public KnowledgeIngestionService(KnowledgeDomainService knowledgeDomainService,
                                     KnowledgeDocumentRepository knowledgeDocumentRepository,
                                     KnowledgeDocumentBuilder knowledgeDocumentBuilder,
                                     TaskRetrievalIndexService taskRetrievalIndexService) {
        this(knowledgeDomainService,
                knowledgeDocumentRepository,
                knowledgeDocumentBuilder,
                taskRetrievalIndexService,
                null);
    }

    /**
     * 组织级知识接入治理落地后，正式运行路径使用完整构造器接入统一配额判断。
     */
    @Autowired
    public KnowledgeIngestionService(KnowledgeDomainService knowledgeDomainService,
                                     KnowledgeDocumentRepository knowledgeDocumentRepository,
                                     KnowledgeDocumentBuilder knowledgeDocumentBuilder,
                                     TaskRetrievalIndexService taskRetrievalIndexService,
                                     OrganizationQuotaPolicy organizationQuotaPolicy) {
        this.knowledgeDomainService = knowledgeDomainService;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeDocumentBuilder = knowledgeDocumentBuilder;
        this.taskRetrievalIndexService = taskRetrievalIndexService;
        this.organizationQuotaPolicy = organizationQuotaPolicy;
    }

    /**
     * 统一接入入口当前仍然只负责“接入 -> 标准化 -> 入知识底座 -> 接统一索引主链”。
     * 真正的连接器运行时调度、放量和配额治理仍然留在 Task 5.8，
     * 这里不要越界演进成新的调度系统。
     *
     * 组织级资料一旦入库，就必须立即进入正式 Retrieval 主链。
     * 否则后续 Domain / Organization RAG 只能从 `knowledge_document` 旁路查询，
     * 既破坏三层召回统一性，也会让 sourceUrls / 证据追溯链在不同入口出现分叉。
     */
    @Transactional
    public KnowledgeDocument ingest(KnowledgeIngestionRequest request) {
        KnowledgeDomain domain = knowledgeDomainService.resolveActiveDomain(
                request.getDomainKey(),
                request.getSourceCategory());
        validateConnectorSpecificConstraints(request);
        ensureKnowledgeIngestionAllowed(request);
        KnowledgeDocument document = knowledgeDocumentBuilder.buildOrganizationDocument(request, domain, 1);
        KnowledgeDocument savedDocument = knowledgeDocumentRepository.save(document);
        if (taskRetrievalIndexService == null) {
            return savedDocument;
        }

        TaskRetrievalIndexingResult indexingResult = taskRetrievalIndexService.indexKnowledgeDocument(savedDocument);
        return indexingResult.knowledgeDocument();
    }

    /**
     * 资料接入会消耗组织级接入额度，因此要在真正写库前先完成治理判定。
     * 这样一旦配额不足，就会直接返回结构化阻断结果，而不是留下半成功的入库状态。
     */
    private void ensureKnowledgeIngestionAllowed(KnowledgeIngestionRequest request) {
        if (organizationQuotaPolicy == null) {
            return;
        }
        QuotaDecision decision = organizationQuotaPolicy.checkAndReserve(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.KNOWLEDGE_SCOPE,
                GovernanceDefaults.KNOWLEDGE_INGESTION_KEY,
                1,
                request == null ? java.util.List.of() : request.getSourceUrls()
        );
        if (decision != null && !decision.isAllowed()) {
            throw new GovernanceBlockException(decision);
        }
    }

    /**
     * 受控连接器资料如果没有 connectorKey，就无法解释“这份资料来自哪个受治理连接器定义”，
     * 后续既无法审计，也没法在 5.8 的运行时治理阶段承接同一条资料链路。
     */
    private void validateConnectorSpecificConstraints(KnowledgeIngestionRequest request) {
        if ("AUTHENTICATED_SOURCES".equalsIgnoreCase(request.getSourceCategory())
                && !StringUtils.hasText(request.getConnectorKey())) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "authenticated source requires connectorKey");
        }
    }
}
