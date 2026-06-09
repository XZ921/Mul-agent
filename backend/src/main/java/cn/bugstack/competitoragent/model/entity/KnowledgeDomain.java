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
 * 组织级知识域实体。
 * <p>
 * 这个对象负责承接“资料应该沉淀到哪个知识域”的稳定语义，
 * 让后续上传资料、连接器资料和 AI 发现资料都能在统一知识边界内落库，
 * 而不是临时散落到多个隐藏表或额外仓库中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_domain",
        indexes = {
                @Index(name = "idx_knowledge_domain_status", columnList = "status"),
                @Index(name = "idx_knowledge_domain_owner_scope", columnList = "owner_scope")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_knowledge_domain_domain_key", columnNames = {"domain_key"})
        })
public class KnowledgeDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain_key", nullable = false, length = 120)
    private String domainKey;

    @Column(name = "domain_name", nullable = false, length = 120)
    private String domainName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "domain_type", nullable = false, length = 40)
    private String domainType;

    @Column(name = "owner_scope", nullable = false, length = 40)
    private String ownerScope;

    @Column(name = "access_scope", nullable = false, length = 40)
    private String accessScope;

    @Column(name = "default_lifecycle", nullable = false, length = 40)
    private String defaultLifecycle;

    @Column(name = "default_trust_level", nullable = false, length = 40)
    private String defaultTrustLevel;

    /**
     * 允许接入的资料来源分类保留为列表，
     * 后续统一接入服务可以直接依据这个白名单限制不同知识域的来源边界。
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "allowed_source_categories", columnDefinition = "TEXT")
    private List<String> allowedSourceCategories;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.allowedSourceCategories == null) {
            this.allowedSourceCategories = new ArrayList<>();
        }
        if (this.domainType == null || this.domainType.isBlank()) {
            this.domainType = "ORGANIZATION";
        }
        if (this.ownerScope == null || this.ownerScope.isBlank()) {
            this.ownerScope = "ORGANIZATION";
        }
        if (this.accessScope == null || this.accessScope.isBlank()) {
            this.accessScope = "TEAM_SHARED";
        }
        if (this.defaultLifecycle == null || this.defaultLifecycle.isBlank()) {
            this.defaultLifecycle = "ACTIVE";
        }
        if (this.defaultTrustLevel == null || this.defaultTrustLevel.isBlank()) {
            this.defaultTrustLevel = "CURATED";
        }
        if (this.status == null || this.status.isBlank()) {
            this.status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.allowedSourceCategories == null) {
            this.allowedSourceCategories = new ArrayList<>();
        }
    }
}
