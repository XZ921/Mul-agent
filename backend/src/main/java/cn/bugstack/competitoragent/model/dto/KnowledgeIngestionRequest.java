package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.security.HttpUrlOnly;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 组织级资料接入请求。
 * <p>
 * Task 5.2.a 先把“资料准备进入哪个知识域、属于哪类来源、携带哪些可追溯链接”
 * 这组稳定契约沉淀下来，后续统一接入服务和最小前端入口都直接复用这份请求模型。
 */
@Data
@Schema(description = "组织级资料接入请求")
public class KnowledgeIngestionRequest {

    @Schema(description = "若资料来源于某次任务，可透传任务 ID；组织级资料默认可为空", example = "101")
    private Long taskId;

    @Size(max = 100, message = "竞品名称长度不能超过 100")
    @Schema(description = "若资料直接服务于某个竞品，可补充竞品名称", example = "Feishu")
    private String competitorName;

    @NotBlank(message = "知识域标识不能为空")
    @Size(max = 120, message = "知识域标识长度不能超过 120")
    @Schema(description = "目标知识域标识", example = "org-product-docs", requiredMode = Schema.RequiredMode.REQUIRED)
    private String domainKey;

    @NotBlank(message = "资料来源分类不能为空")
    @Size(max = 50, message = "资料来源分类长度不能超过 50")
    @Schema(description = "资料来源分类", example = "UPLOADED_DOCUMENTS", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceCategory;

    @Size(max = 50, message = "资料来源类型长度不能超过 50")
    @Schema(description = "资料来源类型", example = "DOCS")
    private String sourceType;

    @Size(max = 200, message = "接入标题长度不能超过 200")
    @Schema(description = "接入标题或资料批次名称", example = "产品资料接入")
    private String title;

    @Size(max = 120, message = "连接器标识长度不能超过 120")
    @Schema(description = "若本次资料来自受控连接器，则填写连接器标识", example = "feishu-drive")
    private String connectorKey;

    @HttpUrlOnly(message = "资料主链接仅支持 http/https 协议")
    @Schema(description = "资料主链接，若为空则回退到 sourceUrls 的第一条", example = "https://docs.example.com/launch-guide.pdf")
    private String url;

    @Size(max = 50, message = "发现方式长度不能超过 50")
    @Schema(description = "资料进入系统的发现方式", example = "UPLOAD")
    private String discoveryMethod;

    @Size(max = 255, message = "来源域名长度不能超过 255")
    @Schema(description = "来源域名", example = "docs.example.com")
    private String sourceDomain;

    @Size(max = 40, message = "期望生命周期长度不能超过 40")
    @Schema(description = "期望生命周期", example = "ACTIVE")
    private String requestedLifecycle;

    @Size(max = 40, message = "期望可信度长度不能超过 40")
    @Schema(description = "期望可信度", example = "CURATED")
    private String requestedTrustLevel;

    @Schema(description = "需要回指的原始来源链接列表")
    private List<@HttpUrlOnly(message = "资料来源链接仅支持 http/https 协议") String> sourceUrls = new ArrayList<>();

    @Schema(description = "接入时可直接提供的摘要片段")
    private String contentSnippet;

    @Schema(description = "接入时可直接提供的原始正文或解析后的正文")
    private String contentText;

    @Schema(description = "接入摘要，供后续服务直接生成用户可读的入库说明")
    private String summary;
}
