package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AI 调用审计仓储。
 * <p>
 * 后续任务详情、回放和治理摘要都从这里读取结构化审计事实，
 * 而不是回头解析散落在日志文件中的文本。
 */
public interface AiCallAuditRecordRepository extends JpaRepository<AiCallAuditRecord, Long> {

    List<AiCallAuditRecord> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    Optional<AiCallAuditRecord> findTopByTaskIdAndNodeNameOrderByCreatedAtDesc(Long taskId, String nodeName);

    void deleteByTaskId(Long taskId);
}
