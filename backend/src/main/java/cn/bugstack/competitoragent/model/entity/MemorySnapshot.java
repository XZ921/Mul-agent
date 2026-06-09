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
 * 任务运行期的记忆快照。
 * <p>
 * 它承接当前任务实际消费过的检索上下文与阶段结论，
 * 用于后续节点复用、回放审计以及任务重跑时的边界判断。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "memory_snapshot",
        indexes = {
                @Index(name = "idx_memory_snapshot_task_id", columnList = "task_id"),
                @Index(name = "idx_memory_snapshot_plan_version_id", columnList = "plan_version_id"),
                @Index(name = "idx_memory_snapshot_node_name", columnList = "node_name")
        })
public class MemorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "plan_version_id")
    private Long planVersionId;

    @Column(name = "branch_key", length = 120)
    private String branchKey;

    @Column(name = "node_name", nullable = false, length = 100)
    private String nodeName;

    @Column(name = "snapshot_type", nullable = false, length = 40)
    private String snapshotType;

    @Column(name = "memory_layer", nullable = false, length = 40)
    private String memoryLayer;

    @Column(name = "query_text", columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "gap_summary", columnDefinition = "TEXT")
    private String gapSummary;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "issue_flags", columnDefinition = "TEXT")
    private List<String> issueFlags;

    /**
     * 标记这条记忆来自哪个版本边界，避免旧结论在新计划里被误复用。
     */
    @Column(name = "version_source", nullable = false, length = 120)
    private String versionSource;

    /**
     * 标记这条记忆应该在哪个边界下失效，例如任务重跑、计划变更或领域刷新。
     */
    @Column(name = "invalidation_scope", nullable = false, length = 40)
    private String invalidationScope;

    /**
     * 标记触发失效的主因，帮助后续解释为什么该记忆不能继续复用。
     */
    @Column(name = "invalidation_reason", nullable = false, length = 120)
    private String invalidationReason;

    /**
     * 直接保留结构化上下文原文，避免当前阶段过早拆散导致回放困难。
     */
    @Column(name = "context_payload", columnDefinition = "TEXT")
    private String contextPayload;

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
     * 统一在实体入口补齐治理默认值，
     * 避免调用方遗漏字段后把“无版本、无失效规则”的记忆写进库里。
     */
    private void applyDefaults() {
        if (this.memoryLayer == null || this.memoryLayer.isBlank()) {
            this.memoryLayer = "SHORT_TERM";
        }
        if (this.snapshotType == null || this.snapshotType.isBlank()) {
            this.snapshotType = "TASK_RAG";
        }
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
        if (this.issueFlags == null) {
            this.issueFlags = new ArrayList<>();
        }
        if (this.versionSource == null || this.versionSource.isBlank()) {
            this.versionSource = "UNSPECIFIED";
        }
        if (this.invalidationScope == null || this.invalidationScope.isBlank()) {
            this.invalidationScope = "MANUAL_REVIEW";
        }
        if (this.invalidationReason == null || this.invalidationReason.isBlank()) {
            this.invalidationReason = "NOT_EVALUATED";
        }
    }
}
