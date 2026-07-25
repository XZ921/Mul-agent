package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** mutation 主事务回滚后的独立失败收口，只写人工停点，不创建任何计划或节点。 */
@Component
@RequiredArgsConstructor
public class DynamicPlanMutationFailureHandler {

    private final TaskNodeRepository nodeRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void close(TaskNode triggerNode, String detail) {
        triggerNode.setStatus(TaskNodeStatus.WAITING_INTERVENTION);
        triggerNode.setFailureCategory(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED);
        triggerNode.setInterventionReason("MUTATION_MATERIALIZATION_FAILED: " + detail);
        nodeRepository.save(triggerNode);
    }
}
