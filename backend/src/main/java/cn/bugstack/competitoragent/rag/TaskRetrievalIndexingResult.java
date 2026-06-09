package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务级知识沉淀结果。
 * <p>
 * Collector 只关心这次证据是否已经被成功沉淀为知识文档、切片和索引，
 * 因此通过一个稳定结果对象把成功产物、失败原因和问题标记统一带回上游。
 */
public record TaskRetrievalIndexingResult(KnowledgeDocument knowledgeDocument,
                                          List<RetrievalChunk> retrievalChunks,
                                          RetrievalIndex retrievalIndex,
                                          List<String> issueFlags,
                                          String failureReason) {

    public static TaskRetrievalIndexingResult success(KnowledgeDocument knowledgeDocument,
                                                      List<RetrievalChunk> retrievalChunks,
                                                      RetrievalIndex retrievalIndex,
                                                      List<String> issueFlags) {
        return new TaskRetrievalIndexingResult(
                knowledgeDocument,
                retrievalChunks == null ? List.of() : new ArrayList<>(retrievalChunks),
                retrievalIndex,
                issueFlags == null ? List.of() : new ArrayList<>(issueFlags),
                null
        );
    }

    public static TaskRetrievalIndexingResult failed(KnowledgeDocument knowledgeDocument,
                                                     RetrievalIndex retrievalIndex,
                                                     List<String> issueFlags,
                                                     String failureReason) {
        return new TaskRetrievalIndexingResult(
                knowledgeDocument,
                List.of(),
                retrievalIndex,
                issueFlags == null ? List.of() : new ArrayList<>(issueFlags),
                failureReason
        );
    }

    public boolean succeeded() {
        return knowledgeDocument != null
                && "READY".equalsIgnoreCase(knowledgeDocument.getStatus())
                && retrievalIndex != null
                && "READY".equalsIgnoreCase(retrievalIndex.getStatus());
    }
}
