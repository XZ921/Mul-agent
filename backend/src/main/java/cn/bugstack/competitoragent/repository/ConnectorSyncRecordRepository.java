package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.ConnectorSyncRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConnectorSyncRecordRepository extends JpaRepository<ConnectorSyncRecord, Long> {

    List<ConnectorSyncRecord> findByKnowledgeDomainIdOrderByIdAsc(Long knowledgeDomainId);

    List<ConnectorSyncRecord> findByConnectorKeyOrderByIdDesc(String connectorKey);
}
