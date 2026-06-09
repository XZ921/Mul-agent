package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByTaskIdAndEvidenceId(Long taskId, String evidenceId);

    List<KnowledgeDocument> findByTaskIdOrderByIdAsc(Long taskId);

    void deleteByTaskId(Long taskId);

    List<KnowledgeDocument> findByKnowledgeDomainKeyOrderByIdAsc(String knowledgeDomainKey);

    /**
     * 5.2.c 的 sourceUrls 回指要回答的是“这条来源是否真的被后续任务消费过”，
     * 因此这里先取回带 sourceUrls 的任务级文档候选，再在 Java 层做 URL 完整值精确匹配，
     * 避免 JSON 文本 LIKE 把同前缀或同子串的其他链接误算进消费链路。
     */
    @Query("""
            select document
            from KnowledgeDocument document
            where document.taskId is not null
              and document.sourceUrls is not null
            order by document.id asc
            """)
    List<KnowledgeDocument> findTaskDocumentsWithSourceUrlsOrderByIdAsc();

    /**
     * 保留现有仓储入口名称，避免服务层和测试层调用口径分裂；
     * 但内部已经从“文本 LIKE”升级为“sourceUrls 完整值匹配”。
     */
    default List<KnowledgeDocument> findTaskDocumentsBySourceUrlLikeOrderByIdAsc(@Param("sourceUrl") String sourceUrl) {
        if (!StringUtils.hasText(sourceUrl)) {
            return List.of();
        }
        String normalizedSourceUrl = sourceUrl.trim();
        return findTaskDocumentsWithSourceUrlsOrderByIdAsc().stream()
                .filter(document -> document != null && document.getSourceUrls() != null)
                .filter(document -> document.getSourceUrls().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .anyMatch(normalizedSourceUrl::equals))
                .toList();
    }
}
