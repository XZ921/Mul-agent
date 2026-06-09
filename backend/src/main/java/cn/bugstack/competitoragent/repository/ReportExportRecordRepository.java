package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.ReportExportRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 正式导出记录仓储。
 * <p>
 * 当前阶段先承担“按任务查看历史记录”和“为新导出分配下一个版本号”的最小职责，
 * 后续再继续补齐更细粒度的交付查询能力。
 */
@Repository
public interface ReportExportRecordRepository extends JpaRepository<ReportExportRecord, Long> {

    List<ReportExportRecord> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    Optional<ReportExportRecord> findTopByTaskIdOrderByExportVersionDesc(Long taskId);

    void deleteByTaskId(Long taskId);
}
