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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接器资料同步记录。
 * <p>
 * 当前阶段只记录“某次同步定义和结果摘要”，
 * 不提前扩展到 Task 5.8 的运行时占位、配额和调度治理，
 * 这样既能保证资料接入可追溯，也不会越权实现后续运行时系统。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "connector_sync_record",
        indexes = {
                @Index(name = "idx_connector_sync_record_domain_id", columnList = "knowledge_domain_id"),
                @Index(name = "idx_connector_sync_record_connector_key", columnList = "connector_key"),
                @Index(name = "idx_connector_sync_record_status", columnList = "sync_status")
        })
public class ConnectorSyncRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_domain_id")
    private Long knowledgeDomainId;

    @Column(name = "connector_key", nullable = false, length = 120)
    private String connectorKey;

    @Column(name = "connector_type", nullable = false, length = 60)
    private String connectorType;

    @Column(name = "connector_label", nullable = false, length = 120)
    private String connectorLabel;

    @Column(name = "trigger_type", nullable = false, length = 40)
    private String triggerType;

    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus;

    @Column(name = "source_category", nullable = false, length = 50)
    private String sourceCategory;

    /**
     * 即使当前还是连接器定义阶段，也要保留 sourceUrls，
     * 这样后续文档入库和证据回指不会丢失原始来源入口。
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Column(name = "synced_document_count", nullable = false)
    private Integer syncedDocumentCount;

    @Column(name = "request_payload", columnDefinition = "TEXT")
    private String requestPayload;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

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
        if (this.triggerType == null || this.triggerType.isBlank()) {
            this.triggerType = "MANUAL";
        }
        if (this.syncStatus == null || this.syncStatus.isBlank()) {
            this.syncStatus = "PENDING";
        }
        if (this.sourceCategory == null || this.sourceCategory.isBlank()) {
            this.sourceCategory = "AUTHENTICATED_SOURCES";
        }
        if (this.syncedDocumentCount == null) {
            this.syncedDocumentCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
    }
}
