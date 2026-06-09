package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 恢复后的占位释放规则 DTO。
 * <p>
 * 该对象用于显式说明恢复动作会不会释放任务级 / 节点级占位，
 * 让调用方能区分“继续自动推进”和“转人工 / 终态收口”两类语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务恢复占位释放规则")
public class TaskRecoveryReleasePolicy {

    @Schema(description = "是否释放任务执行占位", example = "true")
    private boolean releaseTaskExecutionLock;

    @Schema(description = "是否释放节点执行占位", example = "true")
    private boolean releaseNodeExecutionLocks;

    @Schema(description = "释放规则说明")
    private String releaseReason;
}
