package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.WorkflowDeadLetterRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowDeadLetterRecordRepository extends JpaRepository<WorkflowDeadLetterRecord, Long> {

    List<WorkflowDeadLetterRecord> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
