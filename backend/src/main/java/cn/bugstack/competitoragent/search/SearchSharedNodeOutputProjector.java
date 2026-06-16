package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
import cn.bugstack.competitoragent.task.SharedNodeOutputProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索 Collector 输出投影器。
 * <p>
 * 该类负责把搜索节点原始 outputData 裁剪为 SearchSharedProjection；
 * DagExecutor 不再关心搜索字段解析细节。
 */
@Component
@RequiredArgsConstructor
public class SearchSharedNodeOutputProjector implements SharedNodeOutputProjector {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String outputData) {
        return SearchSharedProjection.supportsCollectorOutput(objectMapper, outputData);
    }

    @Override
    public SharedNodeOutputEnvelope project(Long taskId,
                                            String nodeName,
                                            Long planVersionId,
                                            String outputData) {
        SearchSharedProjection projection = SearchSharedProjection.fromCollectorOutput(objectMapper, outputData);
        return SharedNodeOutputEnvelope.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(planVersionId)
                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                .payloadJson(writeProjection(projection))
                .sourceUrls(projection.getSourceUrls() == null ? List.of() : projection.getSourceUrls())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String writeProjection(SearchSharedProjection projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (Exception e) {
            throw new IllegalStateException("serialize search shared projection failed", e);
        }
    }
}
