package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByTaskId(Long taskId);

    boolean existsByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);
}
