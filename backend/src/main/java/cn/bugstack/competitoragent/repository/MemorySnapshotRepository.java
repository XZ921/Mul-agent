package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MemorySnapshotRepository extends JpaRepository<MemorySnapshot, Long> {

    List<MemorySnapshot> findByTaskIdOrderByIdDesc(Long taskId);

    void deleteByTaskId(Long taskId);

    /**
     * Task 5.4.a 先提供按记忆层查询的正式入口，
     * 让后续融合服务可以显式消费“短期记忆”而不是扫描全部快照再靠约定过滤。
     */
    List<MemorySnapshot> findByMemoryLayerOrderByIdDesc(String memoryLayer);
}
