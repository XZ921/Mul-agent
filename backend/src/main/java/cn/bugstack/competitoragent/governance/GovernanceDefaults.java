package cn.bugstack.competitoragent.governance;

/**
 * 组织级治理默认常量。
 * <p>
 * 当前阶段先用一组稳定默认键把统一治理能力接入主链路，
 * 为后续真正的组织上下文解析和多租户扩展预留一致入口，而不是让每条链路各自硬编码。
 */
public final class GovernanceDefaults {

    public static final String DEFAULT_ORGANIZATION_KEY = "default-organization";

    public static final String TASK_SCOPE = "TASK";
    public static final String MODEL_SCOPE = "MODEL";
    public static final String KNOWLEDGE_SCOPE = "KNOWLEDGE";
    public static final String EXPORT_SCOPE = "EXPORT";

    public static final String TASK_CONCURRENCY_KEY = "TASK_CONCURRENCY";
    public static final String MODEL_DAILY_BUDGET_KEY = "MODEL_DAILY_BUDGET";
    public static final String KNOWLEDGE_INGESTION_KEY = "KNOWLEDGE_INGESTION";
    public static final String EXPORT_PACKAGE_KEY = "EXPORT_PACKAGE";

    public static final String CONNECTOR_RUNTIME_SLOT_SYNC_PULL = "SYNC_PULL";

    private GovernanceDefaults() {
    }
}
