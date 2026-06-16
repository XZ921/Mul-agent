package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 反爬检测结果。
 * Task 1 先提供统一故障分类器需要消费的数据结构，
 * 后续 Task 2 会继续把多信号检测逻辑接到这个对象上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AntiBotDetectionResult {

    private boolean blocked;
    private boolean suspected;
    private String reasonCode;
    private Integer httpStatus;
    private String finalUrl;
    private String pageTitle;
    private List<String> matchedSignals;
    private List<String> matchedSelectors;
}
