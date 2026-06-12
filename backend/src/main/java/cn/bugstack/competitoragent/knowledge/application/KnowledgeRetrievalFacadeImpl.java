package cn.bugstack.competitoragent.knowledge.application;

import cn.bugstack.competitoragent.knowledge.KnowledgeDocumentQueryService;
import cn.bugstack.competitoragent.model.dto.KnowledgeDocumentResponse;
import cn.bugstack.competitoragent.rag.TaskRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * `KnowledgeRetrievalFacade` 的最小落地实现。
 * <p>
 * phase4a Task 1 先通过包装现有查询服务与任务检索服务固定跨模块读取入口，
 * 对外只暴露稳定投影视图，不把 `TaskRetrievalService.RetrievalResult` 直接泄露给调用方。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalFacadeImpl implements KnowledgeRetrievalFacade {

    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService;
    private final TaskRetrievalService taskRetrievalService;

    @Override
    public List<KnowledgeDocumentResponse> listTaskKnowledge(Long taskId) {
        return knowledgeDocumentQueryService.listByTaskId(taskId);
    }

    @Override
    public RetrievalResultView retrieveForTask(Long taskId, String query, String nodeName) {
        TaskRetrievalService.RetrievalResult retrievalResult = taskRetrievalService.retrieve(taskId, query, nodeName);
        if (retrievalResult == null) {
            return new RetrievalResultView(List.of(), null, "", List.of(), List.of());
        }
        return new RetrievalResultView(
                retrievalResult.getSourceUrls() == null ? List.of() : retrievalResult.getSourceUrls(),
                retrievalResult.getGapSummary(),
                buildAnswer(retrievalResult),
                collectHitDocumentIds(retrievalResult),
                collectHitEvidenceIds(retrievalResult)
        );
    }

    @Override
    public String summarizeTaskRagContext(Long taskId, String query, String nodeName) {
        RetrievalResultView view = retrieveForTask(taskId, query, nodeName);
        if (!StringUtils.hasText(view.answer())) {
            return firstNonBlank(view.gapSummary(), "当前没有可用知识上下文。");
        }
        if (!StringUtils.hasText(view.gapSummary())) {
            return view.answer();
        }
        return view.answer() + "；缺口说明：" + view.gapSummary();
    }

    /**
     * facade 对外的 answer 只表达“当前召回到了什么”，
     * 不额外发明第二套分析结论语义，避免和后续 Agent 真正生成的结论混淆。
     */
    private String buildAnswer(TaskRetrievalService.RetrievalResult retrievalResult) {
        if (retrievalResult.getChunks() == null || retrievalResult.getChunks().isEmpty()) {
            return "";
        }
        List<String> fragments = new ArrayList<>();
        for (TaskRetrievalService.RetrievedChunk chunk : retrievalResult.getChunks()) {
            if (chunk == null) {
                continue;
            }
            String text = firstNonBlank(chunk.getSnippet(), chunk.getContent());
            if (StringUtils.hasText(text)) {
                fragments.add(text.trim());
            }
            if (fragments.size() >= 3) {
                break;
            }
        }
        return String.join("；", fragments);
    }

    /**
     * 命中文档 ID 统一以 documentKey 对外暴露，
     * 这样调用方能回指到知识文档，但不会被内部切片结构或完整 RetrievalResult 绑定。
     */
    private List<String> collectHitDocumentIds(TaskRetrievalService.RetrievalResult retrievalResult) {
        LinkedHashSet<String> hitDocumentIds = new LinkedHashSet<>();
        if (retrievalResult.getChunks() == null) {
            return List.of();
        }
        for (TaskRetrievalService.RetrievedChunk chunk : retrievalResult.getChunks()) {
            if (chunk != null && StringUtils.hasText(chunk.getDocumentKey())) {
                hitDocumentIds.add(chunk.getDocumentKey().trim());
            }
        }
        return new ArrayList<>(hitDocumentIds);
    }

    /**
     * 对话和审计链路都需要稳定回指 evidenceId，
     * 因此 facade 要把命中文档对应的 evidenceId 一并投影出来，而不是只暴露 documentKey。
     */
    private List<String> collectHitEvidenceIds(TaskRetrievalService.RetrievalResult retrievalResult) {
        LinkedHashSet<String> hitEvidenceIds = new LinkedHashSet<>();
        if (retrievalResult.getChunks() == null) {
            return List.of();
        }
        for (TaskRetrievalService.RetrievedChunk chunk : retrievalResult.getChunks()) {
            if (chunk != null && StringUtils.hasText(chunk.getEvidenceId())) {
                hitEvidenceIds.add(chunk.getEvidenceId().trim());
            }
        }
        return new ArrayList<>(hitEvidenceIds);
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }
}
