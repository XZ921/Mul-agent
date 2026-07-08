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
}
