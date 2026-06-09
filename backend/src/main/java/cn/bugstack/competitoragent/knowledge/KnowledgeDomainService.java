package cn.bugstack.competitoragent.knowledge;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import cn.bugstack.competitoragent.repository.KnowledgeDomainRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识域解析服务。
 * <p>
 * Task 5.2.b 先把“目标知识域是否存在、是否启用、是否允许当前来源类型接入”
 * 这三类判断收口到一个服务里，避免后续接入入口各自复制一套校验逻辑。
 */
@Service
public class KnowledgeDomainService {

    private final KnowledgeDomainRepository knowledgeDomainRepository;

    public KnowledgeDomainService(KnowledgeDomainRepository knowledgeDomainRepository) {
        this.knowledgeDomainRepository = knowledgeDomainRepository;
    }

    /**
     * 统一校验知识域是否可接收当前资料来源。
     * 这里故意只做“存在性 + 状态 + 来源白名单”三类轻量治理，
     * 不提前引入 Task 5.8 才需要的连接器运行时、配额或复杂授权。
     */
    public KnowledgeDomain resolveActiveDomain(String domainKey, String sourceCategory) {
        KnowledgeDomain domain = knowledgeDomainRepository.findByDomainKey(domainKey)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "knowledgeDomain=" + domainKey));
        if (!"ACTIVE".equalsIgnoreCase(domain.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_VALUE_INVALID,
                    "知识域未启用, domainKey=" + domainKey);
        }

        List<String> allowedSourceCategories = domain.getAllowedSourceCategories();
        if (allowedSourceCategories != null
                && !allowedSourceCategories.isEmpty()
                && StringUtils.hasText(sourceCategory)
                && allowedSourceCategories.stream().noneMatch(sourceCategory::equalsIgnoreCase)) {
            throw new BusinessException(ResultCode.PARAM_VALUE_INVALID,
                    "知识域不允许该资料来源, domainKey=" + domainKey + ", sourceCategory=" + sourceCategory);
        }
        return domain;
    }

    /**
     * 前端最小接入入口只需要看到“当前有哪些启用中的知识域可以选”，
     * 因此这里显式只返回 ACTIVE 域，避免把停用域带到用户入口再让页面自己猜能否使用。
     */
    public List<KnowledgeDomain> listActiveDomains() {
        return knowledgeDomainRepository.findByStatusOrderByIdAsc("ACTIVE");
    }
}
