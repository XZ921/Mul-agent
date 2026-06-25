package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 工作流事件出站箱 Repository。
 */
@Repository
public interface TaskWorkflowEventRepository extends JpaRepository<TaskWorkflowEvent, Long> {

    Optional<TaskWorkflowEvent> findByEventId(String eventId);

    void deleteByTaskId(Long taskId);

    Optional<TaskWorkflowEvent> findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(Long taskId,
                                                                                  WorkflowEventType eventType);

    /**
     * 对话入口只需要“最近一次编排决策事件”这个稳定读模型入口，
     * 不应该在上层再次感知 workflow 内部枚举常量。
     */
    default Optional<TaskWorkflowEvent> findLatestOrchestrationDecisionEvent(Long taskId) {
        return findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                taskId,
                WorkflowEventType.ORCHESTRATION_DECISION_RECORDED
        );
    }

    boolean existsByTaskIdAndEventTypeAndDeliveryStatusIn(Long taskId,
                                                          WorkflowEventType eventType,
                                                          Collection<String> deliveryStatuses);

    @Query("""
            SELECT event
            FROM TaskWorkflowEvent event
            WHERE event.deliveryStatus IN :deliveryStatuses
              AND event.nextAttemptAt <= :deadline
            ORDER BY event.createdAt ASC
            """)
    List<TaskWorkflowEvent> findDispatchCandidates(@Param("deliveryStatuses") Collection<String> deliveryStatuses,
                                                   @Param("deadline") LocalDateTime deadline,
                                                   Pageable pageable);
}
