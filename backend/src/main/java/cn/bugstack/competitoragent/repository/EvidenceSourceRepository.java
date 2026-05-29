package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EvidenceSourceRepository extends JpaRepository<EvidenceSource, Long> {

    Optional<EvidenceSource> findByTaskIdAndEvidenceId(Long taskId, String evidenceId);

    List<EvidenceSource> findByTaskIdOrderByEvidenceIdAsc(Long taskId);

    List<EvidenceSource> findByTaskIdAndCompetitorNameOrderByEvidenceIdAsc(Long taskId, String competitorName);

    long countByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);
}
