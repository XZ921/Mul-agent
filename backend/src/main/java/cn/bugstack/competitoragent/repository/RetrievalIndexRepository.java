package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetrievalIndexRepository extends JpaRepository<RetrievalIndex, Long> {

    void deleteByKnowledgeDocumentId(Long knowledgeDocumentId);

    void deleteByTaskId(Long taskId);

    /**
     * 任务级检索仍保留 taskId 查询入口，
     * 供当前 Task RAG MVP 在升级三层召回前继续稳定工作。
     */
    List<RetrievalIndex> findByTaskIdOrderByIdAsc(Long taskId);

    /**
     * 三层召回治理统一按 retrievalScope + scopeRefKey 表达边界。
     * 这样后续 Domain / Organization RAG 可以直接复用正式索引查询入口，
     * 而不是回退到 taskId 或 documentKey 的旁路拼接。
     */
    List<RetrievalIndex> findByRetrievalScopeAndScopeRefKeyOrderByIdAsc(String retrievalScope, String scopeRefKey);

    /**
     * 某些召回策略需要先按知识域聚合候选索引，
     * 因此仓储层必须显式暴露 knowledgeDomainKey 维度的正式查询入口。
     */
    List<RetrievalIndex> findByRetrievalScopeAndKnowledgeDomainKeyOrderByIdAsc(String retrievalScope,
                                                                               String knowledgeDomainKey);
}
