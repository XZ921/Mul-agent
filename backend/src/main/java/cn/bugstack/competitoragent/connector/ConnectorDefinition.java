package cn.bugstack.competitoragent.connector;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 连接器定义对象。
 * <p>
 * Task 5.2.e 先把“系统认识哪些受控连接器、它们会映射成什么资料分类”沉淀成正式对象，
 * 这样后续无论是资料接入还是 Task 5.8 的运行时治理，都有统一定义可复用。
 */
@Getter
@Builder
@EqualsAndHashCode
public class ConnectorDefinition {

    /**
     * 连接器稳定标识，后续资料入库与同步记录都会复用这个键做追踪。
     */
    private final String connectorKey;

    /**
     * 连接器类型用于表达“这类连接器主要接什么系统”，而不是运行时实例状态。
     */
    private final String connectorType;

    /**
     * 给前端和日志展示的业务可读名称。
     */
    private final String connectorLabel;

    /**
     * 当前阶段所有正式连接器定义都映射为受控来源，避免混入上传或 AI 发现链路。
     */
    private final String sourceCategory;
}
