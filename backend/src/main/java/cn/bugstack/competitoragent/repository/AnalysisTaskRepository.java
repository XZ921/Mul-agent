package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分析任务 Repository
 */
@Repository
public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {

    /** 按状态查询任务 */
    List<AnalysisTask> findByStatusOrderByCreatedAtDesc(AnalysisTaskStatus status);

    /** 按状态和创建时间倒序查询所有任务 */
    List<AnalysisTask> findAllByOrderByCreatedAtDesc();

    /** 查询最近 N 条任务 */
    List<AnalysisTask> findTop10ByOrderByCreatedAtDesc();

    List<AnalysisTask> findAllByStatus(AnalysisTaskStatus status);
}
