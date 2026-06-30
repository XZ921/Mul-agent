package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 字段查询的 includeDomains 决策器。
 * 官方来源只把竞品域名作为优先命中锚点；第三方来源返回空列表，表示允许全网补充证据进入。
 */
@Component
public class IncludeDomainPlanner {

    private static final Set<String> THIRD_PARTY_SOURCE_TYPES = Set.of("REVIEW", "NEWS", "OPEN_WEB", "THIRD_PARTY_REVIEW");

    /**
     * 按来源类型和候选官方域名生成 includeDomains。
     * 注意：这里输出的是搜索侧的优先域名，不代表下游可以把非官方候选直接过滤掉。
     */
    public List<String> planIncludeDomains(String competitorName,
                                           List<String> preferredDomains,
                                           List<String> sourceTypes,
                                           String evidencePathKey) {
        if (isThirdPartySource(sourceTypes, evidencePathKey)) {
            return List.of();
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        if (preferredDomains != null) {
            for (String domain : preferredDomains) {
                if (StringUtils.hasText(domain)) {
                    domains.add(normalizeDomain(domain));
                }
            }
        }
        return new ArrayList<>(domains);
    }

    private boolean isThirdPartySource(List<String> sourceTypes, String evidencePathKey) {
        if (containsThirdPartyToken(evidencePathKey)) {
            return true;
        }
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return false;
        }
        for (String sourceType : sourceTypes) {
            if (containsThirdPartyToken(sourceType)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsThirdPartyToken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return THIRD_PARTY_SOURCE_TYPES.stream().anyMatch(normalized::contains);
    }

    private String normalizeDomain(String domain) {
        String normalized = domain.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("/.*$", "");
        return normalized.toLowerCase(Locale.ROOT);
    }
}
