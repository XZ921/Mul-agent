package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.security.HttpUrlOnly;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建分析任务请求
 */
@Data
@Schema(description = "创建分析任务请求")
public class CreateTaskRequest {

    @NotBlank(message = "分析主题不能为空")
    @Size(max = 200, message = "分析主题最多 200 字")
    @Schema(description = "分析主题/任务名称", example = "AI 知识库产品竞品分析", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskName;

    @NotBlank(message = "本方产品描述不能为空")
    @Size(max = 200, message = "本方产品描述最多 200 字")
    @Schema(description = "本方产品或关注对象", example = "企业级 RAG 知识库平台", requiredMode = Schema.RequiredMode.REQUIRED)
    private String subjectProduct;

    @NotEmpty(message = "至少需要 1 个竞品")
    @Schema(description = "竞品名称列表", example = "[\"Notion AI\",\"Glean\",\"Dify\",\"FastGPT\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> competitorNames;

    @Schema(description = "竞品官网 URL 列表（可选，不填则自动搜索）", example = "[\"https://www.notion.so\",\"https://www.glean.com\"]")
    private List<@HttpUrlOnly(message = "竞品 URL 仅支持 http/https 协议") String> competitorUrls;

    @Schema(description = "分析维度", example = "[\"产品功能\",\"目标用户\",\"价格策略\",\"技术能力\",\"市场定位\"]")
    private List<String> analysisDimensions;

    @Schema(description = "信息源范围", example = "[\"官网\",\"产品文档\",\"定价页\",\"公开测评\"]")
    private List<String> sourceScope;

    @Schema(description = "报告语言", example = "中文")
    private String reportLanguage = "中文";

    @Schema(description = "报告模板类型", example = "标准版")
    private String reportTemplate = "标准版";

    @Schema(description = "分析模板 ID（不填则使用默认维度）", example = "1")
    private Long schemaId;
}
