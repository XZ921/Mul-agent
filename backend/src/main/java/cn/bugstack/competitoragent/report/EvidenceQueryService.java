package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
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
