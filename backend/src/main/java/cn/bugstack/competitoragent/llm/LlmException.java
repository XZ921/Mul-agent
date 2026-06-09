package cn.bugstack.competitoragent.llm;

/**
 * LLM 调用异常
 */
public class LlmException extends RuntimeException {

    private final String providerErrorCode;

    public LlmException(String message) {
        super(message);
        this.providerErrorCode = null;
    }

    public LlmException(String message, String providerErrorCode) {
        super(message);
        this.providerErrorCode = providerErrorCode;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.providerErrorCode = null;
    }

    public LlmException(String message, String providerErrorCode, Throwable cause) {
        super(message, cause);
        this.providerErrorCode = providerErrorCode;
    }

    public String getProviderErrorCode() {
        return providerErrorCode;
    }
}
