package cn.bugstack.competitoragent.rag;

import org.springframework.util.StringUtils;

/**
 * 检索召回作用域枚举。
 * <p>
 * Task 5.3 开始，系统不再把所有 RAG 命中都混成“任务内命中”，
 * 而是显式区分：
 * 1. `TASK`：当前任务自己沉淀的证据与知识；
 * 2. `DOMAIN`：组织内某个知识域沉淀的可复用资料；
 * 3. `ORGANIZATION`：组织级公共知识底座。
 * 这样后续检索、重排、上下文摘要和审计都能明确说明“这段上下文到底来自哪一层”。
 */
public enum RetrievalScope {

    TASK,
    DOMAIN,
    ORGANIZATION;

    public static RetrievalScope fromText(String value) {
        if (!StringUtils.hasText(value)) {
            return TASK;
        }
        return RetrievalScope.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
