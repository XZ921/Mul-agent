package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetrievalChunkRepository extends JpaRepository<RetrievalChunk, Long> {

    void deleteByKnowledgeDocumentId(Long knowledgeDocumentId);

    /**
     * 任务级查询入口继续保留，保证当前 Task RAG 主链不被本次字段扩展打断。
     */
    List<RetrievalChunk> findByTaskIdOrderByKnowledgeDocumentIdAscChunkIndexAsc(Long taskId);

    /**
     * 三层召回边界正式化后，切片查询也必须能按作用域引用键直接检索，
     * 避免后续 Domain / Organization RAG 只能靠 taskId 旁路或内存过滤模拟。
     */
    List<RetrievalChunk> findByRetrievalScopeAndScopeRefKeyOrderByKnowledgeDocumentIdAscChunkIndexAsc(
            String retrievalScope,
            String scopeRefKey
    );

    /**
     * 领域级召回需要显式按知识域过滤候选切片，
     * 仓储层保留这个入口，后续服务层才能稳定表达“只在当前域内召回”。
     */
    List<RetrievalChunk> findByRetrievalScopeAndKnowledgeDomainKeyOrderByKnowledgeDocumentIdAscChunkIndexAsc(
            String retrievalScope,
            String knowledgeDomainKey
    );
}
