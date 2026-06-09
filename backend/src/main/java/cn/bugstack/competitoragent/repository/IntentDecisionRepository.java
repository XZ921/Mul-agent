package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.IntentDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 意图决策审计仓储。
 */
@Repository
public interface IntentDecisionRepository extends JpaRepository<IntentDecision, Long> {

    List<IntentDecision> findByConversationSessionIdOrderByIdAsc(Long conversationSessionId);
}
