package cn.bugstack.competitoragent.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
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

/**
 * 竞品知识沉淀实体。
 * <p>
 * Task 5.4 以后它不再只是“抽取结果表”，还承担跨任务可复用领域知识的载体职责，
 * 因此需要显式保留记忆层级、版本来源和失效规则。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "competitor_knowledge", indexes = {
        @Index(name = "idx_knowledge_task_id", columnList = "taskId"),
        @Index(name = "idx_knowledge_competitor", columnList = "competitorName")
})
@Schema(description = "Competitor knowledge schema")
public class CompetitorKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Primary key", example = "1")
    private Long id;

    @Column(nullable = false)
    @Schema(description = "Task ID", example = "1")
    private Long taskId;

    @Column(nullable = false, length = 100)
    @Schema(description = "Competitor name", example = "Notion AI")
    private String competitorName;

    @Column(length = 2048)
    @Schema(description = "Official URL", example = "https://www.notion.so")
    private String officialUrl;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Summary")
    private String summary;

    @Column(name = "memory_layer", nullable = false, length = 40)
    @Schema(description = "Memory layer", example = "DOMAIN")
    private String memoryLayer;

    @Column(name = "snapshot_scope", nullable = false, length = 40)
    @Schema(description = "Snapshot scope", example = "TASK")
    private String snapshotScope;

    @Column(name = "producer_node_name", length = 120)
    @Schema(description = "Producer node name", example = "extract_schema")
    private String producerNodeName;

    @Column(name = "plan_version_id")
    @Schema(description = "Plan version ID", example = "27")
    private Long planVersionId;

    @Column(name = "branch_key", length = 120)
    @Schema(description = "Plan branch key", example = "root")
    private String branchKey;

    @Column(length = 500)
    @Schema(description = "Positioning")
    private String positioning;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Target users JSON array")
    private String targetUsers;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Core features JSON")
    private String coreFeatures;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Pricing JSON")
    private String pricing;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Strengths JSON")
    private String strengths;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Weaknesses JSON")
    private String weaknesses;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Source objects JSON")
    private String sources;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Unique source URLs JSON array")
    private String sourceUrls;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Field-level evidence coverage JSON")
    private String evidenceCoverage;

    /**
     * 标记领域知识来自哪个任务版本边界，避免跨任务沉淀时新旧来源混淆。
     */
    @Column(name = "version_source", nullable = false, length = 120)
    @Schema(description = "Version source", example = "TASK_RAG@PLAN-22:analysis")
    private String versionSource;

    /**
     * 标记领域知识在哪个边界下应该被重新评估。
     */
    @Column(name = "invalidation_scope", nullable = false, length = 40)
    @Schema(description = "Invalidation scope", example = "DOMAIN_REFRESH")
    private String invalidationScope;

    /**
     * 标记领域知识失效的主要原因。
     */
    @Column(name = "invalidation_reason", nullable = false, length = 120)
    @Schema(description = "Invalidation reason", example = "SOURCE_EVIDENCE_CHANGED")
    private String invalidationReason;

    @Schema(description = "Extracted time")
    private LocalDateTime extractedAt;

    @Column(updatable = false)
    @Schema(description = "Created time")
    private LocalDateTime createdAt;

    @Schema(description = "Updated time")
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
     * 统一补齐领域记忆默认边界，避免写回时把无版本、无失效规则的知识沉进长期层。
     */
    private void applyDefaults() {
        if (this.memoryLayer == null || this.memoryLayer.isBlank()) {
            this.memoryLayer = "DOMAIN";
        }
        if (this.snapshotScope == null || this.snapshotScope.isBlank()) {
            this.snapshotScope = this.memoryLayer;
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
