package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskNodeExecutionAttemptRepository extends JpaRepository<TaskNodeExecutionAttempt, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<TaskNodeExecutionAttempt> findByTaskIdAndNodeIdOrderByAttemptNoAsc(Long taskId, Long nodeId);

    Optional<TaskNodeExecutionAttempt> findTopByTaskIdAndNodeIdOrderByAttemptNoDesc(Long taskId, Long nodeId);
}
