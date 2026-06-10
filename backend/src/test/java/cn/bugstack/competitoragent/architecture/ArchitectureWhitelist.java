package cn.bugstack.competitoragent.architecture;

import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.agent.analyzer.CompetitorAnalysisAgent;
import cn.bugstack.competitoragent.agent.collector.CollectorAgent;
import cn.bugstack.competitoragent.agent.extractor.SchemaExtractorAgent;
import cn.bugstack.competitoragent.agent.reviewer.QualityReviewAgent;
import cn.bugstack.competitoragent.agent.writer.ReportWriterAgent;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 2 Task 1 先把历史耦合登记成具名白名单模型。
 * 当前阶段只负责“把事实记清楚”，不在这里承载规则判断，真正生效的边界仍由 BackendModuleDependencyTest 接手。
 */
final class ArchitectureWhitelist {

    /**
     * 当前已有的历史豁免全部要求显式写出规则名、类名、原因与计划回收阶段。
     * 这样后续阶段不论是否真正完成回收，至少都能在 progress / ledger 中追踪“为什么还留着”。
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
                    "knowledge 抽取 Agent 当前仍沿用历史 repository 访问方式，phase4a 先收口知识读接口与 contract 归属。",
                    "phase5-modularization-evaluation-task",
                    "A"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    QualityReviewAgent.class.getName(),
                    "report 质量复核 Agent 仍在 legacy 结构中直接读取持久化对象，需等待 report facade 与消费视图稳定后再评估回收。",
                    "phase5-modularization-evaluation-task",
                    "B"
            ),
            new Exemption(
                    "agent_classes_should_not_access_task_repositories",
                    ReportWriterAgent.class.getName(),
                    "report 生成 Agent 当前仍直接读取证据与报告持久化对象，phase4b 之前不在本阶段提前修改其历史实现。",
                    "phase5-modularization-evaluation-task",
                    "B"
            ),
            new Exemption(
                    "workflow_should_not_depend_on_business_agent_implementations",
                    WorkflowFactory.class.getName(),
                    "WorkflowFactory 仍承担把搜索规划翻译成 Collector 节点配置的历史职责，需等待 phase3b 的 collection facade 稳定后再回收。",
                    "phase3b-collection-evidence-task",
                    "B"
            )
    );

    private ArchitectureWhitelist() {
    }

    /**
     * BackendModuleDependencyTest 只允许通过规则名读取具名豁免，
     * 避免再次把类名硬编码回测试入口。
     */
    static List<String> classNamesForRule(String ruleName) {
        return EXEMPTIONS.stream()
                .filter(exemption -> exemption.ruleName().equals(ruleName))
                .map(Exemption::className)
                .toList();
    }

    /**
     * 统一生成规则级白名单摘要，便于失败时快速看出当前有哪些历史豁免仍未回收。
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
     * 白名单记录本身只表达“谁、因为什么、预计何时回收”，不在这里加入规则执行逻辑。
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
