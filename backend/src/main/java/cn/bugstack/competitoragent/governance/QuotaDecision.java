package cn.bugstack.competitoragent.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一治理判定结果。
 * <p>
 * Task 5.8.b 先把任务、模型、连接器三类入口都能复用的结果对象抽出来，
 * 避免后续每条业务链路各自拼装“是否允许、为什么、占位结果是什么”的零散结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaDecision {

    private boolean allowed;

    private String decisionCode;

    private String summary;

    private String recommendedAction;

    private String organizationKey;

    private String quotaScope;

    private String quotaKey;

    private Integer requestedUnits;

    private Integer availableUnits;

    private String leaseToken;

    private String blockingOwner;

    private List<String> sourceUrls;

    public static QuotaDecision allow(String decisionCode,
                                      String summary,
                                      String organizationKey,
                                      String quotaScope,
                                      String quotaKey,
                                      Integer requestedUnits,
                                      Integer availableUnits,
                                      String leaseToken,
                                      List<String> sourceUrls) {
        return QuotaDecision.builder()
                .allowed(true)
                .decisionCode(decisionCode)
                .summary(summary)
                .organizationKey(organizationKey)
                .quotaScope(quotaScope)
                .quotaKey(quotaKey)
                .requestedUnits(requestedUnits)
                .availableUnits(availableUnits)
                .leaseToken(leaseToken)
                .sourceUrls(sourceUrls)
                .build();
    }

    public static QuotaDecision deny(String decisionCode,
                                     String summary,
                                     String organizationKey,
                                     String quotaScope,
                                     String quotaKey,
                                     Integer requestedUnits,
                                     Integer availableUnits,
                                     String blockingOwner,
                                     List<String> sourceUrls) {
        return QuotaDecision.builder()
                .allowed(false)
                .decisionCode(decisionCode)
                .summary(summary)
                .organizationKey(organizationKey)
                .quotaScope(quotaScope)
                .quotaKey(quotaKey)
                .requestedUnits(requestedUnits)
                .availableUnits(availableUnits)
                .blockingOwner(blockingOwner)
                .sourceUrls(sourceUrls)
                .build();
    }
}
