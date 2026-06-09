package cn.bugstack.competitoragent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 运维侧组织级治理运行摘要。
 * <p>
 * 该响应只暴露用户和操作者能直接理解的状态、等待原因、重试建议与来源链接，
 * 避免把 quotaKey、Redis 键或租约 token 等底层实现细节泄露到页面。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceRuntimeSummaryResponse {

    private String organizationKey;

    private String summary;

    private List<GovernanceQuotaSummary> quotaSummaries;

    private List<GovernanceConnectorSummary> connectorSummaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceQuotaSummary {

        private String displayName;

        private String status;

        private String summary;

        private String retryAdvice;

        private Integer limitValue;

        private Integer usedValue;

        private Integer reservedValue;

        private Integer availableValue;

        private List<String> sourceUrls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceConnectorSummary {

        private String displayName;

        private String status;

        private String summary;

        private String retryAdvice;

        private String leaseOwner;

        private String expiresAt;

        private List<String> sourceUrls;
    }
}
