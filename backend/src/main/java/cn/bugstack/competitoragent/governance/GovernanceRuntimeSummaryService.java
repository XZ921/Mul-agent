package cn.bugstack.competitoragent.governance;

import cn.bugstack.competitoragent.model.dto.GovernanceRuntimeSummaryResponse;
import cn.bugstack.competitoragent.model.entity.ConnectorRuntimeLease;
import cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot;
import cn.bugstack.competitoragent.repository.ConnectorRuntimeLeaseRepository;
import cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 组织级治理运行摘要服务。
 * <p>
 * 这里把底层配额快照和连接器租约翻译成运维 / 操作者可读的摘要，
 * 让查询接口既能说明当前阻断原因，也不会暴露内部配额键或租约 token。
 */
@Service
public class GovernanceRuntimeSummaryService {

    private final OrganizationQuotaSnapshotRepository quotaSnapshotRepository;
    private final ConnectorRuntimeLeaseRepository connectorRuntimeLeaseRepository;

    public GovernanceRuntimeSummaryService(OrganizationQuotaSnapshotRepository quotaSnapshotRepository,
                                           ConnectorRuntimeLeaseRepository connectorRuntimeLeaseRepository) {
        this.quotaSnapshotRepository = quotaSnapshotRepository;
        this.connectorRuntimeLeaseRepository = connectorRuntimeLeaseRepository;
    }

    /**
     * 查询指定组织的治理运行状态摘要。
     * 当前任务只做最小可用查询：从已有持久化快照聚合活动配额和持有中连接器租约，
     * 并统一输出可读名称、等待状态、重试建议和 sourceUrls。
     */
    public GovernanceRuntimeSummaryResponse summarize(String organizationKey) {
        String normalizedOrganizationKey = StringUtils.hasText(organizationKey) ? organizationKey.trim() : GovernanceDefaults.DEFAULT_ORGANIZATION_KEY;
        List<GovernanceRuntimeSummaryResponse.GovernanceQuotaSummary> quotaSummaries = quotaSnapshotRepository.findAll().stream()
                .filter(snapshot -> normalizedOrganizationKey.equals(snapshot.getOrganizationKey()))
                .filter(snapshot -> "ACTIVE".equalsIgnoreCase(snapshot.getSnapshotStatus()))
                .map(this::toQuotaSummary)
                .toList();
        List<GovernanceRuntimeSummaryResponse.GovernanceConnectorSummary> connectorSummaries = connectorRuntimeLeaseRepository.findAll().stream()
                .filter(lease -> normalizedOrganizationKey.equals(lease.getOrganizationKey()))
                .filter(lease -> "HELD".equalsIgnoreCase(lease.getLeaseStatus()))
                .map(this::toConnectorSummary)
                .toList();

        long waitingQuotaCount = quotaSummaries.stream()
                .filter(summary -> "WAITING_RELEASE".equals(summary.getStatus()))
                .count();
        long busyConnectorCount = connectorSummaries.stream()
                .filter(summary -> "BUSY".equals(summary.getStatus()))
                .count();

        return GovernanceRuntimeSummaryResponse.builder()
                .organizationKey(normalizedOrganizationKey)
                .summary("当前有 " + waitingQuotaCount + " 个配额项需等待释放，" + busyConnectorCount + " 个连接器正在运行。")
                .quotaSummaries(quotaSummaries)
                .connectorSummaries(connectorSummaries)
                .build();
    }

    /**
     * 把配额快照转换成用户能理解的配额状态。
     * 复杂点在于 reservedValue 代表正在运行但尚未释放的占位，因此剩余额度为 0 时需要明确提示等待释放。
     */
    private GovernanceRuntimeSummaryResponse.GovernanceQuotaSummary toQuotaSummary(OrganizationQuotaSnapshot snapshot) {
        int limitValue = safeInt(snapshot.getLimitValue());
        int usedValue = safeInt(snapshot.getUsedValue());
        int reservedValue = safeInt(snapshot.getReservedValue());
        int availableValue = Math.max(0, limitValue - usedValue - reservedValue);
        boolean waitingRelease = availableValue <= 0 && (usedValue > 0 || reservedValue > 0);
        String displayName = quotaDisplayName(snapshot.getQuotaKey());

        return GovernanceRuntimeSummaryResponse.GovernanceQuotaSummary.builder()
                .displayName(displayName)
                .status(waitingRelease ? "WAITING_RELEASE" : "AVAILABLE")
                .summary(displayName + "可用 " + availableValue + " / 上限 " + limitValue + "，当前已用 " + usedValue + "，预留 " + reservedValue + "。")
                .retryAdvice(waitingRelease
                        ? "等待正在运行的任务完成后再重试，或暂停低优先级任务释放并发槽位。"
                        : "当前还有可用额度，可以继续发起操作。")
                .limitValue(limitValue)
                .usedValue(usedValue)
                .reservedValue(reservedValue)
                .availableValue(availableValue)
                .sourceUrls(normalizeSourceUrls(snapshot.getSourceUrls()))
                .build();
    }

    /**
     * 把连接器租约转换成运行状态摘要。
     * 只保留租约所有者和预计释放时间，不下发 leaseToken，避免用户看到不可操作的内部占位标识。
     */
    private GovernanceRuntimeSummaryResponse.GovernanceConnectorSummary toConnectorSummary(ConnectorRuntimeLease lease) {
        long remainingMinutes = Math.max(0, Duration.between(LocalDateTime.now(), lease.getExpiresAt()).toMinutes());
        String displayName = connectorDisplayName(lease.getConnectorKey());
        return GovernanceRuntimeSummaryResponse.GovernanceConnectorSummary.builder()
                .displayName(displayName)
                .status("BUSY")
                .summary(displayName + "正在同步，预计 " + remainingMinutes + " 分钟内释放。")
                .retryAdvice("等待当前同步完成后再重试，或选择其他资料来源继续处理。")
                .leaseOwner(lease.getLeaseOwner())
                .expiresAt(lease.getExpiresAt() == null ? null : lease.getExpiresAt().toString())
                .sourceUrls(normalizeSourceUrls(lease.getSourceUrls()))
                .build();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String quotaDisplayName(String quotaKey) {
        if ("TASK_CONCURRENCY".equalsIgnoreCase(quotaKey)) {
            return "任务并发";
        }
        if ("MODEL_BUDGET".equalsIgnoreCase(quotaKey)) {
            return "模型预算";
        }
        if ("EXPORT_CONCURRENCY".equalsIgnoreCase(quotaKey)) {
            return "报告导出并发";
        }
        return "组织配额";
    }

    private String connectorDisplayName(String connectorKey) {
        if ("notion-pages".equalsIgnoreCase(connectorKey)) {
            return "Notion 页面";
        }
        if ("feishu-docs".equalsIgnoreCase(connectorKey)) {
            return "飞书文档";
        }
        if ("confluence-space".equalsIgnoreCase(connectorKey)) {
            return "Confluence 空间";
        }
        return "资料连接器";
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        return sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
    }
}
