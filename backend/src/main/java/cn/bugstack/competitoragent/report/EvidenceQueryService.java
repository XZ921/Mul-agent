package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceEntryPointInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceReference;
import cn.bugstack.competitoragent.model.dto.ReportResponse.FieldEvidenceDetail;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SectionEvidenceBundleInfo;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 证据来源查询服务。
 * V2 第二阶段把来源元数据结构化后，统一从这里承接报告页筛选与后续质量看板查询。
 */
@Service
@RequiredArgsConstructor
public class EvidenceQueryService {

    private final EvidenceSourceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public List<EvidenceInfo> listEvidences(Long taskId,
                                            String competitorName,
                                            String sourceType,
                                            String discoveryMethod) {
        Specification<EvidenceSource> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("taskId"), taskId));
            if (StringUtils.hasText(competitorName)) {
                predicates.add(cb.equal(root.get("competitorName"), competitorName));
            }
            if (StringUtils.hasText(sourceType)) {
                predicates.add(cb.equal(root.get("sourceType"), sourceType));
            }
            if (StringUtils.hasText(discoveryMethod)) {
                predicates.add(cb.equal(root.get("discoveryMethod"), discoveryMethod));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return evidenceRepository.findAll(specification, Sort.by(
                        Sort.Order.asc("competitorName"),
                        Sort.Order.desc("sourceScore").nullsLast(),
                        Sort.Order.asc("evidenceId")))
                .stream()
                .map(this::toEvidenceInfo)
                .toList();
    }

    public EvidenceInfo toEvidenceInfo(EvidenceSource evidence) {
        Map<String, Object> metadata = mergeStructuredMetadata(evidence);
        return new EvidenceInfo(
                evidence.getEvidenceId(),
                evidence.getTitle(),
                evidence.getUrl(),
                evidence.getContentSnippet(),
                evidence.getCompetitorName(),
                evidence.getCollectedAt(),
                evidence.getSourceType(),
                evidence.getDiscoveryMethod(),
                evidence.getSourceDomain(),
                evidence.getDiscoveryReason(),
                evidence.getPublishedAt(),
                evidence.getSourceScore(),
                readBoolean(metadata, "verified"),
                readString(metadata, "verificationReason"),
                readString(metadata, "searchQuery"),
                readString(metadata, "searchEngine"),
                readInteger(metadata, "resultRank"),
                readString(metadata, "browserTraceId"),
                readString(metadata, "selectionReason"),
                readString(metadata, "selectionStage"),
                readStringList(metadata, "matchedSignals"),
                metadata
        );
    }

    public EvidenceReference toEvidenceReference(EvidenceInfo evidence) {
        if (evidence == null) {
            return null;
        }
        return EvidenceReference.builder()
                .evidenceId(evidence.getEvidenceId())
                .title(evidence.getTitle())
                .url(evidence.getUrl())
                .competitorName(evidence.getCompetitorName())
                .sourceType(evidence.getSourceType())
                .contentSnippet(evidence.getContentSnippet())
                .build();
    }

    /**
     * 交付中心主路径并不需要一次性展开全部证据，
     * 更重要的是先告诉用户“应该优先点开哪条证据”。
     * 因此这里统一从 section bundle 与 evidence 列表中挑选一个最小证据入口摘要。
     */
    public EvidenceEntryPointInfo toEvidenceEntryPointInfo(List<EvidenceInfo> evidences,
                                                           List<SectionEvidenceBundleInfo> bundles) {
        SectionEvidenceBundleInfo primaryBundle = selectPrimaryBundle(bundles);
        EvidenceReference primaryReference = selectPrimaryReference(primaryBundle, evidences);
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();

        if (primaryBundle != null && primaryBundle.getSourceUrls() != null) {
            sourceUrls.addAll(primaryBundle.getSourceUrls());
        }
        if (primaryReference != null && StringUtils.hasText(primaryReference.getUrl())) {
            sourceUrls.add(primaryReference.getUrl().trim());
        }

        String summary = null;
        if (primaryBundle != null && StringUtils.hasText(primaryBundle.getSummary())) {
            summary = primaryBundle.getSummary().trim();
        } else if (primaryReference != null && StringUtils.hasText(primaryReference.getTitle())) {
            summary = "可优先核对证据：" + primaryReference.getTitle().trim();
        } else if (primaryBundle != null && StringUtils.hasText(primaryBundle.getGapSummary())) {
            summary = primaryBundle.getGapSummary().trim();
        } else if (primaryReference != null && StringUtils.hasText(primaryReference.getUrl())) {
            summary = "可优先核对来源链接：" + primaryReference.getUrl().trim();
        } else {
            summary = "当前暂无可直接展开的关键证据入口";
        }

        return EvidenceEntryPointInfo.builder()
                .summary(summary)
                .sectionKey(primaryBundle == null ? null : primaryBundle.getSectionKey())
                .sectionTitle(primaryBundle == null ? null : primaryBundle.getSectionTitle())
                .evidenceId(primaryReference == null ? null : primaryReference.getEvidenceId())
                .title(primaryReference == null ? null : primaryReference.getTitle())
                .url(primaryReference == null ? null : primaryReference.getUrl())
                .sourceType(primaryReference == null ? null : primaryReference.getSourceType())
                .sourceUrls(new ArrayList<>(sourceUrls))
                .build();
    }

    /**
     * 报告接口需要的 section bundle 视图在这里统一投影：
     * 1. 原始 bundle 保留字段级缺口语义；
     * 2. evidenceId/sourceUrl 会在这里解析成 EvidenceReference；
     * 3. 即使某个字段完全没有命中证据，也会保留 gapComment 与 issueFlags，不会静默丢失。
     */
    public SectionEvidenceBundleInfo toSectionEvidenceBundleInfo(List<EvidenceInfo> evidences,
                                                                 SectionEvidenceBundle bundle) {
        if (bundle == null) {
            return null;
        }
        SectionEvidenceBundle normalized = bundle.normalized();
        List<FieldEvidenceDetail> fields = new ArrayList<>();
        LinkedHashMap<String, EvidenceReference> references = new LinkedHashMap<>();

        for (EvidenceFragment fragment : normalized.getEvidenceFragments() == null
                ? List.<EvidenceFragment>of()
                : normalized.getEvidenceFragments()) {
            EvidenceFragment normalizedFragment = fragment.normalized();
            List<EvidenceReference> resolved = resolveEvidenceReferences(
                    evidences,
                    normalizedFragment.getEvidenceId() == null ? List.of() : List.of(normalizedFragment.getEvidenceId()),
                    normalizedFragment.getSourceUrl() == null ? List.of() : List.of(normalizedFragment.getSourceUrl())
            );
            EvidenceReference evidence = resolved.isEmpty() ? null : resolved.get(0);
            if (evidence != null) {
                String key = evidence.getEvidenceId() != null && !evidence.getEvidenceId().isBlank()
                        ? "ID:" + evidence.getEvidenceId()
                        : "URL:" + evidence.getUrl();
                references.putIfAbsent(key, evidence);
            }
            fields.add(FieldEvidenceDetail.builder()
                    .fieldName(normalizedFragment.getFieldName())
                    .fieldLabel(normalizedFragment.getFieldLabel())
                    .coverageStatus(normalizedFragment.getCoverageStatus())
                    .gapComment(normalizedFragment.getGapComment())
                    .evidenceId(normalizedFragment.getEvidenceId())
                    .sourceUrl(normalizedFragment.getSourceUrl())
                    .title(normalizedFragment.getTitle())
                    .snippet(normalizedFragment.getSnippet())
                    .issueFlags(normalizedFragment.getIssueFlags())
                    .evidence(evidence)
                    .build());
        }

        for (EvidenceReference evidenceReference : resolveEvidenceReferences(evidences, List.of(), normalized.getSourceUrls())) {
            String key = evidenceReference.getEvidenceId() != null && !evidenceReference.getEvidenceId().isBlank()
                    ? "ID:" + evidenceReference.getEvidenceId()
                    : "URL:" + evidenceReference.getUrl();
            references.putIfAbsent(key, evidenceReference);
        }

        return SectionEvidenceBundleInfo.builder()
                .stage(normalized.getStage())
                .sectionType(normalized.getSectionType())
                .sectionKey(normalized.getSectionKey())
                .sectionTitle(normalized.getSectionTitle())
                .summary(normalized.getSummary())
                .gapSummary(normalized.getGapSummary())
                .hasGap(!normalized.getMissingFields().isEmpty()
                        || normalized.getIssueFlags().contains("SECTION_EVIDENCE_GAP")
                        || normalized.getIssueFlags().contains("NO_USABLE_EVIDENCE"))
                .fieldNames(normalized.getFieldNames())
                .missingFields(normalized.getMissingFields())
                .sourceUrls(normalized.getSourceUrls())
                .issueFlags(normalized.getIssueFlags())
                .fields(fields)
                .evidenceReferences(new ArrayList<>(references.values()))
                .build();
    }

    /**
     * 诊断条目可能只带 evidenceIds，也可能只带 sourceUrls。
     * 这里统一解析成轻量 EvidenceReference，前端就不需要再自己做“编号匹配 + URL 回退”的二次组装。
     */
    public List<EvidenceReference> resolveEvidenceReferences(List<EvidenceInfo> evidences,
                                                             List<String> evidenceIds,
                                                             List<String> sourceUrls) {
        LinkedHashMap<String, EvidenceInfo> byEvidenceId = new LinkedHashMap<>();
        LinkedHashMap<String, EvidenceInfo> byUrl = new LinkedHashMap<>();
        for (EvidenceInfo evidence : evidences == null ? List.<EvidenceInfo>of() : evidences) {
            if (evidence.getEvidenceId() != null && !evidence.getEvidenceId().isBlank()) {
                byEvidenceId.putIfAbsent(evidence.getEvidenceId().trim(), evidence);
            }
            if (evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
                byUrl.putIfAbsent(evidence.getUrl().trim(), evidence);
            }
        }

        List<EvidenceReference> resolved = new ArrayList<>();
        LinkedHashSet<String> addedKeys = new LinkedHashSet<>();
        for (String evidenceId : evidenceIds == null ? List.<String>of() : evidenceIds) {
            if (evidenceId == null || evidenceId.isBlank()) {
                continue;
            }
            EvidenceInfo matched = byEvidenceId.get(evidenceId.trim());
            if (matched == null) {
                continue;
            }
            String key = "ID:" + matched.getEvidenceId();
            if (addedKeys.add(key)) {
                resolved.add(toEvidenceReference(matched));
            }
        }

        for (String sourceUrl : sourceUrls == null ? List.<String>of() : sourceUrls) {
            if (sourceUrl == null || sourceUrl.isBlank()) {
                continue;
            }
            String normalizedUrl = sourceUrl.trim();
            EvidenceInfo matched = byUrl.get(normalizedUrl);
            String key = matched != null && matched.getEvidenceId() != null && !matched.getEvidenceId().isBlank()
                    ? "ID:" + matched.getEvidenceId().trim()
                    : "URL:" + normalizedUrl;
            if (!addedKeys.add(key)) {
                continue;
            }
            resolved.add(matched != null
                    ? toEvidenceReference(matched)
                    : EvidenceReference.builder().url(normalizedUrl).build());
        }
        return resolved;
    }

    /**
     * 优先选择已经被 writer / diagnosis 收口过的 section bundle，
     * 这样主路径证据入口就会尽量贴近“当前结论最依赖哪一段证据”。
     */
    private SectionEvidenceBundleInfo selectPrimaryBundle(List<SectionEvidenceBundleInfo> bundles) {
        if (bundles == null || bundles.isEmpty()) {
            return null;
        }
        for (SectionEvidenceBundleInfo bundle : bundles) {
            if (bundle == null) {
                continue;
            }
            if (bundle.getEvidenceReferences() != null && !bundle.getEvidenceReferences().isEmpty()) {
                return bundle;
            }
            if (bundle.getFields() != null && !bundle.getFields().isEmpty()) {
                return bundle;
            }
            if (bundle.getSourceUrls() != null && !bundle.getSourceUrls().isEmpty()) {
                return bundle;
            }
        }
        return bundles.get(0);
    }

    /**
     * 证据入口优先取 bundle 里已经解析好的 EvidenceReference，
     * 如未命中再回退到报告 evidence 列表的第一条，保证主路径始终能落到一个稳定入口。
     */
    private EvidenceReference selectPrimaryReference(SectionEvidenceBundleInfo primaryBundle,
                                                     List<EvidenceInfo> evidences) {
        if (primaryBundle != null) {
            if (primaryBundle.getEvidenceReferences() != null && !primaryBundle.getEvidenceReferences().isEmpty()) {
                return primaryBundle.getEvidenceReferences().get(0);
            }
            if (primaryBundle.getFields() != null) {
                for (FieldEvidenceDetail field : primaryBundle.getFields()) {
                    if (field != null && field.getEvidence() != null) {
                        return field.getEvidence();
                    }
                }
            }
        }
        if (evidences == null || evidences.isEmpty()) {
            return null;
        }
        return toEvidenceReference(evidences.get(0));
    }

    /**
     * 老数据只包含 pageMetadata，新数据同时包含结构化列。这里合并两者，保证前端视图稳定。
     */
    private Map<String, Object> mergeStructuredMetadata(EvidenceSource evidence) {
        Map<String, Object> metadata = parseJsonMap(evidence.getPageMetadata());
        putIfPresent(metadata, "sourceType", evidence.getSourceType());
        putIfPresent(metadata, "discoveryMethod", evidence.getDiscoveryMethod());
        putIfPresent(metadata, "domain", evidence.getSourceDomain());
        putIfPresent(metadata, "reason", evidence.getDiscoveryReason());
        putIfPresent(metadata, "publishedAt", evidence.getPublishedAt());
        putIfPresent(metadata, "totalScore", evidence.getSourceScore());
        return metadata;
    }

    private String readString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private Boolean readBoolean(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private Integer readInteger(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> readStringList(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result.isEmpty() ? Collections.emptyList() : result;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            metadata.put(key, value);
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return new java.util.LinkedHashMap<>(Map.of("raw", json));
        }
    }
}
