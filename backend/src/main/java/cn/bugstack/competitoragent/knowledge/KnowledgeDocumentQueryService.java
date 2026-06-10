package cn.bugstack.competitoragent.knowledge;

import cn.bugstack.competitoragent.model.dto.KnowledgeDocumentResponse;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 组织级知识文档查询服务。
 * <p>
 * Task 5.2.c 先把“接入后的组织知识如何被业务可读地返回”收口到这里：
 * 1. 统一拼装 sourceUrls 回指信息；
 * 2. 统一汇总被后续任务消费过的 task / evidence 线索；
 * 3. 避免控制器和前端各自拼 trace summary，导致口径分裂。
 */
@Service
public class KnowledgeDocumentQueryService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    public KnowledgeDocumentQueryService(KnowledgeDocumentRepository knowledgeDocumentRepository) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
    }

    public List<KnowledgeDocumentResponse> listByDomainKey(String domainKey) {
        List<KnowledgeDocument> documents = knowledgeDocumentRepository.findByKnowledgeDomainKeyOrderByIdAsc(domainKey);
        List<KnowledgeDocumentResponse> responses = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            responses.add(toResponse(document));
        }
        return responses;
    }

    /**
     * 为 phase4a 的 knowledge facade 提供稳定的任务级知识读取入口。
     * 这里沿用 repository 的升序语义，保证 task 级知识列表在 facade 收口后仍保持可预测顺序。
     */
    public List<KnowledgeDocumentResponse> listByTaskId(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        List<KnowledgeDocumentResponse> responses = new ArrayList<>();
        for (KnowledgeDocument document : knowledgeDocumentRepository.findByTaskIdOrderByIdAsc(taskId)) {
            responses.add(toResponse(document));
        }
        return responses;
    }

    /**
     * 这里故意把“消费链路摘要”放在查询服务层统一生成，
     * 因为它本质上是多个知识文档查询结果之间的聚合判断，而不是单个实体字段本身。
     */
    public KnowledgeDocumentResponse toResponse(KnowledgeDocument document) {
        List<String> sourceUrls = normalizeList(document.getSourceUrls());
        TraceContext traceContext = buildTraceContext(document, sourceUrls);
        return KnowledgeDocumentResponse.builder()
                .id(document.getId())
                .taskId(document.getTaskId())
                .evidenceId(document.getEvidenceId())
                .documentKey(document.getDocumentKey())
                .knowledgeScope(document.getKnowledgeScope())
                .knowledgeDomainId(document.getKnowledgeDomainId())
                .knowledgeDomainKey(document.getKnowledgeDomainKey())
                .competitorName(document.getCompetitorName())
                .sourceType(document.getSourceType())
                .sourceCategory(document.getSourceCategory())
                .discoveryMethod(document.getDiscoveryMethod())
                .sourceDomain(document.getSourceDomain())
                .sourceLifecycle(document.getSourceLifecycle())
                .trustLevel(document.getTrustLevel())
                .connectorKey(document.getConnectorKey())
                .title(document.getTitle())
                .url(document.getUrl())
                .sourceUrls(sourceUrls)
                .issueFlags(normalizeList(document.getIssueFlags()))
                .consumedTaskIds(traceContext.consumedTaskIds())
                .consumedEvidenceIds(traceContext.consumedEvidenceIds())
                .traceSummary(traceContext.summary())
                .build();
    }

    private TraceContext buildTraceContext(KnowledgeDocument document, List<String> sourceUrls) {
        LinkedHashSet<Long> consumedTaskIds = new LinkedHashSet<>();
        LinkedHashSet<String> consumedEvidenceIds = new LinkedHashSet<>();

        for (String sourceUrl : sourceUrls) {
            List<KnowledgeDocument> consumerDocuments =
                    knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc(sourceUrl);
            for (KnowledgeDocument consumerDocument : consumerDocuments) {
                if (document.getId() != null && document.getId().equals(consumerDocument.getId())) {
                    continue;
                }
                if (consumerDocument.getTaskId() != null) {
                    consumedTaskIds.add(consumerDocument.getTaskId());
                }
                if (StringUtils.hasText(consumerDocument.getEvidenceId())) {
                    consumedEvidenceIds.add(consumerDocument.getEvidenceId().trim());
                }
            }
        }

        return new TraceContext(
                new ArrayList<>(consumedTaskIds),
                new ArrayList<>(consumedEvidenceIds),
                buildTraceSummary(document, sourceUrls, consumedTaskIds)
        );
    }

    /**
     * 返回摘要坚持“先说明来源，再说明消费状态”的顺序，
     * 这样前端无需理解底层表结构，也能直接向用户解释当前资料是否已经进入后续任务链路。
     */
    private String buildTraceSummary(KnowledgeDocument document,
                                     List<String> sourceUrls,
                                     LinkedHashSet<Long> consumedTaskIds) {
        String primarySourceUrl = sourceUrls.isEmpty() ? defaultText(document.getUrl(), "未知来源") : sourceUrls.get(0);
        if (consumedTaskIds.isEmpty()) {
            return "来源已回指到 " + primarySourceUrl + "，尚未发现后续任务消费记录";
        }

        List<String> taskLabels = new ArrayList<>();
        for (Long consumedTaskId : consumedTaskIds) {
            taskLabels.add("task-" + consumedTaskId);
        }
        return "来源已回指到 " + primarySourceUrl + "，并已进入 " + String.join("、", taskLabels) + " 的证据消费链路";
    }

    private List<String> normalizeList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private record TraceContext(List<Long> consumedTaskIds,
                                List<String> consumedEvidenceIds,
                                String summary) {
    }
}
