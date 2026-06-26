package cn.bugstack.competitoragent.architecture;

import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.agent.analyzer.CompetitorAnalysisAgent;
import cn.bugstack.competitoragent.agent.citation.CitationAgent;
import cn.bugstack.competitoragent.agent.collector.CollectorAgent;
import cn.bugstack.competitoragent.agent.extractor.SchemaExtractorAgent;
import cn.bugstack.competitoragent.agent.reviewer.QualityReviewAgent;
import cn.bugstack.competitoragent.agent.writer.ReportWriterAgent;
import cn.bugstack.competitoragent.workflow.CollectorPlanTemplateFactory;
import cn.bugstack.competitoragent.workflow.ExecutionPlanDefinitionBuilder;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 架构白名单台账。
 * 当前阶段只负责把历史耦合显式登记出来，真正的边界约束仍由 ArchUnit 规则本身执行。
 */
final class ArchitectureWhitelist {

    /**
     * 所有历史豁免都必须记录规则名、类名、原因和预计回收阶段，
     * 这样后续回看时能够知道“为什么还留着”和“准备在哪个阶段清理”。
     */
    static final List<Exemption> EXEMPTIONS = List.of(
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    BaseAgent.class.getName(),
                    "legacy runtime support 仍直接承担日志持久化与上下文增强依赖，phase1 明确不改 BaseAgent 主体。",
                    "phase5-modularization-evaluation-task",
                    "A+B"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    CompetitorAnalysisAgent.class.getName(),
                    "knowledge 侧分析 Agent 仍保留历史 repository 直连，phase4a 只收口 facade 与 contract，不提前拆 analysis-intelligence。",
                    "phase5-modularization-evaluation-task",
                    "A"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    CollectorAgent.class.getName(),
                    "phase3b 明确只收口 evidence 边界，不承诺移除 CollectorAgent 继承 BaseAgent 带来的历史 repository 依赖。",
                    "phase5-modularization-evaluation-task",
                    "B"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    SchemaExtractorAgent.class.getName(),
                    "knowledge 抽取 Agent 当前仍沿用历史 repository 访问方式，phase4a 先收口知识读取 contract 再评估回收。",
                    "phase5-modularization-evaluation-task",
                    "A"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    QualityReviewAgent.class.getName(),
                    "report 质量复核 Agent 仍在 legacy 结构里直接读取持久化对象，需等待 report facade 与消费视图稳定后再回收。",
                    "phase5-modularization-evaluation-task",
                    "B"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    ReportWriterAgent.class.getName(),
                    "report 生成 Agent 当前仍直接读取证据与报告持久化对象，phase4b 之前不在本阶段提前改造其历史实现。",
                    "phase5-modularization-evaluation-task",
                    "B"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    CitationAgent.class.getName(),
                    "P3-4 Citation Agent 当前沿用既有 Agent 直连 repository 读取证据的模式，先保证 Reviewer 前引用核查闭环稳定，后续与 phase5 模块化评估一起回收。",
                    "phase5-modularization-evaluation-task",
                    "B"
            ),
            new Exemption(
                    "workflow_should_not_depend_on_business_agent_implementations",
                    WorkflowFactory.class.getName(),
                    "WorkflowFactory 仍承担把搜索规划翻译成 Collector 节点配置的历史职责，需等待 phase3b collection facade 稳定后统一回收。",
                    "phase3b-collection-evidence-task",
                    "B"
            ),
            new Exemption(
                    "workflow_should_not_depend_on_business_agent_implementations",
                    CollectorPlanTemplateFactory.class.getName(),
                    "2.1.1/2.1.2 将 WorkflowFactory 的 collector 节点配置拼装拆到模板工厂，但底层仍复用同一份 CollectorNodeConfig 契约，后续随 collection facade 一并降耦。",
                    "phase3b-collection-evidence-task",
                    "B"
            ),
            new Exemption(
                    "workflow_should_not_depend_on_business_agent_implementations",
                    ExecutionPlanDefinitionBuilder.class.getName(),
                    "ExecutionPlanDefinitionBuilder 当前只是承接 WorkflowFactory 拆出的规划装配职责，仍需要读取 Collector 节点契约；后续与 collection facade 一起回收。",
                    "phase3b-collection-evidence-task",
                    "B"
            )
    );

    private ArchitectureWhitelist() {
    }

    /**
     * ArchUnit 规则只通过规则名读取对应白名单，避免把类名再次硬编码回规则入口。
     */
    static List<String> classNamesForRule(String ruleName) {
        return EXEMPTIONS.stream()
                .filter(exemption -> exemption.ruleName().equals(ruleName))
                .map(Exemption::className)
                .toList();
    }

    /**
     * 统一生成规则级白名单摘要，便于失败时快速看到当前还有哪些历史豁免未回收。
     */
    static String summaryForRule(String ruleName) {
        List<String> simpleNames = EXEMPTIONS.stream()
                .filter(exemption -> exemption.ruleName().equals(ruleName))
                .map(Exemption::className)
                .map(ArchitectureWhitelist::simpleName)
                .toList();
        return simpleNames.isEmpty() ? "none" : simpleNames.stream().collect(Collectors.joining(", "));
    }

    private static String simpleName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    /**
     * 白名单记录本身只表达“谁、为什么、预计何时回收”，不在这里加入规则执行逻辑。
     */
    record Exemption(
            String ruleName,
            String className,
            String reason,
            String removeByPhase,
            String owner
    ) {
    }
}
