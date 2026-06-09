package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.MemoryReuseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 记忆复用留痕查询入口。
 * <p>
 * Task 5.4.a 先提供最小正式仓储，保证后续任意节点复用记忆时都能落到可审计对象，
 * 而不是散落在日志里无法结构化追溯。
 */
@Repository
public interface MemoryReuseRecordRepository extends JpaRepository<MemoryReuseRecord, Long> {

    List<MemoryReuseRecord> findByTaskIdOrderByIdAsc(Long taskId);
}
