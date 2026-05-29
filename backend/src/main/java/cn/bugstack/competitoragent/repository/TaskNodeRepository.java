package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DAG 任务节点 Repository
 */
@Repository
public interface TaskNodeRepository extends JpaRepository<TaskNode, Long> {

    /** 按任务 ID 和节点名称查询 */
    Optional<TaskNode> findByTaskIdAndNodeName(Long taskId, String nodeName);

    /** 查询某任务的所有节点，按执行顺序排列 */
    List<TaskNode> findByTaskIdOrderByExecutionOrderAsc(Long taskId);

    /** 统计某任务中指定状态的节点数 */
    long countByTaskIdAndStatus(Long taskId, TaskNodeStatus status);

    /** 查询某任务中所有依赖已就绪的 PENDING 节点 */
    @Query("SELECT n FROM TaskNode n WHERE n.taskId = :taskId AND n.status = 'PENDING' ORDER BY n.executionOrder ASC")
    List<TaskNode> findPendingNodesByTaskId(@Param("taskId") Long taskId);

    /** 批量更新节点状态 */
    @Modifying
    @Query("UPDATE TaskNode n SET n.status = :status WHERE n.id IN :ids")
    void batchUpdateStatus(@Param("ids") List<Long> ids, @Param("status") TaskNodeStatus status);
}
