package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点配置修改请求。
 * 第四阶段允许用户在任务暂停/失败后微调节点配置，并从该节点继续执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Update node config request")
public class UpdateNodeConfigRequest {

    @NotBlank
    @Schema(description = "Updated node config JSON object")
    private String nodeConfig;
}
