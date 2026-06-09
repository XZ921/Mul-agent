package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 对话会话仓储。
 */
@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {
}
