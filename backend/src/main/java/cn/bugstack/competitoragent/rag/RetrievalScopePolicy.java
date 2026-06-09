package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 三层召回作用域策略。
 * <p>
 * 这个策略对象只负责回答两个问题：
 * 1. 某份 `KnowledgeDocument` 应该沉淀成哪些正式召回作用域；
 * 2. 每个作用域对应的 `scopeRefKey / knowledgeDomainKey / taskId` 是什么。
 * 它不负责检索排序，也不负责事实判断，保持“作用域边界”职责单一。
 */
@Component
public class RetrievalScopePolicy {

    /**
     * 根据知识文档的归属语义，计算它应进入哪些正式检索作用域。
     * <p>
     * 规则设计为：
     * 1. 任务级文档只进入 `TASK`；
     * 2. 领域级文档进入 `DOMAIN`；
     * 3. 组织级文档若绑定了 `knowledgeDomainKey`，同时进入 `DOMAIN + ORGANIZATION`，
     *    这样后续可以先命中领域，再在必要时回退到组织级公共资料；
     * 4. 组织级文档若没有领域键，则只进入 `ORGANIZATION`。
     */
    public List<ScopeBinding> resolveBindings(KnowledgeDocument document) {
        List<ScopeBinding> bindings = new ArrayList<>();
        if (document == null) {
            return bindings;
        }

        RetrievalScope knowledgeScope = RetrievalScope.fromText(document.getKnowledgeScope());
        if (knowledgeScope == RetrievalScope.TASK) {
            bindings.add(new ScopeBinding(
                    RetrievalScope.TASK,
                    document.getTaskId(),
                    buildTaskScopeRefKey(document.getTaskId()),
                    null
            ));
            return bindings;
        }

        if (knowledgeScope == RetrievalScope.DOMAIN) {
            bindings.add(new ScopeBinding(
                    RetrievalScope.DOMAIN,
                    null,
                    document.getKnowledgeDomainKey(),
                    document.getKnowledgeDomainKey()
            ));
            return bindings;
        }

        if (StringUtils.hasText(document.getKnowledgeDomainKey())) {
            bindings.add(new ScopeBinding(
                    RetrievalScope.DOMAIN,
                    null,
                    document.getKnowledgeDomainKey(),
                    document.getKnowledgeDomainKey()
            ));
        }
        bindings.add(new ScopeBinding(
                RetrievalScope.ORGANIZATION,
                null,
                "ORGANIZATION",
                document.getKnowledgeDomainKey()
        ));
        return bindings;
    }

    private String buildTaskScopeRefKey(Long taskId) {
        return taskId == null ? null : String.valueOf(taskId);
    }

    /**
     * 单个文档在某个正式召回层级下的绑定结果。
     */
    public record ScopeBinding(RetrievalScope retrievalScope,
                               Long taskId,
                               String scopeRefKey,
                               String knowledgeDomainKey) {
    }
}
