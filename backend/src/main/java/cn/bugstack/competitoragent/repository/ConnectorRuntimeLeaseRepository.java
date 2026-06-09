package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.ConnectorRuntimeLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConnectorRuntimeLeaseRepository extends JpaRepository<ConnectorRuntimeLease, Long> {

    /**
     * 统一查询同一组织下某个连接器运行槽位当前是否已有正式持有中的租约。
     */
    Optional<ConnectorRuntimeLease> findFirstByOrganizationKeyAndConnectorKeyAndRuntimeSlotAndLeaseStatusOrderByAcquiredAtDescIdDesc(
            String organizationKey,
            String connectorKey,
            String runtimeSlot,
            String leaseStatus
    );
}
