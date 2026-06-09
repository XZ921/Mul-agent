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
 * 连接器运行时租约。
 * <p>
 * Task 5.8.a 先把“某个组织下某个连接器当前被谁占用”的正式租约对象沉淀下来，
 * 这样后续注册表和统一配额判断才能围绕同一份运行时事实工作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "connector_runtime_lease",
        indexes = {
                @Index(name = "idx_connector_runtime_lease_org_key", columnList = "organization_key"),
                @Index(name = "idx_connector_runtime_lease_connector_key", columnList = "connector_key"),
                @Index(name = "idx_connector_runtime_lease_status", columnList = "lease_status")
        })
public class ConnectorRuntimeLease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_key", nullable = false, length = 120)
    private String organizationKey;

    @Column(name = "connector_key", nullable = false, length = 120)
    private String connectorKey;

    @Column(name = "runtime_slot", nullable = false, length = 60)
    private String runtimeSlot;

    @Column(name = "lease_owner", nullable = false, length = 120)
    private String leaseOwner;

    @Column(name = "lease_status", nullable = false, length = 20)
    private String leaseStatus;

    @Column(name = "lease_token", nullable = false, length = 160)
    private String leaseToken;

    /**
     * 租约同样保留 sourceUrls，
     * 便于后续解释当前占位关联的是哪一类连接器来源或哪份接入文档。
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Column(name = "acquired_at", nullable = false)
    private LocalDateTime acquiredAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

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
     * 统一给运行时租约补齐最小默认值，
     * 避免注册表尚未接入前出现“没有状态、没有时间边界”的悬空占位记录。
     */
    private void applyDefaults() {
        if (this.runtimeSlot == null || this.runtimeSlot.isBlank()) {
            this.runtimeSlot = "DEFAULT";
        }
        if (this.leaseStatus == null || this.leaseStatus.isBlank()) {
            this.leaseStatus = "HELD";
        }
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
        if (this.acquiredAt == null) {
            this.acquiredAt = LocalDateTime.now();
        }
        if (this.expiresAt == null) {
            this.expiresAt = this.acquiredAt.plusMinutes(30);
        }
    }
}
