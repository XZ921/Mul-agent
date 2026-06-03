package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 统一证据片段契约。
 * 用一个稳定的小对象承接“证据编号、来源链接、片段摘要、问题标记”，
 * 避免采集/抽取/分析/写作各阶段各自定义字段，导致 sourceUrls 漂移或缺口信息丢失。
 */
@Data
@Builder(toBuilder = true)
public class EvidenceFragment {

    private String stage;

    private String competitorName;

    private String fieldName;

    private String evidenceId;

    private String sourceUrl;

    private String title;

    private String snippet;

    @Builder.Default
    private List<String> issueFlags = List.of();

    /**
     * 每次片段进入下一阶段前都先做一次标准化：
     * 1. 去掉空白和重复问题标记；
     * 2. 如果来源链接缺失，自动补上 MISSING_SOURCE_URL，防止问题在链路中被静默吞掉。
     */
    public EvidenceFragment normalized() {
        LinkedHashSet<String> normalizedFlags = new LinkedHashSet<>();
        if (issueFlags != null) {
            for (String issueFlag : issueFlags) {
                if (issueFlag != null && !issueFlag.isBlank()) {
                    normalizedFlags.add(issueFlag.trim());
                }
            }
        }
        if (sourceUrl == null || sourceUrl.isBlank()) {
            normalizedFlags.add("MISSING_SOURCE_URL");
        }
        return this.toBuilder()
                .sourceUrl(sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl.trim())
                .issueFlags(new ArrayList<>(normalizedFlags))
                .build();
    }

    /**
     * 从一组证据片段里统一抽取可回溯的 sourceUrls。
     * 这样即使上游只传了 evidenceFragments，下游也能稳定拿到去重后的来源列表。
     */
    public static List<String> collectSourceUrls(List<EvidenceFragment> fragments) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (fragments != null) {
            for (EvidenceFragment fragment : fragments) {
                EvidenceFragment normalized = fragment == null ? null : fragment.normalized();
                if (normalized != null
                        && normalized.getSourceUrl() != null
                        && !normalized.getSourceUrl().isBlank()) {
                    urls.add(normalized.getSourceUrl());
                }
            }
        }
        return new ArrayList<>(urls);
    }
}
