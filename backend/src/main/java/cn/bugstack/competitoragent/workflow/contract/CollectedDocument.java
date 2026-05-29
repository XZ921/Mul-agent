package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单篇采集文档详情。
 */
@Data
@Builder
public class CollectedDocument {

    private String competitor;

    private String url;

    private String evidenceId;

    private boolean success;

    /** 清洗后的页面标题 */
    private String title;

    /** 清洗后的正文文本，不是 HTML */
    private String cleanedText;

    /** 前 500 字符摘要，用于检索和快速预览 */
    private String snippet;

    /** 内容长度 */
    private int contentLength;

    /** 失败原因，仅 success=false 时有值 */
    private String errorMessage;

    /** 采集时间 */
    private LocalDateTime collectedAt;
}
