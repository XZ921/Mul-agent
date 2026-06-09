package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchRuntimePolicy;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 采集节点配置。
 * 从 WorkflowFactory 规划阶段写入，并由 CollectorAgent 在运行时消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
        "competitorName",
        "competitorUrls",
        "sourceType",
        "sourceScope",
        "schemaName",
        "discoveryNotes",
        "sourceCandidates",
        "searchMode",
        "searchQueries",
        "searchFallbackOrder",
        "verifyCandidates",
        "verifyResultPage",
        "minVerifiedCandidates",
        "preferredDomains",
        "blockedDomains",
        "browserSearchEnabled",
        "maxSearchResults",
        "searchTimeoutMillis",
        "searchRuntimePolicy",
        "searchExecutionPlan",
        "searchAuditCheckpoint"
})
public class CollectorNodeConfig {

    /**
     * 目标竞品公司或产品的名称（例如："阿里云"、"PingCAP"）
     */
    private String competitorName;

    /**
     * 用户明确指定的竞品官网、文档入口或特定的目标页面 URL 列表
     */
    private List<String> competitorUrls;

    /**
     * 采集的来源分类（例如：官方渠道 "OFFICIAL"、第三方渠道 "THIRD_PARTY" 等）
     */
    private String sourceType;

    /**
     * 采集的范围限定（例如：限制在特定的频道、模块或板块内）
     */
    private List<String> sourceScope;

    /**
     * 对应的结构化 Schema 架构名称，用于后续指引抽取数据对齐
     */
    private String schemaName;

    /**
     * 探索发现备注，由人工或规划层写入的寻源线索或特殊指引说明
     */
    private String discoveryNotes;

    /**
     * 预选/已匹配出来的候选数据源列表（包含链接、评分、发布时间等元数据）
     */
    private List<SourceCandidate> sourceCandidates;

    // 下面这些字段为运行期搜索与验证流程预留，当前阶段先由规划层写入默认值。

    /**
     * 搜索引擎的运行模式（例如：利用浏览器渲染的 "BROWSER" 模式、或者纯 HTTP 请求模式）
     */
    private String searchMode;

    /**
     * 动态规划生成的搜索引擎查询关键词（Query）列表
     */
    private List<String> searchQueries;

    /**
     * 搜索失败时的回退兜底策略顺序（例如：优先百度，失败后回退至必应或 HTTP 补源）
     */
    private List<String> searchFallbackOrder;

    /**
     * 是否需要对筛选出的候选链接（Candidates）进行合规与可信度验证
     */
    private Boolean verifyCandidates;

    /**
     * 是否需要对最终抓取到的结果页面进行二次合规验证
     */
    private Boolean verifyResultPage;

    /**
     * 触发后续链路所需的最低已验证通过的候选源数量
     */
    private Integer minVerifiedCandidates;

    /**
     * 优先推荐或倾向采纳的网站域名后缀/白名单列表（例如：gov.cn、csdn.net）
     */
    private List<String> preferredDomains;

    /**
     * 明确排除、禁止访问的黑名单网站域名列表（常用于过滤广告或低质量噪声源）
     */
    private List<String> blockedDomains;

    /**
     * 是否启用真实浏览器动态渲染搜索（开启后可有效绕过常规反爬拦截）
     */
    private Boolean browserSearchEnabled;

    /**
     * 单次搜索允许返回的最大结果记录条数限制
     */
    private Integer maxSearchResults;

    /**
     * 网络搜索与页面抓取的全局超时时间（毫秒）
     */
    private Long searchTimeoutMillis;

    /**
     * 搜索运行期的核心运行时环境控制策略
     */
    private SearchRuntimePolicy searchRuntimePolicy;

    /**
     * 搜索引擎在当前节点所对应的具体执行计划
     */
    private SearchExecutionPlan searchExecutionPlan;

    /**
     * 搜索审计快照，记录合规性审计、反爬检测及当前节点的执行水位快照
     */
    private SearchAuditSnapshot searchAuditCheckpoint;
}
