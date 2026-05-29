package cn.bugstack.competitoragent.repository;

import cn.bugstack.competitoragent.model.entity.AnalysisSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 分析模板 Repository
 */
@Repository
public interface AnalysisSchemaRepository extends JpaRepository<AnalysisSchema, Long> {

    /** 按名称查询 */
    Optional<AnalysisSchema> findByName(String name);

    /** 判断名称是否已存在 */
    boolean existsByName(String name);
}
