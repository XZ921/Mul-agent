package cn.bugstack.competitoragent.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>
 * 统一拦截所有异常，返回 {@link ApiResponse} 格式的错误信息。
 * 按照异常类型分级处理：
 * <ul>
 *   <li>BusinessException — 业务异常，使用其内置的 ResultCode</li>
 *   <li>参数校验异常 — 返回 400 + 具体的校验错误详情</li>
 *   <li>其他未知异常 — 返回 500 + 脱敏后的错误信息</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    /**
     * 业务异常 — 业务层主动抛出的异常，错误码和消息已在 ResultCode 中定义
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> handleBusinessException(BusinessException e) {
        log.warn("[业务异常] code={}, message={}, traceId={}", e.getCode(), e.getMessage(), TraceIdHolder.get());

        Map<String, Object> errorDetail = new HashMap<>();
        errorDetail.put("errorCode", e.getCode());
        errorDetail.put("errorType", e.getResultCode().name());
        errorDetail.put("detail", e.getMessage());

        return ApiResponse.error(e.getResultCode(), errorDetail);
    }

    // ==================== 参数校验异常 ====================

    /**
     * 请求体参数校验失败 (@Valid)
     * <p>
     * 返回每个字段的具体校验错误
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "校验失败",
                        (a, b) -> a + "; " + b
                ));

        log.warn("[参数校验失败] fields={}, traceId={}", fieldErrors.keySet(), TraceIdHolder.get());

        Map<String, Object> errorDetail = new HashMap<>();
        errorDetail.put("errorCode", ResultCode.PARAM_INVALID.getCode());
        errorDetail.put("errorType", "VALIDATION_FAILED");
        errorDetail.put("fieldErrors", fieldErrors);

        return ApiResponse.error(ResultCode.PARAM_INVALID, errorDetail);
    }

    /**
     * URL 参数校验失败 (@RequestParam 缺失)
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> handleMissingParamException(MissingServletRequestParameterException e) {
        log.warn("[缺少必填参数] param={}, traceId={}", e.getParameterName(), TraceIdHolder.get());

        Map<String, Object> errorDetail = new HashMap<>();
        errorDetail.put("errorCode", ResultCode.PARAM_MISSING.getCode());
        errorDetail.put("missingParam", e.getParameterName());

        return ApiResponse.error(ResultCode.PARAM_MISSING, errorDetail);
    }

    /**
     * 参数类型转换失败 (如需要 Long 传了 String)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("[参数类型错误] param={}, requiredType={}, traceId={}",
                e.getName(), e.getRequiredType(), TraceIdHolder.get());

        Map<String, Object> errorDetail = new HashMap<>();
        errorDetail.put("errorCode", ResultCode.PARAM_VALUE_INVALID.getCode());
        errorDetail.put("param", e.getName());
        errorDetail.put("expectedType", e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown");

        return ApiResponse.error(ResultCode.PARAM_VALUE_INVALID,
                "参数 " + e.getName() + " 类型不正确，期望 " + (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown"));
    }

    /**
     * 请求体 JSON 解析失败
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("[请求体解析失败] message={}, traceId={}", e.getMessage(), TraceIdHolder.get());
        return ApiResponse.error(ResultCode.BAD_REQUEST, "请求体格式错误，请检查 JSON 格式是否正确");
    }

    /**
     * JSR-380 校验异常 (方法级别 @Validated)
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> handleConstraintViolationException(ConstraintViolationException e) {
        String violations = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));

        log.warn("[约束校验失败] violations={}, traceId={}", violations, TraceIdHolder.get());

        Map<String, Object> errorDetail = new HashMap<>();
        errorDetail.put("errorCode", ResultCode.PARAM_INVALID.getCode());
        errorDetail.put("violations", violations);

        return ApiResponse.error(ResultCode.PARAM_INVALID, errorDetail);
    }

    // ==================== 兜底异常 ====================

    /**
     * 静态资源 404（favicon.ico 等）— 静默处理，不记录 ERROR 日志
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNoResourceFound(NoResourceFoundException e) {
        // 浏览器自动请求 favicon.ico 等，属于正常行为，不打印日志
    }

    /**
     * 所有未显式捕获的异常 — 兜底处理
     * <p>
     * 返回 500 错误，不暴露详细的堆栈信息给前端，只在日志中记录完整堆栈
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> handleUnknownException(Exception e) {
        log.error("[未知异常] type={}, message={}, traceId={}",
                e.getClass().getName(), e.getMessage(), TraceIdHolder.get(), e);

        Map<String, Object> errorDetail = new HashMap<>();
        errorDetail.put("errorCode", ResultCode.INTERNAL_ERROR.getCode());
        errorDetail.put("errorType", e.getClass().getSimpleName());
        // 仅开发环境返回详细信息
        errorDetail.put("detail", e.getMessage() != null ? e.getMessage() : "未知错误");

        return ApiResponse.error(ResultCode.INTERNAL_ERROR, errorDetail);
    }
}
