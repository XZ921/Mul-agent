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
 * 记忆复用与写回的审计留痕。
 * <p>
 * 它不仅记录“用了哪条记忆”，还要记录为什么能用、属于哪个版本边界、
 * 以及在什么条件下应当失效，避免审计时只能依赖日志倒推。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "memory_reuse_record",
        indexes = {
                @Index(name = "idx_memory_reuse_record_task_id", columnList = "task_id"),
                @Index(name = "idx_memory_reuse_record_source_task_id", columnList = "source_task_id"),
                @Index(name = "idx_memory_reuse_record_source_memory_layer", columnList = "source_memory_layer")
        })
public class MemoryReuseRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "consumer_node_name", nullable = false, length = 100)
    private String consumerNodeName;

    @Column(name = "source_memory_layer", nullable = false, length = 40)
    private String sourceMemoryLayer;

    @Column(name = "source_object_type", nullable = false, length = 60)
    private String sourceObjectType;

    @Column(name = "source_record_id", nullable = false)
    private Long sourceRecordId;

    @Column(name = "source_task_id")
    private Long sourceTaskId;

    @Column(name = "source_summary", columnDefinition = "TEXT")
    private String sourceSummary;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", nullable = false, columnDefinition = "TEXT")
    private List<String> sourceUrls;

    /**
     * 复用说明必须结构化落库，避免后续只能看到一段摘要却不知道为何允许复用。
     */
    @Column(name = "reuse_reason", columnDefinition = "TEXT")
    private String reuseReason;

    /**
     * 记录当前复用或写回引用的是哪一个版本来源的记忆。
     */
    @Column(name = "version_source", nullable = false, length = 120)
    private String versionSource;

    /**
     * 记录当前复用说明对应的失效边界。
     */
    @Column(name = "invalidation_scope", nullable = false, length = 40)
    private String invalidationScope;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        applyDefaults();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        applyDefaults();
    }

    /**
     * 统一补齐最小治理默认值，保证留痕对象不会因为调用方遗漏而丢边界信息。
     */
    private void applyDefaults() {
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
        if (this.versionSource == null || this.versionSource.isBlank()) {
            this.versionSource = "UNSPECIFIED";
        }
        if (this.invalidationScope == null || this.invalidationScope.isBlank()) {
            this.invalidationScope = "MANUAL_REVIEW";
        }
    }
}
