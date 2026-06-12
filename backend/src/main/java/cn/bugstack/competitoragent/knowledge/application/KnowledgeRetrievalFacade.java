package cn.bugstack.competitoragent.knowledge.application;

import cn.bugstack.competitoragent.model.dto.KnowledgeDocumentResponse;

import java.util.List;

/**
 * knowledge-intelligence 对外暴露的稳定读取门面。
 * <p>
 * phase4a Task 1 先把 task 级知识列表、任务检索结果投影和摘要入口固定下来，
 * 避免其他模块继续直接依赖 `TaskRetrievalService.RetrievalResult` 这类内部结果结构。
 */
public interface KnowledgeRetrievalFacade {

    List<KnowledgeDocumentResponse> listTaskKnowledge(Long taskId);

    RetrievalResultView retrieveForTask(Long taskId, String query, String nodeName);

    String summarizeTaskRagContext(Long taskId, String query, String nodeName);

    record RetrievalResultView(
            List<String> sourceUrls,
            String gapSummary,
            String answer,
            List<String> hitDocumentIds,
            List<String> hitEvidenceIds
    ) {
    }
}
