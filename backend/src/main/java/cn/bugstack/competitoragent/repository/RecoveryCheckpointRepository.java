package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.RecoveryCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 恢复点仓储。
 */
@Repository
public interface RecoveryCheckpointRepository extends JpaRepository<RecoveryCheckpoint, Long> {

    List<RecoveryCheckpoint> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
