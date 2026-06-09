package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务并发配额占位协同器。
 * <p>
 * 当前任务并发治理的关键不是“有没有 reserve 能力”，
 * 而是 reserve / release / 再次执行时重新 reserve 是否形成闭环。
 * 这里统一管理任务实体上的 `taskQuotaReserved` 标记，
 * 避免各条业务链路各自推断导致重复释放、漏释放或绕过重新占位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQuotaCoordinator {

    private final OrganizationQuotaPolicy organizationQuotaPolicy;
    private final ObjectMapper objectMapper;

    /**
     * 当任务准备重新进入执行链路时，若当前并未持有占位，则先补做一次 reserve。
     * 这样 FAILED / SUCCESS / STOPPED 任务在 retry / resume / execute 后
     * 不会绕过组织级并发治理。
     */
    public void ensureTaskQuotaReserved(AnalysisTask task) {
        if (task == null || task.isTaskQuotaReserved()) {
            return;
        }
        QuotaDecision decision = organizationQuotaPolicy.checkAndReserve(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.TASK_SCOPE,
                GovernanceDefaults.TASK_CONCURRENCY_KEY,
                1,
                resolveQuotaSourceUrls(task)
        );
        if (decision != null && !decision.isAllowed()) {
            throw new GovernanceBlockException(decision);
        }
        task.setTaskQuotaReserved(true);
    }

    /**
     * 创建任务时 reserve 已经发生在定义链路前半段，
     * 这里仅负责把“当前任务已持有占位”的事实显式写回实体，
     * 供后续终态释放与恢复链路复用。
     */
    public void markTaskQuotaReserved(AnalysisTask task) {
        if (task == null) {
            return;
        }
        task.setTaskQuotaReserved(true);
    }

    /**
     * 当任务进入终态或被删除时，若当前仍持有占位，则执行一次释放。
     * 释放动作是否真正命中组织快照由治理层决定；
     * 对任务侧来说，只要释放流程成功返回，就把持有标记清掉，避免重复释放。
     */
    public void releaseTaskQuotaIfHeld(AnalysisTask task) {
        if (task == null || !task.isTaskQuotaReserved()) {
            return;
        }
        organizationQuotaPolicy.releaseReservation(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.TASK_SCOPE,
                GovernanceDefaults.TASK_CONCURRENCY_KEY,
                1,
                resolveQuotaSourceUrls(task)
        );
        task.setTaskQuotaReserved(false);
    }

    /**
     * 任务并发配额的来源线索当前沿用任务录入阶段的 competitorUrls。
     * 若历史任务字段为空或解析失败，则降级为空列表，但不阻断释放闭环。
     */
    private List<String> resolveQuotaSourceUrls(AnalysisTask task) {
        if (task == null || task.getCompetitorUrls() == null || task.getCompetitorUrls().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(task.getCompetitorUrls(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("resolve task quota source urls failed, taskId={}", task.getId(), e);
            return List.of();
        }
    }
}
