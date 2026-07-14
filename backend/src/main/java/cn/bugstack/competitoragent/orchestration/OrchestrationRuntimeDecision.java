package cn.bugstack.competitoragent.orchestration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一条决策在 Policy、运行时门和执行适配器处理后的不可变事实。
 */
public record OrchestrationRuntimeDecision(
        OrchestrationDecision decision,
        DecisionPolicyResult policyResult,
        DynamicPlanMutation mutation,
        boolean fallbackAttempt,
        String runtimeStatus,
        List<String> sourceUrls
) {

    public static final String READY = "READY";
    public static final String POLICY_REJECTED = "POLICY_REJECTED";
    public static final String CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED";
    public static final String DYNAMIC_BRANCH_LIMIT_REACHED = "DYNAMIC_BRANCH_LIMIT_REACHED";
    public static final String NO_MUTATION = "NO_MUTATION";

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            READY,
            POLICY_REJECTED,
            CONFIRMATION_REQUIRED,
            DYNAMIC_BRANCH_LIMIT_REACHED,
            NO_MUTATION);

    public OrchestrationRuntimeDecision {
        if (decision == null || policyResult == null || mutation == null) {
            throw new IllegalArgumentException("runtime decision 的 decision、policyResult、mutation 均不能为空");
        }
        decision = decision.normalized();
        policyResult = policyResult.normalized();
        mutation = mutation.normalized();
        if (!ALLOWED_STATUSES.contains(runtimeStatus)) {
            throw new IllegalArgumentException("不支持的 runtimeStatus: " + runtimeStatus);
        }

        // 被 Policy 拒绝的候选只能形成 NO_MUTATION；否则调用方可能误把 rejected attempt 当成可执行结果。
        if (POLICY_REJECTED.equals(runtimeStatus)
                && (policyResult.isAllowed() || !NO_MUTATION.equals(mutation.getMutationType()))) {
            throw new IllegalArgumentException("POLICY_REJECTED 必须对应 rejected policy 与 NO_MUTATION");
        }
        // 确认门是真实暂停边界，必须由 Adapter 翻译为 MARK_WAITING_INTERVENTION，禁止继续生成自动补图。
        if (CONFIRMATION_REQUIRED.equals(runtimeStatus)
                && (!policyResult.isRequiresConfirmation()
                || !"MARK_WAITING_INTERVENTION".equals(mutation.getMutationType()))) {
            throw new IllegalArgumentException(
                    "CONFIRMATION_REQUIRED 必须对应 requiresConfirmation 与 MARK_WAITING_INTERVENTION");
        }
        if (DYNAMIC_BRANCH_LIMIT_REACHED.equals(runtimeStatus)
                && !NO_MUTATION.equals(mutation.getMutationType())) {
            throw new IllegalArgumentException("DYNAMIC_BRANCH_LIMIT_REACHED 必须对应 NO_MUTATION");
        }
        sourceUrls = mergeSourceUrls(sourceUrls,
                decision.getSourceUrls(),
                policyResult.getSourceUrls(),
                mutation.getSourceUrls());
    }

    @SafeVarargs
    private static List<String> mergeSourceUrls(List<String>... groups) {
        Set<String> merged = new LinkedHashSet<>();
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String value : group) {
                if (value != null && !value.isBlank()) {
                    merged.add(value.trim());
                }
            }
        }
        return List.copyOf(merged);
    }
}
