package cn.bugstack.competitoragent.governance;

import cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot;
import cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 组织级配额策略。
 * <p>
 * 当前阶段先把“配额读取、剩余额度计算、预占位结果输出”集中到同一个策略对象中，
 * 让后续任务创建、模型调用、导出等链路都能复用同一套治理判断。
 */
@Service
public class OrganizationQuotaPolicy {

    private final OrganizationQuotaSnapshotRepository organizationQuotaSnapshotRepository;

    public OrganizationQuotaPolicy(OrganizationQuotaSnapshotRepository organizationQuotaSnapshotRepository) {
        this.organizationQuotaSnapshotRepository = organizationQuotaSnapshotRepository;
    }

    /**
     * 在正式运行前先做统一配额判定，并把本次请求需要的额度预留到快照里。
     * 这样后续链路即使还没全部接入，也能围绕同一份“已预占多少”的组织级事实继续扩展。
     */
    public QuotaDecision checkAndReserve(String organizationKey,
                                         String quotaScope,
                                         String quotaKey,
                                         int requestedUnits,
                                         List<String> sourceUrls) {
        int normalizedRequestedUnits = Math.max(requestedUnits, 0);
        Optional<OrganizationQuotaSnapshot> optionalSnapshot =
                organizationQuotaSnapshotRepository
                        .findFirstByOrganizationKeyAndQuotaScopeAndQuotaKeyAndSnapshotStatusOrderBySnapshotAtDescIdDesc(
                                organizationKey,
                                quotaScope,
                                quotaKey,
                                "ACTIVE"
                        );

        if (optionalSnapshot.isEmpty()) {
            return QuotaDecision.allow(
                    "NO_ACTIVE_QUOTA",
                    "当前组织未配置活动配额快照，先按放行处理",
                    organizationKey,
                    quotaScope,
                    quotaKey,
                    normalizedRequestedUnits,
                    Integer.MAX_VALUE,
                    null,
                    normalizeSourceUrls(sourceUrls)
            );
        }

        OrganizationQuotaSnapshot snapshot = optionalSnapshot.get();
        int availableUnits = Math.max(
                0,
                safeInt(snapshot.getLimitValue()) - safeInt(snapshot.getUsedValue()) - safeInt(snapshot.getReservedValue())
        );
        if (normalizedRequestedUnits > availableUnits) {
            return QuotaDecision.deny(
                    "BLOCKED_QUOTA_EXCEEDED",
                    "组织配额不足：" + quotaKey + " 剩余 " + availableUnits + "，无法再预留 " + normalizedRequestedUnits,
                    organizationKey,
                    quotaScope,
                    quotaKey,
                    normalizedRequestedUnits,
                    availableUnits,
                    null,
                    normalizeSourceUrls(sourceUrls)
            );
        }

        snapshot.setReservedValue(safeInt(snapshot.getReservedValue()) + normalizedRequestedUnits);
        snapshot.setSourceUrls(mergeSourceUrls(snapshot.getSourceUrls(), sourceUrls));
        organizationQuotaSnapshotRepository.save(snapshot);

        return QuotaDecision.allow(
                "ALLOWED_RESERVED",
                "组织配额校验通过，已为 " + quotaKey + " 预留 " + normalizedRequestedUnits,
                organizationKey,
                quotaScope,
                quotaKey,
                normalizedRequestedUnits,
                availableUnits - normalizedRequestedUnits,
                null,
                snapshot.getSourceUrls()
        );
    }

    /**
     * 故障恢复、失败重试或补偿收口后，需要显式释放先前预留的组织级额度。
     * 否则 reservedValue 会长期滞留，后续请求会被错误判定成“额度始终不足”。
     */
    public QuotaDecision releaseReservation(String organizationKey,
                                            String quotaScope,
                                            String quotaKey,
                                            int requestedUnits,
                                            List<String> sourceUrls) {
        int normalizedRequestedUnits = Math.max(requestedUnits, 0);
        Optional<OrganizationQuotaSnapshot> optionalSnapshot =
                organizationQuotaSnapshotRepository
                        .findFirstByOrganizationKeyAndQuotaScopeAndQuotaKeyAndSnapshotStatusOrderBySnapshotAtDescIdDesc(
                                organizationKey,
                                quotaScope,
                                quotaKey,
                                "ACTIVE"
                        );

        if (optionalSnapshot.isEmpty()) {
            return QuotaDecision.allow(
                    "NO_ACTIVE_QUOTA",
                    "当前组织未配置活动配额快照，释放请求按空操作处理",
                    organizationKey,
                    quotaScope,
                    quotaKey,
                    normalizedRequestedUnits,
                    Integer.MAX_VALUE,
                    null,
                    normalizeSourceUrls(sourceUrls)
            );
        }

        OrganizationQuotaSnapshot snapshot = optionalSnapshot.get();
        int nextReservedValue = Math.max(0, safeInt(snapshot.getReservedValue()) - normalizedRequestedUnits);
        snapshot.setReservedValue(nextReservedValue);
        snapshot.setSourceUrls(mergeSourceUrls(snapshot.getSourceUrls(), sourceUrls));
        organizationQuotaSnapshotRepository.save(snapshot);

        int availableUnits = Math.max(
                0,
                safeInt(snapshot.getLimitValue()) - safeInt(snapshot.getUsedValue()) - nextReservedValue
        );
        return QuotaDecision.allow(
                "RELEASED_RESERVED",
                "组织配额预留已释放 " + normalizedRequestedUnits + "，当前剩余可用额度 " + availableUnits,
                organizationKey,
                quotaScope,
                quotaKey,
                normalizedRequestedUnits,
                availableUnits,
                null,
                snapshot.getSourceUrls()
        );
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 统一在治理层合并来源，确保后续解释“为什么形成这条配额基线 / 这次预留来自哪里”时仍可追溯。
     */
    private List<String> mergeSourceUrls(List<String> existingSourceUrls, List<String> incomingSourceUrls) {
        Set<String> merged = new LinkedHashSet<>();
        if (existingSourceUrls != null) {
            merged.addAll(existingSourceUrls);
        }
        if (incomingSourceUrls != null) {
            merged.addAll(incomingSourceUrls);
        }
        return new ArrayList<>(merged);
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        return sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
    }
}
