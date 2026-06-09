package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrganizationQuotaSnapshotRepository extends JpaRepository<OrganizationQuotaSnapshot, Long> {

    /**
     * 统一按组织、作用域和配额键读取最新活动快照，
     * 让治理策略层无需回退到遍历全表再手工挑最新记录。
     */
    Optional<OrganizationQuotaSnapshot> findFirstByOrganizationKeyAndQuotaScopeAndQuotaKeyAndSnapshotStatusOrderBySnapshotAtDescIdDesc(
            String organizationKey,
            String quotaScope,
            String quotaKey,
            String snapshotStatus
    );
}
