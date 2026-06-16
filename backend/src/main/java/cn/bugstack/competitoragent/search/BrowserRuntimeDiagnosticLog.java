package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 浏览器运行时诊断日志。
 * 这里把浏览器搜索与页面采集阶段的关键诊断字段统一收口，
 * 便于日志平台聚合 failureKind、restartScope、fallbackAction 和反爬命中信号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrowserRuntimeDiagnosticLog {

    private Long taskId;
    private String nodeName;
    private String competitorName;
    private String sourceType;
    private String query;
    private String targetUrl;
    private String engineKey;
    private String failureKind;
    private String restartScope;
    private String fallbackAction;
    private String blockedReasonCode;
    private List<String> matchedSignals;
}
