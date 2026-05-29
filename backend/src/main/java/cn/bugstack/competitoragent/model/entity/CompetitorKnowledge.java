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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
