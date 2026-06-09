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
import jakarta.persistence.PreUpdate;
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
 * 任务级检索索引元数据。
 * <p>
 * 当前阶段不直接绑定具体向量库实现，而是先沉淀“某个知识文档已经完成切片并可被检索”的索引事实，
 * 为 `Task 4.5` 的正式召回链路保留稳定的任务内索引主键和状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "retrieval_index",
        indexes = {
                @Index(name = "idx_retrieval_index_task_id", columnList = "task_id"),
                @Index(name = "idx_retrieval_index_scope_ref_key", columnList = "scope_ref_key"),
                @Index(name = "idx_retrieval_index_knowledge_domain_key", columnList = "knowledge_domain_key"),
                @Index(name = "idx_retrieval_index_document_id", columnList = "knowledge_document_id"),
                @Index(name = "idx_retrieval_index_index_key", columnList = "index_key")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_retrieval_index_index_key", columnNames = {"index_key"})
        })
public class RetrievalIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Task 5.3 起 taskId 只约束任务级检索。
     * 对于领域级 / 组织级索引，统一改由 retrievalScope + scopeRefKey 表达归属。
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
     * 正式召回作用域，和 indexScope 的区别是：
     * indexScope 仍保留“索引用途”语义，
     * retrievalScope 则显式表达这份索引在哪一层召回边界上生效。
     */
    @Column(name = "retrieval_scope", nullable = false, length = 40)
    private String retrievalScope;

    /**
     * 作用域引用键：
     * 1. `TASK` 层为 taskId 字符串；
     * 2. `DOMAIN` 层为 knowledgeDomainKey；
     * 3. `ORGANIZATION` 层固定为 `ORGANIZATION`。
     */
    @Column(name = "scope_ref_key", nullable = false, length = 160)
    private String scopeRefKey;

    /**
     * 保留所属知识域，供 Domain RAG 与 Organization RAG 做分层过滤与摘要说明。
     */
    @Column(name = "knowledge_domain_key", length = 120)
    private String knowledgeDomainKey;

    @Column(name = "index_key", nullable = false, length = 200)
    private String indexKey;

    @Column(name = "index_scope", nullable = false, length = 50)
    private String indexScope;

    @Column(name = "source_category", nullable = false, length = 50)
    private String sourceCategory;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "issue_flags", columnDefinition = "TEXT")
    private List<String> issueFlags;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
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

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
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
