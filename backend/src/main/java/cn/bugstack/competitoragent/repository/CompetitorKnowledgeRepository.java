package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompetitorKnowledgeRepository extends JpaRepository<CompetitorKnowledge, Long> {

    List<CompetitorKnowledge> findByTaskIdOrderByIdAsc(Long taskId);

    Optional<CompetitorKnowledge> findByTaskIdAndCompetitorName(Long taskId, String competitorName);

    void deleteByTaskId(Long taskId);
}
