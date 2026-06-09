package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.FormDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

/**
 * 任务草稿仓储。
 */
@Repository
public interface FormDraftRepository extends JpaRepository<FormDraft, Long> {

    Optional<FormDraft> findTopByConversationSessionIdOrderByUpdatedAtDesc(Long conversationSessionId);

    void deleteByTaskId(Long taskId);

    void deleteByConversationSessionIdIn(Collection<Long> conversationSessionIds);
}
