package cn.bugstack.competitoragent.common;

import lombok.Getter;

/**
 * 业务异常 — 所有业务层异常统一使用此类
 * <p>
 * 使用方式：
 * <pre>
 * throw new BusinessException(ResultCode.TASK_NOT_FOUND);
 * throw new BusinessException(ResultCode.TASK_NOT_FOUND, "任务 ID: " + taskId);
 * </pre>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务状态码 */
    private final int code;

    /** 预定义错误码枚举 */
    private final ResultCode resultCode;

    /**
     * 使用预定义错误码
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    /**
     * 使用预定义错误码 + 自定义补充信息（会追加到默认消息后面）
     */
    public BusinessException(ResultCode resultCode, String detail) {
        super(resultCode.getMessage() + " — " + detail);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    /**
     * 使用预定义错误码 + 原始异常
     */
    public BusinessException(ResultCode resultCode, Throwable cause) {
        super(resultCode.getMessage(), cause);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    /**
     * 使用预定义错误码 + 补充信息 + 原始异常
     */
    public BusinessException(ResultCode resultCode, String detail, Throwable cause) {
        super(resultCode.getMessage() + " — " + detail, cause);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }
}
