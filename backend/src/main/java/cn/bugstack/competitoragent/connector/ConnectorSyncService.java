package cn.bugstack.competitoragent.connector;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.governance.ConnectorRuntimeRegistry;
import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.knowledge.KnowledgeDomainService;
import cn.bugstack.competitoragent.model.entity.ConnectorSyncRecord;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import cn.bugstack.competitoragent.repository.ConnectorSyncRecordRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 连接器同步定义服务。
 * <p>
 * 当前阶段只负责两件事：
 * 1. 暴露受控连接器定义，明确“连接器资料”属于哪类组织知识来源；
 * 2. 用显式开关保护运行时调度入口，避免在 Task 5.8 之前提前放量。
 * <p>
 * 这里故意不实现真正的外部授权、配额控制、异步执行和重试调度，
 * 以保证 Task 5.2 仍然停留在知识接入底座范围内。
 */
@Service
public class ConnectorSyncService {

    private static final String AUTHENTICATED_SOURCE_CATEGORY = "AUTHENTICATED_SOURCES";

    private static final List<ConnectorDefinition> SUPPORTED_DEFINITIONS = List.of(
            ConnectorDefinition.builder()
                    .connectorKey("confluence-space")
                    .connectorType("CONFLUENCE")
                    .connectorLabel("Confluence 空间")
                    .sourceCategory(AUTHENTICATED_SOURCE_CATEGORY)
                    .build(),
            ConnectorDefinition.builder()
                    .connectorKey("feishu-docs")
                    .connectorType("FEISHU")
                    .connectorLabel("飞书文档")
                    .sourceCategory(AUTHENTICATED_SOURCE_CATEGORY)
                    .build(),
            ConnectorDefinition.builder()
                    .connectorKey("notion-pages")
                    .connectorType("NOTION")
                    .connectorLabel("Notion 页面")
                    .sourceCategory(AUTHENTICATED_SOURCE_CATEGORY)
                    .build()
    );

    private final KnowledgeDomainService knowledgeDomainService;
    private final ConnectorSyncRecordRepository connectorSyncRecordRepository;
    private final boolean runtimeEnabled;
    private final ConnectorRuntimeRegistry connectorRuntimeRegistry;

    public ConnectorSyncService(KnowledgeDomainService knowledgeDomainService,
                                ConnectorSyncRecordRepository connectorSyncRecordRepository,
                                @Value("${knowledge.connector-runtime.enabled:false}") boolean runtimeEnabled) {
        this(knowledgeDomainService, connectorSyncRecordRepository, runtimeEnabled, null);
    }

    /**
     * 连接器同步在运行时放量后，需要通过统一注册表判断当前租约是否已被其他任务占用。
     */
    @Autowired
    public ConnectorSyncService(KnowledgeDomainService knowledgeDomainService,
                                ConnectorSyncRecordRepository connectorSyncRecordRepository,
                                @Value("${knowledge.connector-runtime.enabled:false}") boolean runtimeEnabled,
                                ConnectorRuntimeRegistry connectorRuntimeRegistry) {
        this.knowledgeDomainService = knowledgeDomainService;
        this.connectorSyncRecordRepository = connectorSyncRecordRepository;
        this.runtimeEnabled = runtimeEnabled;
        this.connectorRuntimeRegistry = connectorRuntimeRegistry;
    }

    /**
     * 统一暴露当前已经认可的连接器定义。
     * 这里返回静态白名单，是为了先把“定义入口”做稳定，再把“运行时治理”留给后续任务。
     */
    public List<ConnectorDefinition> listDefinitions() {
        return List.copyOf(SUPPORTED_DEFINITIONS);
    }

    /**
     * 手工登记一次连接器同步请求。
     * <p>
     * 当运行时开关关闭时，这里必须明确阻断，防止系统在没有配额、占位和调度治理前直接执行同步。
     * 只有显式开启后，才允许把请求沉淀成最小同步记录，供后续运行时消费。
     */
    @Transactional
    public ConnectorSyncRecord scheduleManualSync(String domainKey,
                                                  String connectorKey,
                                                  String requestPayload,
                                                  List<String> sourceUrls) {
        KnowledgeDomain domain = knowledgeDomainService.resolveActiveDomain(domainKey, AUTHENTICATED_SOURCE_CATEGORY);
        ConnectorDefinition definition = resolveDefinition(connectorKey);
        if (!runtimeEnabled) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE,
                    "connector runtime disabled before Task 5.8, connectorKey=" + connectorKey);
        }
        ensureConnectorRuntimeAvailable(definition, sourceUrls);

        ConnectorSyncRecord record = ConnectorSyncRecord.builder()
                .knowledgeDomainId(domain.getId())
                .connectorKey(definition.getConnectorKey())
                .connectorType(definition.getConnectorType())
                .connectorLabel(definition.getConnectorLabel())
                .triggerType("MANUAL")
                .syncStatus("PENDING")
                .sourceCategory(definition.getSourceCategory())
                .sourceUrls(normalizeSourceUrls(sourceUrls))
                .requestPayload(requestPayload)
                .resultSummary("Connector sync request accepted for governed runtime dispatch.")
                .build();
        return connectorSyncRecordRepository.save(record);
    }

    /**
     * 连接器真正进入运行时前，必须先向统一注册表申请租约。
     * 一旦当前运行槽位已被占用，就直接返回结构化治理阻断结果，不再继续登记同步记录。
     */
    private void ensureConnectorRuntimeAvailable(ConnectorDefinition definition, List<String> sourceUrls) {
        if (connectorRuntimeRegistry == null) {
            return;
        }
        QuotaDecision decision = connectorRuntimeRegistry.acquireLease(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                definition.getConnectorKey(),
                GovernanceDefaults.CONNECTOR_RUNTIME_SLOT_SYNC_PULL,
                "manual-sync:" + definition.getConnectorKey(),
                Duration.ofMinutes(30),
                normalizeSourceUrls(sourceUrls)
        );
        if (decision != null && !decision.isAllowed()) {
            throw new GovernanceBlockException(decision);
        }
    }

    /**
     * 连接器定义必须来自显式白名单，否则后续同步记录就无法解释其治理边界。
     */
    private ConnectorDefinition resolveDefinition(String connectorKey) {
        if (!StringUtils.hasText(connectorKey)) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "connectorKey");
        }
        return SUPPORTED_DEFINITIONS.stream()
                .filter(definition -> definition.getConnectorKey().equalsIgnoreCase(connectorKey))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ResultCode.PARAM_VALUE_INVALID,
                        "unknown connector definition, connectorKey=" + connectorKey
                ));
    }

    /**
     * 先对来源链接做去重和裁剪，保证同步记录仍然能回指原始入口地址。
     */
    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String sourceUrl : sourceUrls) {
            if (StringUtils.hasText(sourceUrl)) {
                normalized.add(sourceUrl.trim());
            }
        }
        return new ArrayList<>(normalized);
    }
}
