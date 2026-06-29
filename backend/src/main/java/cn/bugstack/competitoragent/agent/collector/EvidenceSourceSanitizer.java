package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import org.springframework.stereotype.Component;

/**
 * EvidenceSource 入库前安全化。
 * discoveryReason 已迁移为 TEXT，因此保留原始诊断文本；
 * 其余仍受数据库长度限制的字段统一裁剪，避免真实补源数据在持久化阶段失败。
 */
@Component
public class EvidenceSourceSanitizer {

    /**
     * 统一裁剪 EvidenceSource 中仍受长度约束的字段。
     * 这里直接在原对象上修正，保证后续持久化与下游引用看到的是同一份安全数据。
     */
    public EvidenceSource sanitize(EvidenceSource source) {
        if (source == null) {
            return null;
        }
        source.setCompetitorName(truncate(source.getCompetitorName(), 100));
        source.setEvidenceId(truncate(source.getEvidenceId(), 100));
        source.setTitle(truncate(source.getTitle(), 500));
        source.setUrl(truncate(source.getUrl(), 2048));
        source.setSourceType(truncate(source.getSourceType(), 50));
        source.setDiscoveryMethod(truncate(source.getDiscoveryMethod(), 50));
        source.setSourceCategory(truncate(source.getSourceCategory(), 50));
        source.setSourceDomain(truncate(source.getSourceDomain(), 255));
        source.setPublishedAt(truncate(source.getPublishedAt(), 30));
        return source;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
