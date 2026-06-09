package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDomainRepository extends JpaRepository<KnowledgeDomain, Long> {

    Optional<KnowledgeDomain> findByDomainKey(String domainKey);

    List<KnowledgeDomain> findByStatusOrderByIdAsc(String status);
}
