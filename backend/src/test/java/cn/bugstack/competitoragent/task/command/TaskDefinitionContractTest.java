package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskDefinitionContractTest {

    @Test
    void shouldExposeBusinessPlanSemanticsInPreviewResponse() {
        TaskPlanPreviewResponse response = TaskPlanPreviewResponse.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .goal("围绕企业级 RAG 平台展开竞品研究")
                .competitorCount(1)
                .collectorCount(1)
                .pipelineCount(4)
                .stages(List.of())
                .nodes(List.of(TaskPlanPreviewNodeResponse.builder()
                        .nodeName("collect_sources_01_01")
                        .stageCode("SOURCE_STRATEGY")
                        .goal("优先覆盖官网与产品文档，必要时再补充公网搜索")
                        .fallbackOrder(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"))
                        .sourceUrls(List.of())
                        .build()))
                .sourceUrls(List.of())
                .build();

        assertEquals("TASK_PLAN_PREVIEW_V1", response.getContractType());
        assertEquals("围绕企业级 RAG 平台展开竞品研究", response.getGoal());
        assertEquals(1, response.getCompetitorCount());
        assertEquals(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"),
                response.getNodes().get(0).getFallbackOrder());
        assertNotNull(response.getSourceUrls());
        assertFalse(response.getNodes().isEmpty());
    }
}
