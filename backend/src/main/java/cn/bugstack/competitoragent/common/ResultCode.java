package cn.bugstack.competitoragent.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 统一业务状态码枚举
 * <p>
 * 编码分段规则：
 * <ul>
 *   <li>200：成功</li>
 *   <li>4xx：客户端错误（参数校验等）</li>
 *   <li>5xx：服务端错误</li>
 *   <li>1xxxx：任务相关业务错误</li>
 *   <li>2xxxx：Agent 执行相关错误</li>
 *   <li>3xxxx：数据采集相关错误</li>
 *   <li>4xxxx：LLM 调用相关错误</li>
 * </ul>
 */
@Getter
@Schema(description = "统一业务状态码")
public enum ResultCode {

    // ==================== 通用状态码 ====================
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误，请检查输入"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "不支持的请求方法"),
    INTERNAL_ERROR(500, "服务器内部错误，请联系管理员"),
    SERVICE_UNAVAILABLE(503, "服务暂时不可用，请稍后重试"),

    // ==================== 参数校验 (4xxx) ====================
    PARAM_MISSING(4001, "必填参数缺失"),
    PARAM_INVALID(4002, "参数格式不正确"),
    PARAM_VALUE_INVALID(4003, "参数取值不在允许范围内"),

    // ==================== 任务相关 (1xxxx) ====================
    TASK_NOT_FOUND(10001, "分析任务不存在，请检查任务 ID"),
    TASK_ALREADY_RUNNING(10002, "任务正在执行中，请勿重复启动"),
    TASK_CREATE_FAILED(10003, "任务创建失败，请稍后重试"),
    TASK_EXECUTION_FAILED(10004, "任务执行失败，请查看 Agent 日志定位原因"),
    TASK_DELETE_FAILED(10005, "任务删除失败，运行中的任务不可删除"),
    TASK_STATUS_INVALID(10006, "当前任务状态不允许此操作"),
    TASK_STOP_FAILED(10007, "任务停止失败，只有执行中的任务才能停止"),

    // ==================== Agent 执行 (2xxxx) ====================
    AGENT_EXECUTION_FAILED(20001, "Agent 执行失败"),
    AGENT_TIMEOUT(20002, "Agent 执行超时"),
    COLLECTOR_FAILED(20003, "信息采集 Agent 执行失败，请检查目标 URL 是否可访问"),
    EXTRACTOR_FAILED(20004, "Schema 抽取 Agent 执行失败，请检查 LLM 服务是否正常"),
    ANALYZER_FAILED(20005, "竞品分析 Agent 执行失败"),
    WRITER_FAILED(20006, "报告撰写 Agent 执行失败"),
    REVIEWER_FAILED(20007, "质检 Agent 执行失败"),

    // ==================== 数据采集 (3xxxx) ====================
    URL_FETCH_FAILED(30001, "网页抓取失败，目标 URL 不可达或已失效"),
    CONTENT_PARSE_FAILED(30002, "网页内容解析失败，无法提取正文"),
    PAGE_RENDER_FAILED(30003, "页面渲染失败（SPA 页面），请检查 Playwright 服务状态"),
    SOURCE_TOO_LARGE(30004, "采集内容超过大小限制（最大 5MB）"),

    // ==================== LLM 调用 (4xxxx) ====================
    LLM_CALL_FAILED(40001, "大模型调用失败，请检查 API Key 和网络连接"),
    LLM_TIMEOUT(40002, "大模型调用超时（超过 120 秒），请稍后重试"),
    LLM_RESPONSE_PARSE_FAILED(40003, "大模型返回内容解析失败，未能提取有效 JSON"),
    LLM_RATE_LIMITED(40004, "大模型调用频率超限，请稍后重试"),

    // ==================== 报告相关 (5xxxx) ====================
    REPORT_NOT_FOUND(50001, "报告不存在，请确认任务是否已完成"),
    REPORT_EXPORT_FAILED(50002, "报告导出失败"),
    REPORT_GENERATION_FAILED(50003, "报告生成失败"),

    // ==================== Schema 相关 (6xxxx) ====================
    SCHEMA_NOT_FOUND(60001, "分析模板不存在"),
    SCHEMA_NAME_DUPLICATE(60002, "分析模板名称重复"),
    SCHEMA_IN_USE(60003, "该模板正在被任务使用，暂不可删除");

    /** 业务状态码 */
    private final int code;

    /** 状态码描述（面向用户） */
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据状态码查找枚举，找不到返回 null
     */
    public static ResultCode fromCode(int code) {
        for (ResultCode rc : values()) {
            if (rc.code == code) {
                return rc;
            }
        }
        return null;
    }
}
