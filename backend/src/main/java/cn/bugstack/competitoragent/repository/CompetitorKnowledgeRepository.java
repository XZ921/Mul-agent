package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompetitorKnowledgeRepository extends JpaRepository<CompetitorKnowledge, Long> {

    List<CompetitorKnowledge> findByTaskIdOrderByIdAsc(Long taskId);

    /**
     * Task 5.4.a 先把领域记忆查询口固定下来，
     * 后续融合链路可以显式拉取可复用的领域知识，而不是混入所有竞品知识记录。
     */
    List<CompetitorKnowledge> findByMemoryLayerOrderByIdAsc(String memoryLayer);

    Optional<CompetitorKnowledge> findByTaskIdAndCompetitorName(Long taskId, String competitorName);

    void deleteByTaskId(Long taskId);
}
