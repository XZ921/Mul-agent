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
 * 组织级配额快照。
 * <p>
 * Task 5.8.a 先沉淀“组织当前有哪些配额、用了多少、预留了多少”的正式仓储对象，
 * 这样后续统一配额策略与占位释放链路才有稳定、可追溯的持久化基础。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "organization_quota_snapshot",
        indexes = {
                @Index(name = "idx_org_quota_snapshot_org_key", columnList = "organization_key"),
                @Index(name = "idx_org_quota_snapshot_scope", columnList = "quota_scope"),
                @Index(name = "idx_org_quota_snapshot_status", columnList = "snapshot_status")
        })
public class OrganizationQuotaSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_key", nullable = false, length = 120)
    private String organizationKey;

    @Column(name = "quota_scope", nullable = false, length = 40)
    private String quotaScope;

    @Column(name = "quota_key", nullable = false, length = 120)
    private String quotaKey;

    @Column(name = "limit_value", nullable = false)
    private Integer limitValue;

    @Column(name = "used_value", nullable = false)
    private Integer usedValue;

    @Column(name = "reserved_value", nullable = false)
    private Integer reservedValue;

    @Column(name = "quota_unit", nullable = false, length = 40)
    private String quotaUnit;

    @Column(name = "snapshot_status", nullable = false, length = 20)
    private String snapshotStatus;

    /**
     * 即使这里是治理对象，也保留 sourceUrls，
     * 方便后续解释“这条配额基线来自哪份规则或运维配置”。
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDateTime snapshotAt;

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
     * 统一在实体入库前补齐治理默认值，
     * 避免调用方先接入时因为遗漏字段，把无状态的配额快照写成脏数据。
     */
    private void applyDefaults() {
        if (this.limitValue == null) {
            this.limitValue = 0;
        }
        if (this.usedValue == null) {
            this.usedValue = 0;
        }
        if (this.reservedValue == null) {
            this.reservedValue = 0;
        }
        if (this.quotaScope == null || this.quotaScope.isBlank()) {
            this.quotaScope = "TASK";
        }
        if (this.quotaUnit == null || this.quotaUnit.isBlank()) {
            this.quotaUnit = "COUNT";
        }
        if (this.snapshotStatus == null || this.snapshotStatus.isBlank()) {
            this.snapshotStatus = "ACTIVE";
        }
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
        if (this.snapshotAt == null) {
            this.snapshotAt = LocalDateTime.now();
        }
    }
}
