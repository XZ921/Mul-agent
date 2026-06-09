package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.TaskPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务计划版本仓储。
 */
@Repository
public interface TaskPlanRepository extends JpaRepository<TaskPlan, Long> {

    List<TaskPlan> findByTaskIdOrderByPlanVersionAsc(Long taskId);

    Optional<TaskPlan> findFirstByTaskIdOrderByPlanVersionDesc(Long taskId);

    Optional<TaskPlan> findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(Long taskId);

    void deleteByTaskId(Long taskId);
}
