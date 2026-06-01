package cn.bugstack.competitoragent.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 节点控制态枚举。
 * 仅表达人工控制请求，不替代主执行状态。
 */
@Getter
@Schema(description = "节点控制状态")
public enum TaskNodeControlState {

    @Schema(description = "无额外控制请求")
    NONE("无"),

    @Schema(description = "已请求在当前轮执行结束后终止")
    TERMINATE_REQUESTED("已请求终止");

    private final String description;

    TaskNodeControlState(String description) {
        this.description = description;
    }
}
