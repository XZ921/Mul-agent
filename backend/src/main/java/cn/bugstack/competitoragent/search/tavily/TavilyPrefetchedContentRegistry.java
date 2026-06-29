package cn.bugstack.competitoragent.search.tavily;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tavily 预取正文运行时注册表。
 * 该注册表只在单次任务运行期保存正文引用，Executor 消费时通过 remove 原子取走，
 * 避免 get + evict 带来的重复消费问题，同时继续满足 SourceCandidate 只保留轻量引用的约束。
 */
@Component
public class TavilyPrefetchedContentRegistry {

    private final ConcurrentHashMap<String, TavilyPrefetchedContent> contents = new ConcurrentHashMap<>();
    private final AtomicInteger localSequence = new AtomicInteger(0);

    /**
     * 注册一份 Tavily 预取正文并返回轻量引用。
     * 引用格式固定为 tavily:{requestId}:{rank}，便于后续审计与 executor 诊断直接回溯来源。
     */
    public String register(TavilyPrefetchedContent content) {
        if (content == null) {
            String fallbackRequestId = UUID.randomUUID().toString();
            String ref = buildRef(fallbackRequestId, localSequence.incrementAndGet());
            contents.put(ref, null);
            return ref;
        }
        String requestId = StringUtils.hasText(content.getRequestId())
                ? content.getRequestId().trim()
                : UUID.randomUUID().toString();
        int rank = content.getResultRank() == null || content.getResultRank() <= 0
                ? localSequence.incrementAndGet()
                : content.getResultRank();
        String ref = buildRef(requestId, rank);
        contents.put(ref, content);
        return ref;
    }

    /**
     * 通过原子 remove 消费正文。
     * 这样同一份正文只会被一个下游执行器取走，避免并发场景重复采集。
     */
    public Optional<TavilyPrefetchedContent> remove(String ref) {
        if (!StringUtils.hasText(ref)) {
            return Optional.empty();
        }
        return Optional.ofNullable(contents.remove(ref.trim()));
    }

    public int size() {
        return contents.size();
    }

    private String buildRef(String requestId, int rank) {
        return "tavily:" + requestId + ":" + Math.max(0, rank);
    }
}
