package cn.bugstack.competitoragent.orchestration;

/**
 * LLM Brain 的类型化失败出口。
 * message 只包含稳定分类，调用方必须读取 failure，而不是解析异常文本。
 */
public final class LlmOrchestratorDecisionException extends RuntimeException {

    private final LlmOrchestratorDecisionFailure failure;

    public LlmOrchestratorDecisionException(LlmOrchestratorDecisionFailure failure) {
        this(failure, null);
    }

    public LlmOrchestratorDecisionException(LlmOrchestratorDecisionFailure failure, Throwable cause) {
        super(buildMessage(failure), cause);
        this.failure = failure;
    }

    public LlmOrchestratorDecisionFailure failure() {
        return failure;
    }

    private static String buildMessage(LlmOrchestratorDecisionFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure 不能为空");
        }
        return "LLM Orchestrator 决策失败: " + failure.type().name();
    }
}
