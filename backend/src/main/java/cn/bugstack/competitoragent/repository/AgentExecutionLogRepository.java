package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.AgentExecutionLog;
import cn.bugstack.competitoragent.model.enums.AgentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentExecutionLogRepository extends JpaRepository<AgentExecutionLog, Long> {

    List<AgentExecutionLog> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<AgentExecutionLog> findByTaskIdAndAgentTypeOrderByCreatedAtAsc(Long taskId, AgentType agentType);

    List<AgentExecutionLog> findByTraceIdOrderByCreatedAtAsc(String traceId);

    void deleteByTaskId(Long taskId);
}
