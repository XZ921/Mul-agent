package cn.bugstack.competitoragent.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索补源上下文请求对象。
 * 这里统一承接运行期补源所需的竞品、scope、查询词、域名偏好与候选种子，
 * 让新 provider 可以消费完整上下文，同时保持旧 provider 仍可通过兼容入口继续工作。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SearchSourceRequest {

    private String competitorName;

    @Builder.Default
    private List<String> requestedScopes = new ArrayList<>();

    @Builder.Default
    private List<String> searchQueries = new ArrayList<>();

    @Builder.Default
    private List<String> preferredDomains = new ArrayList<>();

    @Builder.Default
    private List<String> includeDomains = new ArrayList<>();

    @Builder.Default
    private List<String> blockedDomains = new ArrayList<>();

    @Builder.Default
    private List<SourceCandidate> seedCandidates = new ArrayList<>();

    private String preferredProviderKey;
    private String preferredQueryMode;
    /**
     * requestPhase 用于让 provider 明确知道当前请求来自 Phase 1 bootstrap
     * 还是运行期 supplement，从而打出不同的发现语义和审计标签。
     */
    private SearchRequestPhase requestPhase;
}
