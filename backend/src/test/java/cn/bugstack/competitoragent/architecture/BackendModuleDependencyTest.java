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
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static cn.bugstack.competitoragent.architecture.ArchitecturePackageMapping.COLLECTION_PACKAGES;
import static cn.bugstack.competitoragent.architecture.ArchitecturePackageMapping.CONVERSATION_PACKAGES;
import static cn.bugstack.competitoragent.architecture.ArchitecturePackageMapping.REPORT_PACKAGES;
import static cn.bugstack.competitoragent.architecture.ArchitecturePackageMapping.RUNTIME_BASELINE_CLASS_NAMES;
import static cn.bugstack.competitoragent.architecture.ArchitecturePackageMapping.TASK_PACKAGES;

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

    @Test
    void taskPackageMappingShouldIncludeRuntimeOrchestrationPackage() {
        assertThat(TASK_PACKAGES).contains("cn.bugstack.competitoragent.orchestration..");
    }

    @ArchTest
    static final ArchRule runtime_baseline_should_not_depend_on_repositories_or_entities =
            noClasses()
                    .that(hasAnyRuntimeBaselineClassName())
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..model.entity..")
                    .because("Phase 1 freezes a minimal runtime baseline. Runtime contracts and registry must stay free of repositories and entities.");

    @ArchTest
    static final ArchRule task_should_not_depend_on_evidence_repository_directly =
            noClasses()
                    .that().resideInAnyPackage(packages(TASK_PACKAGES))
                    .and(isNotWhitelistedForRule("task_should_not_depend_on_evidence_repository_directly"))
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("cn.bugstack.competitoragent.repository.EvidenceSourceRepository")
                    .because("task-orchestration should stop adding direct evidence repository access and move through explicit cleanup or facade entry points. "
                            + "Legacy class-level exemptions: "
                            + ArchitectureWhitelist.summaryForRule("task_should_not_depend_on_evidence_repository_directly"));

    @ArchTest
    static final ArchRule workflow_should_not_depend_on_business_agent_implementations =
            noClasses()
                    .that().resideInAPackage("..workflow..")
                    .and(isNotWhitelistedForRule("workflow_should_not_depend_on_business_agent_implementations"))
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..agent.collector..",
                            "..agent.analyzer..",
                            "..agent.extractor..",
                            "..agent.reviewer..",
                            "..agent.writer..",
                            "..agent.conversation..")
                    .because("workflow should trigger runtime capabilities instead of directly coupling to concrete business Agent implementations. "
                            + "Legacy class-level exemptions: "
                            + ArchitectureWhitelist.summaryForRule("workflow_should_not_depend_on_business_agent_implementations"));

    @ArchTest
    static final ArchRule agent_classes_should_not_access_task_repositories =
            noClasses()
                    .that().resideInAPackage("..agent..")
                    .and(isNotWhitelistedForRule("agent_classes_should_not_access_task_repositories"))
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..")
                    .because("Agent implementations should return structured results; task-runtime owns persistence and state transitions. "
                            + "Legacy class-level exemptions: "
                            + ArchitectureWhitelist.summaryForRule("agent_classes_should_not_access_task_repositories"));

    @ArchTest
    static final ArchRule controllers_should_not_depend_on_workflow_internals =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAnyPackage("..workflow..")
                    .because("REST controllers should call application services or facades, not runtime internals.");

    @ArchTest
    static final ArchRule report_should_not_depend_on_collection_implementations =
            noClasses()
                    .that().resideInAnyPackage(packages(REPORT_PACKAGES))
                    .should().dependOnClassesThat().resideInAnyPackage(packages(COLLECTION_PACKAGES))
                    .because("report-delivery consumes stable evidence views, not collection implementation packages.");

    @ArchTest
    static final ArchRule conversation_should_not_depend_on_workflow_internals =
            noClasses()
                    .that().resideInAnyPackage(packages(CONVERSATION_PACKAGES))
                    .should().dependOnClassesThat().resideInAnyPackage("..workflow..")
                    .because("conversation-entry should call task facades or stable read views instead of workflow internals.");

    /**
     * ArchUnit 的 `resideInAnyPackage` 需要可变参数，这里把集中维护的映射常量转成统一入口，
     * 避免后续规则再次散落重复的字符串展开逻辑。
     */
    private static String[] packages(List<String> packagePatterns) {
        return packagePatterns.toArray(String[]::new);
    }

    /**
     * runtime baseline 是一组离散类而不是单个 package，
     * 因此这里按 FQCN 判断，保证 phase1 冻结下来的最小运行时边界继续由同一入口守护。
     */
    private static DescribedPredicate<JavaClass> hasAnyRuntimeBaselineClassName() {
        return new DescribedPredicate<>("phase1 runtime baseline classes") {
            @Override
            public boolean test(JavaClass input) {
                return RUNTIME_BASELINE_CLASS_NAMES.contains(input.getName());
            }
        };
    }

    /**
     * 所有历史豁免都必须从 ArchitectureWhitelist 读取，
     * 这样规则入口只保留“规则本身”，而不是再次堆叠一组散落常量。
     */
    private static DescribedPredicate<JavaClass> isNotWhitelistedForRule(String ruleName) {
        List<String> whitelistedClassNames = ArchitectureWhitelist.classNamesForRule(ruleName);
        return new DescribedPredicate<>("not whitelisted for rule " + ruleName) {
            @Override
            public boolean test(JavaClass input) {
                return !whitelistedClassNames.contains(input.getName());
            }
        };
    }
}
