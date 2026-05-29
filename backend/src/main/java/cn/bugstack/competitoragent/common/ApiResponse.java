package cn.bugstack.competitoragent.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 统一 API 响应封装
 * <p>
 * 所有接口返回格式统一为：
 * <pre>
 * {
 *   "code": 200,
 *   "message": "操作成功",
 *   "data": { ... },
 *   "timestamp": "2026-05-26 10:30:00",
 *   "traceId": "uuid"
 * }
 * </pre>
 *
 * @param <T> 响应数据类型
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "统一 API 响应")
public class ApiResponse<T> {

    @Schema(description = "业务状态码", example = "200")
    private final int code;

    @Schema(description = "提示信息", example = "操作成功")
    private final String message;

    @Schema(description = "响应数据")
    private final T data;

    @Schema(description = "响应时间戳", example = "2026-05-26 10:30:00")
    private final String timestamp;

    @Schema(description = "请求追踪 ID，用于排查问题", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private final String traceId;

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now().toString();
        this.traceId = traceId;
    }

    // ==================== 成功响应 ====================

    /**
     * 操作成功，无返回数据
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null, TraceIdHolder.get());
    }

    /**
     * 操作成功，返回数据
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data, TraceIdHolder.get());
    }

    /**
     * 操作成功，自定义提示信息
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), message, data, TraceIdHolder.get());
    }

    // ==================== 失败响应 ====================

    /**
     * 使用预定义错误码
     */
    public static <T> ApiResponse<T> error(ResultCode resultCode) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMessage(), null, TraceIdHolder.get());
    }

    /**
     * 使用预定义错误码 + 自定义错误信息
     */
    public static <T> ApiResponse<T> error(ResultCode resultCode, String customMessage) {
        return new ApiResponse<>(resultCode.getCode(), customMessage, null, TraceIdHolder.get());
    }

    /**
     * 自定义错误码 + 错误信息
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, TraceIdHolder.get());
    }

    /**
     * 操作失败，附带错误详情数据
     */
    public static <T> ApiResponse<T> error(ResultCode resultCode, T errorData) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMessage(), errorData, TraceIdHolder.get());
    }
}
