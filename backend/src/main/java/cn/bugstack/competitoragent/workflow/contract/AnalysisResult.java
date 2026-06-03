package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分析节点输出契约 — Analyst → Writer
 * <p>
 * 传递结构化的横向分析结果，不做自由文本传递。
 */
@Data
@Builder
public class AnalysisResult {

    private final String contractVersion = "1.0";

    /** 总体概述 (200 字以内) */
    private String overview;

    /** 功能对比分析 */
    private String featureComparison;

    /** 市场定位对比 */
    private String positioningComparison;

    /** 定价策略对比 */
    private String pricingComparison;

    /** 目标用户群体对比 */
    private String targetUserComparison;

    /** 优势汇总 */
    private String strengthsSummary;

    /** 劣势汇总 */
    private String weaknessesSummary;

    /** 市场机会点 */
    private List<String> opportunities;

    /** 竞争风险 */
    private List<String> risks;

    /** 产品策略建议 */
    private List<String> recommendations;

    /** 分析结果可回指的来源链接 */
    private List<String> sourceUrls;

    /** 分析阶段的问题标记，例如字段漂移已矫正、仍存在证据缺口 */
    private List<String> issueFlags;

    /** 提供给 Writer 的统一证据片段 */
    private List<EvidenceFragment> evidenceFragments;
}
