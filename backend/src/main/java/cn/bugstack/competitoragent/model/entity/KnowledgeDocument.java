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
 * 任务级知识文档。
 * <p>
 * 它承接 `EvidenceSource` 之后的“可检索知识载体”角色：
 * 1. 记录任务内某条证据沉淀出的标准化正文；
 * 2. 保留来源分类、原始证据编号与 `sourceUrls` 回指；
 * 3. 为后续切片、索引、RAG 审计提供稳定主键和版本号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_document",
        indexes = {
                @Index(name = "idx_knowledge_document_task_id", columnList = "task_id"),
                @Index(name = "idx_knowledge_document_evidence_id", columnList = "evidence_id"),
                @Index(name = "idx_knowledge_document_document_key", columnList = "document_key")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_knowledge_document_task_evidence", columnNames = {"task_id", "evidence_id"})
        })
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 任务级知识继续复用 taskId；
     * 组织级接入资料在 Task 5.2.b 起允许为空，改由 knowledgeScope + knowledgeDomainKey 表达归属。
     */
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "competitor_name", nullable = false, length = 100)
    private String competitorName;

    @Column(name = "evidence_id", nullable = false, length = 100)
    private String evidenceId;

    @Column(name = "document_key", nullable = false, length = 160)
    private String documentKey;

    @Column(name = "knowledge_scope", nullable = false, length = 40)
    private String knowledgeScope;

    @Column(name = "knowledge_domain_id")
    private Long knowledgeDomainId;

    @Column(name = "knowledge_domain_key", length = 120)
    private String knowledgeDomainKey;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_category", nullable = false, length = 50)
    private String sourceCategory;

    @Column(name = "discovery_method", length = 50)
    private String discoveryMethod;

    @Column(name = "source_domain", length = 255)
    private String sourceDomain;

    /**
     * 生命周期和可信度是组织级资料治理的正式字段，
     * 后续统一接入、跨层召回和审计都会依赖这两个元数据做边界判断。
     */
    @Column(name = "source_lifecycle", nullable = false, length = 40)
    private String sourceLifecycle;

    @Column(name = "trust_level", nullable = false, length = 40)
    private String trustLevel;

    @Column(name = "connector_key", length = 120)
    private String connectorKey;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "snippet", columnDefinition = "TEXT")
    private String snippet;

    @Column(name = "cleaned_text", columnDefinition = "TEXT")
    private String cleanedText;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "issue_flags", columnDefinition = "TEXT")
    private List<String> issueFlags;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

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
        if (this.documentVersion == null) {
            this.documentVersion = 1;
        }
        if (this.knowledgeScope == null || this.knowledgeScope.isBlank()) {
            this.knowledgeScope = "TASK";
        }
        if (this.sourceLifecycle == null || this.sourceLifecycle.isBlank()) {
            this.sourceLifecycle = "ACTIVE";
        }
        if (this.trustLevel == null || this.trustLevel.isBlank()) {
            this.trustLevel = "CURATED";
        }
        if (this.status == null || this.status.isBlank()) {
            this.status = "PROCESSING";
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
    }
}
