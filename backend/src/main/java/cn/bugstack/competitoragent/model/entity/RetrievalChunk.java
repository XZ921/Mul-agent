package cn.bugstack.competitoragent.model.entity;

import cn.bugstack.competitoragent.model.converter.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索切片对象。
 * <p>
 * 它只负责“可召回的文本载体”，不直接代表最终事实。
 * 因此切片必须始终保留 `knowledgeDocumentId`、`documentKey` 和 `sourceUrls`，
 * 这样后续命中任意一片内容都能回指到文档与原始来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "retrieval_chunk",
        indexes = {
                @Index(name = "idx_retrieval_chunk_task_id", columnList = "task_id"),
                @Index(name = "idx_retrieval_chunk_scope_ref_key", columnList = "scope_ref_key"),
                @Index(name = "idx_retrieval_chunk_knowledge_domain_key", columnList = "knowledge_domain_key"),
                @Index(name = "idx_retrieval_chunk_document_id", columnList = "knowledge_document_id"),
                @Index(name = "idx_retrieval_chunk_document_key", columnList = "document_key")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_retrieval_chunk_chunk_key", columnNames = {"chunk_key"})
        })
public class RetrievalChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Task 5.3 起 taskId 只在任务级召回中是必填。
     * 领域级 / 组织级资料改由 retrievalScope + scopeRefKey + knowledgeDomainKey 表达归属。
     */
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "knowledge_document_id", nullable = false)
    private Long knowledgeDocumentId;

    @Column(name = "competitor_name", nullable = false, length = 100)
    private String competitorName;

    @Column(name = "evidence_id", nullable = false, length = 100)
    private String evidenceId;

    @Column(name = "document_key", nullable = false, length = 160)
    private String documentKey;

    /**
     * 显式记录这个切片属于哪一层正式召回边界，
     * 避免后续检索时只能靠 documentKey 或 taskId 猜测作用域。
     */
    @Column(name = "retrieval_scope", nullable = false, length = 40)
    private String retrievalScope;

    /**
     * 作用域引用键：
     * 1. `TASK` 层存 taskId 的字符串；
     * 2. `DOMAIN` 层存 knowledgeDomainKey；
     * 3. `ORGANIZATION` 层固定存 `ORGANIZATION`。
     */
    @Column(name = "scope_ref_key", nullable = false, length = 160)
    private String scopeRefKey;

    /**
     * 组织级资料若同时属于某个知识域，需要保留 knowledgeDomainKey，
     * 这样 Domain RAG 在回退时才能只命中本领域的组织沉淀，而不是把所有组织资料混在一起。
     */
    @Column(name = "knowledge_domain_key", length = 120)
    private String knowledgeDomainKey;

    @Column(name = "chunk_key", nullable = false, length = 200)
    private String chunkKey;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "start_offset", nullable = false)
    private Integer startOffset;

    @Column(name = "end_offset", nullable = false)
    private Integer endOffset;

    @Column(name = "source_category", nullable = false, length = 50)
    private String sourceCategory;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "snippet", columnDefinition = "TEXT")
    private String snippet;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "issue_flags", columnDefinition = "TEXT")
    private List<String> issueFlags;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
        if (this.issueFlags == null) {
            this.issueFlags = new ArrayList<>();
        }
        if (this.retrievalScope == null || this.retrievalScope.isBlank()) {
            this.retrievalScope = "TASK";
        }
        if (this.scopeRefKey == null || this.scopeRefKey.isBlank()) {
            this.scopeRefKey = this.taskId == null ? "ORGANIZATION" : String.valueOf(this.taskId);
        }
    }
}
