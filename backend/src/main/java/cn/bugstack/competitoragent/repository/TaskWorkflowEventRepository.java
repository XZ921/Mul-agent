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
