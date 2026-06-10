package cn.bugstack.competitoragent.architecture;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.agent.analyzer.CompetitorAnalysisAgent;
import cn.bugstack.competitoragent.agent.capability.AgentCapabilityRegistry;
import cn.bugstack.competitoragent.agent.collector.CollectorAgent;
import cn.bugstack.competitoragent.agent.extractor.SchemaExtractorAgent;
import cn.bugstack.competitoragent.agent.reviewer.QualityReviewAgent;
import cn.bugstack.competitoragent.agent.writer.ReportWriterAgent;
import cn.bugstack.competitoragent.agent.capability.SpringAgentCapabilityRegistry;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Phase 1 先建立后端模块依赖基线。
 * 这里的规则默认从严格边界出发，后续如果发现历史耦合，会通过具名豁免或收窄作用域来显式记录，
 * 避免让新的跨模块依赖在没有感知的情况下继续扩散。
 */
@AnalyzeClasses(
        packages = "cn.bugstack.competitoragent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class BackendModuleDependencyTest {

    /**
     * 这批类是当前代码库里仍然直接依赖 repository 的历史 Agent 基线。
     * Phase 1 先把它们显式登记出来，避免未来新增同类耦合时被历史问题“淹没”。
     * 后续每完成一段 Agent 去持久化改造，就应从这里删除对应豁免。
     */
    private static final String LEGACY_AGENT_REPOSITORY_EXEMPTIONS =
            BaseAgent.class.getSimpleName()
                    + ", " + CompetitorAnalysisAgent.class.getSimpleName()
                    + ", " + CollectorAgent.class.getSimpleName()
                    + ", " + SchemaExtractorAgent.class.getSimpleName()
                    + ", " + QualityReviewAgent.class.getSimpleName()
                    + ", " + ReportWriterAgent.class.getSimpleName();

    /**
     * WorkflowFactory 仍然承担“把搜索规划翻译成 Collector 节点配置”的历史职责，
     * 因此当前阶段会直接引用 CollectorNodeConfig。
     * Task 3 只允许做具名类级豁免，明确记录这处历史耦合，后续在 collection facade 稳定后回收。
     */
    private static final String LEGACY_WORKFLOW_AGENT_PACKAGE_EXEMPTIONS =
            WorkflowFactory.class.getSimpleName();

    @ArchTest
    static final ArchRule runtime_baseline_should_not_depend_on_repositories_or_entities =
            noClasses()
                    .that().haveFullyQualifiedName(Agent.class.getName())
                    .or().haveFullyQualifiedName(AgentContext.class.getName())
                    .or().haveFullyQualifiedName(AgentResult.class.getName())
                    .or().haveFullyQualifiedName(AgentCapabilityRegistry.class.getName())
                    .or().haveFullyQualifiedName(SpringAgentCapabilityRegistry.class.getName())
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..model.entity..")
                    .because("Phase 1 freezes a minimal runtime baseline. Runtime contracts and registry must stay free of repositories and entities.");

    @ArchTest
    static final ArchRule workflow_should_not_depend_on_business_agent_implementations =
            noClasses()
                    .that().resideInAPackage("..workflow..")
                    .and().doNotHaveFullyQualifiedName(WorkflowFactory.class.getName())
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..agent.collector..",
                            "..agent.analyzer..",
                            "..agent.extractor..",
                            "..agent.reviewer..",
                            "..agent.writer..",
                            "..agent.conversation..")
                    .because("workflow should trigger runtime capabilities instead of directly coupling to concrete business Agent implementations. "
                            + "Legacy class-level exemption: " + LEGACY_WORKFLOW_AGENT_PACKAGE_EXEMPTIONS);

    @ArchTest
    static final ArchRule agent_classes_should_not_access_task_repositories =
            noClasses()
                    .that().resideInAPackage("..agent..")
                    .and().doNotHaveFullyQualifiedName(BaseAgent.class.getName())
                    .and().doNotHaveFullyQualifiedName(CompetitorAnalysisAgent.class.getName())
                    .and().doNotHaveFullyQualifiedName(CollectorAgent.class.getName())
                    .and().doNotHaveFullyQualifiedName(SchemaExtractorAgent.class.getName())
                    .and().doNotHaveFullyQualifiedName(QualityReviewAgent.class.getName())
                    .and().doNotHaveFullyQualifiedName(ReportWriterAgent.class.getName())
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..")
                    .because("Agent implementations should return structured results; task-runtime owns persistence and state transitions. "
                            + "Legacy baseline exemptions: " + LEGACY_AGENT_REPOSITORY_EXEMPTIONS);

    @ArchTest
    static final ArchRule controllers_should_not_depend_on_workflow_internals =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAnyPackage("..workflow..")
                    .because("REST controllers should call application services or facades, not runtime internals.");

    @ArchTest
    static final ArchRule report_should_not_depend_on_source_provider_implementations =
            noClasses()
                    .that().resideInAPackage("..report..")
                    .should().dependOnClassesThat().resideInAnyPackage("..source..", "..search..")
                    .because("report-delivery consumes report/evidence views, not collection provider internals.");
}
