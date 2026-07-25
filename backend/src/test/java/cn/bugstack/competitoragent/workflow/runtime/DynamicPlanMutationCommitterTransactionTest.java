package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionOrigin;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.CompensationGraphAssembler;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.TaskPlanVersioner;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** 验证 checkpoint 失败时计划、节点和任务版本不会产生半提交。 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({DynamicPlanMutationCommitter.class, DynamicTaskGraphService.class,
        TaskPlanVersioner.class, CompensationGraphAssembler.class,
        DynamicPlanMutationCommitterTransactionTest.ObjectMapperConfig.class})
class DynamicPlanMutationCommitterTransactionTest {

    @Autowired private DynamicPlanMutationCommitter committer;
    @Autowired private AnalysisTaskRepository taskRepository;
    @Autowired private TaskNodeRepository nodeRepository;
    @Autowired private TaskPlanRepository planRepository;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private OrchestrationTraceService traceService;

    @Test
    void shouldRollbackPlanNodesAndTaskVersionWhenCheckpointPersistenceFails() throws Exception {
        // 事务回滚必须验证 Spring AOP 代理路径；手动 new 的兼容构造器不会触发 @Transactional。
        assertThat(AopUtils.isAopProxy(committer)).isTrue();

        AnalysisTask task = taskRepository.save(AnalysisTask.builder()
                .taskName("day2-atomic-commit").subjectProduct("subject")
                .competitorNames("[\"Notion\"]").status(AnalysisTaskStatus.RUNNING).build());
        WorkflowPlan basePlan = WorkflowPlan.builder().planVersion(1).branchKey("root")
                .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("quality_check_final").displayName("质量终审")
                        .agentType(AgentType.REVIEWER.name()).executionOrder(1).branchKey("root").build()))
                .build();
        TaskPlan parentPlan = planRepository.save(TaskPlan.builder()
                .taskId(task.getId()).planVersion(1).branchKey("root").planType("INITIAL")
                .active(true).planSnapshot(objectMapper.writeValueAsString(basePlan)).build());
        task.setCurrentPlanVersionId(parentPlan.getId());
        task.setCurrentPlanVersion(1);
        taskRepository.save(task);
        TaskNode triggerNode = nodeRepository.save(TaskNode.builder()
                .taskId(task.getId()).nodeName("quality_check_final").displayName("质量终审")
                .agentType(AgentType.REVIEWER).dependsOn("[]").status(TaskNodeStatus.SUCCESS)
                .executionOrder(1).planVersionId(parentPlan.getId()).branchKey("root").build());

        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-day2-rollback").taskId(task.getId())
                .triggerNodeName(triggerNode.getNodeName()).decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("APPEND_DYNAMIC_BRANCH").actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources").affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .reason("补齐 Notion pricing 官网证据")
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.FULL_SOURCE).build().normalized();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-od-day2-rollback").decisionId(decision.getDecisionId())
                .mutationType("APPEND_NODES").dynamicAction("CREATE_SUPPLEMENT_BRANCH")
                .branchReason("ORCHESTRATOR_DECISION")
                .nodeTemplates(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("collect_revision_evidence_v2_1").displayName("补充证据采集")
                        .agentType(AgentType.COLLECTOR.name()).nodeConfig("{}").build()))
                .sourceUrls(decision.getSourceUrls()).evidenceState(EvidenceState.FULL_SOURCE).build();
        doThrow(new IllegalStateException("checkpoint write failed"))
                .when(traceService).recordCheckpoint(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> committer.commit(task.getId(), task, parentPlan, triggerNode, mutation,
                decision, basePlan, Map.of(triggerNode.getNodeName(), triggerNode)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("checkpoint write failed");

        AnalysisTask reloadedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloadedTask.getCurrentPlanVersionId()).isEqualTo(parentPlan.getId());
        assertThat(reloadedTask.getCurrentPlanVersion()).isEqualTo(1);
        assertThat(planRepository.findByTaskIdOrderByPlanVersionAsc(task.getId()))
                .singleElement().satisfies(plan -> assertThat(plan.isActive()).isTrue());
        assertThat(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId()))
                .singleElement().satisfies(node -> assertThat(node.getNodeName()).isEqualTo("quality_check_final"));
        assertThat(planRepository.findByTaskIdAndDecisionId(task.getId(), decision.getDecisionId())).isEmpty();
    }

    @TestConfiguration
    static class ObjectMapperConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
