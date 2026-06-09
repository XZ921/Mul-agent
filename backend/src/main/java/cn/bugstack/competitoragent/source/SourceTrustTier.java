package cn.bugstack.competitoragent.source;

/**
 * 来源可信度层级。
 * 用统一枚举表达候选来源在来源治理阶段的可信程度，
 * 既服务排序，也服务后续节点洞察与前端解释。
 */
public enum SourceTrustTier {

    HIGH(1.0D, "高可信"),
    MEDIUM(0.75D, "中可信"),
    LOW(0.5D, "低可信");

    private final double weight;
    private final String displayName;

    SourceTrustTier(double weight, String displayName) {
        this.weight = weight;
        this.displayName = displayName;
    }

    public double getWeight() {
        return weight;
    }

    public String getDisplayName() {
        return displayName;
    }
}
