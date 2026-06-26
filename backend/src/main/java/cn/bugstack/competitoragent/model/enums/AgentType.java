package cn.bugstack.competitoragent.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * Agent 类型枚举
 */
@Getter
@Schema(description = "Agent 类型")
public enum AgentType {

    @Schema(description = "采集 Agent — 抓取竞品公开信息")
    COLLECTOR("采集 Agent"),

    @Schema(description = "抽取 Agent — 将非结构化内容转为竞品知识 Schema")
    EXTRACTOR("抽取 Agent"),

    @Schema(description = "分析 Agent — 多竞品横向对比分析")
    ANALYZER("分析 Agent"),

    @Schema(description = "撰写 Agent — 生成结构化竞品报告")
    WRITER("撰写 Agent"),

    @Schema(description = "质检 Agent — 检查报告完整性与证据充分性")
    REVIEWER("质检 Agent"),

    @Schema(description = "引用核查 Agent — 检查报告引用覆盖和来源可信度")
    CITATION("引用核查 Agent");

    private final String description;

    AgentType(String description) {
        this.description = description;
    }
}
