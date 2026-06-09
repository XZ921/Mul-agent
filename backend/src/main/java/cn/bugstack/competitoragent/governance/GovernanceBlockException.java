package cn.bugstack.competitoragent.governance;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import lombok.Getter;

/**
 * 组织级治理阻断异常。
 * <p>
 * 当任务、模型、资料接入、导出或连接器运行被统一治理规则拦截时，
 * 统一抛出这类异常，避免上层把治理阻断误判成普通业务失败。
 */
@Getter
public class GovernanceBlockException extends BusinessException {

    private final QuotaDecision decision;

    public GovernanceBlockException(QuotaDecision decision) {
        super(ResultCode.GOVERNANCE_BLOCKED,
                decision == null || decision.getSummary() == null ? "治理策略阻断了当前操作" : decision.getSummary());
        this.decision = decision;
    }
}
