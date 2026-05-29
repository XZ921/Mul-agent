package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建/更新分析 Schema 请求
 */
@Data
@Schema(description = "分析模板请求")
public class SchemaRequest {

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称最多 100 字")
    @Schema(description = "模板名称", example = "功能对比分析", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 500, message = "描述最多 500 字")
    @Schema(description = "模板描述", example = "对竞品进行全维度功能对比分析，适合产品经理选型参考")
    private String description;

    @NotBlank(message = "分析维度不能为空")
    @Schema(description = "分析维度定义 (JSON)", example = "[{\"name\":\"产品功能\",\"description\":\"核心功能对比\",\"weight\":0.3}]", requiredMode = Schema.RequiredMode.REQUIRED)
    private String dimensions;
}
