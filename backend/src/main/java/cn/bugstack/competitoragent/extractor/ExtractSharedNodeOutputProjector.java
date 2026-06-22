package cn.bugstack.competitoragent.extractor;

import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
import cn.bugstack.competitoragent.task.SharedNodeOutputProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * extract_schema 输出投影器。
 * 负责把提取节点原始 outputData 裁剪为稳定、轻量、可恢复的 shared projection，
 * 防止完整正文继续进入 sharedState / Redis / 下游节点上下文。
 */
@Component
@RequiredArgsConstructor
public class ExtractSharedNodeOutputProjector implements SharedNodeOutputProjector {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String outputData) {
        return ExtractSharedProjection.supportsExtractorOutput(objectMapper, outputData);
    }

    @Override
    public SharedNodeOutputEnvelope project(Long taskId,
                                            String nodeName,
                                            Long planVersionId,
                                            String outputData) {
        ExtractSharedProjection projection = ExtractSharedProjection.fromExtractorOutput(objectMapper, outputData);
        return SharedNodeOutputEnvelope.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(planVersionId)
                .projectionType(ExtractSharedProjection.PROJECTION_TYPE)
                .payloadJson(writeProjection(projection))
                .sourceUrls(projection.getSourceUrls() == null ? List.of() : projection.getSourceUrls())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String writeProjection(ExtractSharedProjection projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (Exception e) {
            throw new IllegalStateException("serialize extract shared projection failed", e);
        }
    }
}
