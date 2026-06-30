package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchRuntimePolicy;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
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
        "sourceFamilyKey",
        "sourceFamilyRole",
        "primaryTools",
        "auxiliaryTools",
        "queryTemplates",
        "sourceScope",
        "schemaName",
        "coverageContractRef",
        "requiredCoverageFields",
        "blockingCoverageFields",
        "coverageQueryIntents",
        "dimensionEvidencePlan",
        "recoveryFieldName",
        "recoveryEvidencePathKey",
        "recoveryQueryIntents",
        "discoveryNotes",
        "sourceCandidates",
        "searchMode",
        "searchQueries",
        "searchFallbackOrder",
        "verifyCandidates",
        "verifyResultPage",
        "minVerifiedCandidates",
        "preferredSearchProvider",
        "tavilyQueryMode",
        "preferredDomains",
        "includeDomains",
        "blockedDomains",
        "browserSearchEnabled",
        "maxSearchResults",
        "searchTimeoutMillis",
        "searchRuntimePolicy",
        "searchExecutionPlan",
        "searchAuditCheckpoint",
        "collectionAuditCheckpoint"
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
     * 数据源家族 key，例如 official / news / github。
     * 预览和运行必须共享这组字段，避免两端对同一 sourceType 作不同解释。
     */
    private String sourceFamilyKey;

    /**
     * 数据源家族角色，例如 PRIMARY_VERTICAL / AUXILIARY_PUBLIC。
     */
    private String sourceFamilyRole;

    /**
     * 当前数据源家族的主力采集工具。
     */
    private List<String> primaryTools;

    /**
     * 当前数据源家族的辅助或兜底工具。
     */
    private List<String> auxiliaryTools;

    /**
     * 当前数据源家族绑定的 query template key。
     */
    private List<String> queryTemplates;

    /**
     * 采集的范围限定（例如：限制在特定的频道、模块或板块内）
     */
    private List<String> sourceScope;

    /**
     * 对应的结构化 Schema 架构名称，用于后续指引抽取数据对齐
     */
    private String schemaName;

    /**
     * 顶层 coverageContract 的版本引用。
     * Collector 节点只携带轻量引用，不重复内嵌完整契约，避免多个节点拷贝后出现漂移。
     */
    private String coverageContractRef;

    /**
     * 当前 Collector 节点需要支持的必填覆盖字段。
     * 这个裁剪视图用于告诉采集层哪些字段是本轮必须服务的，不要求它自己做最终字段结论。
     */
    private List<String> requiredCoverageFields;

    /**
     * 当前任务在 Reviewer 视角下的阻断字段列表。
     * 这里写入轻量视图，方便采集和审计链路知道哪些字段缺口会影响最终交付。
     */
    private List<String> blockingCoverageFields;

    /**
     * 从 coverageContract 裁剪出的查询意图列表。
     * 后续阶段 Collector 会进一步用它驱动 field-first 采集，而不是只按 sourceType 补源。
     */
    private List<String> coverageQueryIntents;

    /**
     * 当前补采链路要优先服务的字段名。
     * 公开证据补采只负责利用它规划同域公开入口，不在这里直接决定字段最终覆盖结论。
     */
    /**
     * 当前 Collector 节点携带的字段级证据计划。
     * 它把 CoverageContract 中 required 字段翻译成运行态预算，后续搜索与采集闭环直接消费这份快照。
     */
    private DimensionEvidencePlan dimensionEvidencePlan;

    private String recoveryFieldName;

    /**
     * 当前字段对应的证据路径 key。
     * recovery 会优先按这个路径选择 about/pricing/docs/download 等公开入口，避免退化成泛官网补源。
     */
    private String recoveryEvidencePathKey;

    /**
     * recovery 阶段可复用的字段级 query intents。
     * 它与 coverageQueryIntents 并存，前者用于本轮公开补采语境，后者仍保留节点级轻量 coverage 视图。
     */
    private List<String> recoveryQueryIntents;

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
     * 运行期搜索偏好的 provider key。
     * 这里只表达搜索路由提示，不改变现有 Agent prompt 输出结构。
     */
    private String preferredSearchProvider;

    /**
     * 运行期 Tavily 查询模式提示。
     * orchestration 在补证据场景下可以通过它约束搜索形态，例如 OFFICIAL_DOCS / EVIDENCE_REPAIR。
     */
    private String tavilyQueryMode;

    /**
     * 优先推荐或倾向采纳的网站域名后缀/白名单列表（例如：gov.cn、csdn.net）
     */
    private List<String> preferredDomains;

    /**
     * 运行期强制包含的域名范围。
     * 与 preferredDomains 不同，这里表达的是更强的 include_domains 提示。
     */
    private List<String> includeDomains;

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

    /**
     * 采集审计快照，记录 package 级执行结果、回放时间线与恢复锚点。
     * rerun / resume 会基于这个快照复用已经成功的采集包，避免重复抓取。
     */
    private CollectionAuditSnapshot collectionAuditCheckpoint;
}
