package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将类型化 LLM 失败收敛为低基数 fallback reason，禁止拼接异常文本或外部响应内容。
 */
@Component
public class OrchestratorFallbackReasonMapper {

    public static final String POLICY_REJECTED = "POLICY_REJECTED";

    public String map(LlmOrchestratorDecisionFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure 不能为空");
        }
        if (failure.type() != LlmOrchestratorFailureType.PARSE_ERROR) {
            return failure.type().name();
        }
        String issueCode = finalIssueCode(failure.attempts());
        return issueCode == null ? LlmOrchestratorFailureType.PARSE_ERROR.name()
                : LlmOrchestratorFailureType.PARSE_ERROR.name() + ":" + issueCode;
    }

    public String policyRejected() {
        return POLICY_REJECTED;
    }

    private String finalIssueCode(List<LlmOrchestratorDecisionFailure.Attempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        LlmOrchestratorDecisionFailure.Attempt finalAttempt = attempts.get(attempts.size() - 1);
        if (finalAttempt == null || finalAttempt.issues().isEmpty() || finalAttempt.issues().get(0) == null) {
            return null;
        }
        String code = finalAttempt.issues().get(0).code();
        return code == null || code.isBlank() ? null : code.trim();
    }
}
