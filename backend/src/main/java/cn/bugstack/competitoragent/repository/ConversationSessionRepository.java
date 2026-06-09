package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 对话会话仓储。
 */
@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    List<ConversationSession> findByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);
}
