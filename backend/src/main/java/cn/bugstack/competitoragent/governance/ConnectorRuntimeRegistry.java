package cn.bugstack.competitoragent.governance;

import cn.bugstack.competitoragent.model.entity.ConnectorRuntimeLease;
import cn.bugstack.competitoragent.repository.ConnectorRuntimeLeaseRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 连接器运行时注册表。
 * <p>
 * 当前阶段先把“同一组织下同一连接器是否已被占用”的判定和登记统一起来，
 * 后续各类连接器同步链路只需要向这里申请租约，而不必各自维护散落的忙碌判断。
 */
@Service
public class ConnectorRuntimeRegistry {

    private final ConnectorRuntimeLeaseRepository connectorRuntimeLeaseRepository;

    public ConnectorRuntimeRegistry(ConnectorRuntimeLeaseRepository connectorRuntimeLeaseRepository) {
        this.connectorRuntimeLeaseRepository = connectorRuntimeLeaseRepository;
    }

    /**
     * 在连接器真正开始运行前申请一个正式租约。
     * 如果已有租约占住同一个组织下的同一运行槽位，就返回统一阻断结果而不是让调用方自己拼消息。
     */
    public QuotaDecision acquireLease(String organizationKey,
                                      String connectorKey,
                                      String runtimeSlot,
                                      String leaseOwner,
                                      Duration ttl,
                                      List<String> sourceUrls) {
        recycleExpiredLease(organizationKey, connectorKey, runtimeSlot);
        ConnectorRuntimeLease existingLease =
                connectorRuntimeLeaseRepository
                        .findFirstByOrganizationKeyAndConnectorKeyAndRuntimeSlotAndLeaseStatusOrderByAcquiredAtDescIdDesc(
                                organizationKey,
                                connectorKey,
                                runtimeSlot,
                                "HELD"
                        )
                        .orElse(null);

        if (existingLease != null) {
            return QuotaDecision.deny(
                    "CONNECTOR_BUSY",
                    "连接器运行时槽位已被占用：" + connectorKey + " / " + runtimeSlot,
                    organizationKey,
                    "CONNECTOR",
                    connectorKey + ":" + runtimeSlot,
                    1,
                    0,
                    existingLease.getLeaseOwner(),
                    normalizeSourceUrls(sourceUrls)
            );
        }

        LocalDateTime acquiredAt = LocalDateTime.now();
        Duration normalizedTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(30) : ttl;
        String leaseToken = buildLeaseToken(organizationKey, connectorKey, runtimeSlot);
        ConnectorRuntimeLease savedLease = connectorRuntimeLeaseRepository.save(ConnectorRuntimeLease.builder()
                .organizationKey(organizationKey)
                .connectorKey(connectorKey)
                .runtimeSlot(runtimeSlot)
                .leaseOwner(leaseOwner)
                .leaseStatus("HELD")
                .leaseToken(leaseToken)
                .sourceUrls(normalizeSourceUrls(sourceUrls))
                .acquiredAt(acquiredAt)
                .expiresAt(acquiredAt.plus(normalizedTtl))
                .build());

        return QuotaDecision.allow(
                "LEASE_ACQUIRED",
                "连接器运行时租约申请成功",
                organizationKey,
                "CONNECTOR",
                connectorKey + ":" + runtimeSlot,
                1,
                1,
                savedLease.getLeaseToken(),
                savedLease.getSourceUrls()
        );
    }

    /**
     * 连接器在异常退出或超时后，历史租约可能仍停留在 HELD。
     * 新租约申请前先把已经过期的记录标记为 EXPIRED，避免僵尸占位永久阻断后续同步。
     */
    private void recycleExpiredLease(String organizationKey, String connectorKey, String runtimeSlot) {
        ConnectorRuntimeLease existingLease =
                connectorRuntimeLeaseRepository
                        .findFirstByOrganizationKeyAndConnectorKeyAndRuntimeSlotAndLeaseStatusOrderByAcquiredAtDescIdDesc(
                                organizationKey,
                                connectorKey,
                                runtimeSlot,
                                "HELD"
                        )
                        .orElse(null);
        if (existingLease == null || existingLease.getExpiresAt() == null) {
            return;
        }
        if (existingLease.getExpiresAt().isAfter(LocalDateTime.now())) {
            return;
        }
        existingLease.setLeaseStatus("EXPIRED");
        connectorRuntimeLeaseRepository.save(existingLease);
    }

    /**
     * 用显式 token 标记同一条租约，方便后续释放或审计时能直接定位到正式占位记录。
     */
    private String buildLeaseToken(String organizationKey, String connectorKey, String runtimeSlot) {
        return "lease-" + organizationKey + "-" + connectorKey + "-" + runtimeSlot + "-" + UUID.randomUUID();
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        return sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
    }
}
