package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProgressSnapshotTest {

    @Test
    void shouldCountSuccessDegradedNodeAsCompletedInsteadOfActive() {
        AnalysisTask task = AnalysisTask.builder()
                .id(88L)
                .build();
        TaskNode degradedCollector = TaskNode.builder()
                .taskId(88L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS_DEGRADED)
                .build();
        TaskNode runningAnalyzer = TaskNode.builder()
                .taskId(88L)
                .nodeName("analyze_competitors")
                .displayName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .status(TaskNodeStatus.RUNNING)
                .build();

        TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                task,
                AnalysisTaskStatus.RUNNING,
                null,
                List.of(degradedCollector, runningAnalyzer)
        );

        assertThat(snapshot.getCompletedNodes()).isEqualTo(1);
        assertThat(snapshot.getActiveNodeNames()).containsExactly("analyze_competitors");
    }

    @Test
    void shouldMarkStageOneDegradedReadySnapshotAsReviewRecommendedSuccess() {
        AnalysisTask task = AnalysisTask.builder()
                .id(89L)
                .build();
        TaskNode writerNode = TaskNode.builder()
                .taskId(89L)
                .nodeName("write_report")
                .displayName("write_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "sourceUrls":[
                            "https://www.notion.so/product/ai",
                            "https://www.notion.so/security",
                            "https://docs.notion.so/ai",
                            "https://docs.notion.so/admins",
                            "https://www.g2.com/products/notion-ai/reviews"
                          ]
                        }
                        """)
                .build();
        TaskNode reviewNode = TaskNode.builder()
                .taskId(89L)
                .nodeName("quality_check")
                .displayName("quality_check")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "passed": false,
                          "requiresHumanIntervention": true,
                          "diagnoses": [
                            {
                              "type":"MISSING_CITATION",
                              "section":"定价策略",
                              "severity":"ERROR",
                              "level":"BLOCKER"
                            }
                          ]
                        }
                        """)
                .build();

        TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                task,
                AnalysisTaskStatus.SUCCESS,
                null,
                List.of(writerNode, reviewNode)
        );

        assertThat(snapshot.getStatusSummary()).contains("降级").contains("人工复核");
        assertThat(snapshot.getCurrentStage()).contains("降级").contains("人工复核");
    }
}
