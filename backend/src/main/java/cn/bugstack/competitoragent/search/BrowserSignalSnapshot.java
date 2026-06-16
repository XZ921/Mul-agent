package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 浏览器页面反爬信号快照。
 * 这里把运行时已经采集到的 HTTP、URL、标题、正文和结构信号收口成单一对象，
 * 让检测器只负责“判定”，不再在检测器内部直接操作 Playwright 页面对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrowserSignalSnapshot {

    private Integer httpStatus;
    private String finalUrl;
    private String pageTitle;
    private String bodyText;
    private boolean missingPrimaryResults;
    private boolean bodyTooShort;
    private int primaryResultCount;
    private int bodyLength;
}
